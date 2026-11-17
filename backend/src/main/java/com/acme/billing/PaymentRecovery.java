package com.acme.billing;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.acme.billing.BillingService.*;

/** Invoked only inside BillingService transaction boundaries; simulator operations have no network side effects. */
final class PaymentRecovery {
    static final Set<String> SCENARIOS=Set.of("SUCCESS","HARD_DECLINE","ACCEPTED_RESPONSE_LOST","OUTAGE",
        "SOFT_DECLINE","SOFT_DECLINE_THEN_SUCCESS","RATE_LIMIT","TEMPORARY_FAILURE","AUTHENTICATION_REQUIRED","EXPIRED_METHOD","INVALID_MANDATE");
    private final BillingService billing;
    private final JdbcTemplate db;
    private final Clock clock;
    private final RetryPolicy policy;
    PaymentRecovery(BillingService billing,JdbcTemplate db,Clock clock,RetryPolicy policy){this.billing=billing;this.db=db;this.clock=clock;this.policy=policy;}
    private static java.sql.Timestamp ts(Instant t){return java.sql.Timestamp.from(t);}
    private Map<String,Object> payment(String tenant,UUID id){return billing.one("select * from payments where tenant=? and id=? for update",tenant,id);}
    private Map<String,Object> latest(UUID id){return billing.one("select * from payment_attempts where payment=? order by number desc limit 1",id);}
    private void notice(Map<String,Object> p,String kind,String message){
        db.update("insert into billing_notifications values (?,?,?,?,?,?) on conflict(payment,kind) do nothing",UUID.randomUUID(),p.get("tenant"),p.get("id"),kind,message,ts(clock.instant()));
    }
    void initial(UUID id){
        var p=billing.one("select * from payments where id=? for update",id);
        db.update("insert into payment_attempts(id,payment,number,state,method_version,scenario,created_at) values (?,?,1,'PENDING',0,?,?)",id,id,p.get("scenario"),ts(clock.instant()));
        db.update("update payments set attempt_count=1 where id=?",id);
    }
    void simulate(UUID id){
        var p=billing.one("select * from payments where id=? for update",id);
        if(!p.get("provider").equals("SIMULATOR")||!p.get("state").equals("PENDING"))return;
        var a=latest(id);String scenario=(String)a.get("scenario");
        boolean lost=scenario.equals("ACCEPTED_RESPONSE_LOST");
        if(scenario.equals("OUTAGE")) {outcome(p,a,"UNKNOWN","UNKNOWN");return;}
        String failure=switch(scenario){
            case "SOFT_DECLINE_THEN_SUCCESS" -> num(a,"number")==1?"SOFT_DECLINE":null;
            case "SUCCESS","ACCEPTED_RESPONSE_LOST" -> null;
            default -> scenario;
        };
        String state=failure==null?"SUCCEEDED":"DECLINED";
        db.update("insert into provider_payments values (?,?,?) on conflict do nothing",a.get("id"),p.get("amount"),state);
        outcome(p,a,lost?"UNKNOWN":state,lost?"UNKNOWN":failure);
    }
    private boolean retryable(String failure){return Set.of("SOFT_DECLINE","RATE_LIMIT","TEMPORARY_FAILURE").contains(failure);}
    private void outcome(Map<String,Object> p,Map<String,Object> a,String state,String failure){
        UUID id=(UUID)p.get("id");Instant now=clock.instant();
        db.update("update payment_attempts set state=?,failure_class=?,completed_at=? where id=?",state,failure,state.equals("UNKNOWN")?null:ts(now),a.get("id"));
        db.update("update payments set state=?,failure_class=?,next_retry_at=null where id=?",state,failure,id);
        if(state.equals("SUCCEEDED")){
            // BillingService owns invoice allocation and its balanced journal; pass the pre-success aggregate.
            billing.apply(p,"SUCCEEDED");
            db.update("update dunning_cases set status='RECOVERED',resolved_at=? where payment=?",ts(now),id);
            if(!db.queryForList("select payment from dunning_cases where payment=?",id).isEmpty())notice(p,"RECOVERED","Payment verified. This invoice has been paid; access follows all outstanding invoices.");
            return;
        }
        var invoice=billing.one("select * from invoices where id=? for update",p.get("invoice"));
        db.update("update subscriptions set revision=revision+1,state='PAST_DUE' where id=? and state in ('ACTIVE','INCOMPLETE')",invoice.get("subscription"));
        db.update("insert into dunning_cases values (?,?,?,?,?,?,?,null) on conflict(payment) do nothing",id,p.get("tenant"),ts(now),ts(now.plus(policy.gracePeriod())),ts(now.plus(policy.gracePeriod().dividedBy(2))),ts(now.plus(policy.gracePeriod().multipliedBy(3).dividedBy(4))),"OPEN");
        if(p.get("retry_deadline")==null)db.update("update payments set retry_deadline=?,max_attempts=?,retry_base_seconds=? where id=?",ts(now.plus(policy.retryWindow())),policy.maxAttempts(),policy.baseDelay().toSeconds(),id);
        if("RATE_LIMIT".equals(failure))db.update("update payments set provider_not_before=? where id=?",ts(now.plus(Duration.ofDays(1))),id);
        var fresh=payment((String)p.get("tenant"),id);
        String status=state.equals("UNKNOWN")?"UNKNOWN":retryable(failure)?"OPEN":"ACTION_REQUIRED";
        if(status.equals("OPEN")){
            Instant next=policy.next(id,(int)num(fresh,"attempt_count"),now,"RATE_LIMIT".equals(failure)?Duration.ofDays(1):Duration.ZERO,num(fresh,"retry_base_seconds"));
            if(num(fresh,"attempt_count")>=num(fresh,"max_attempts") || !next.isBefore(instant(fresh.get("retry_deadline"))))status="EXHAUSTED";
            else db.update("update payments set next_retry_at=? where id=?",ts(next),id);
        }
        db.update("update dunning_cases set status=? where payment=?",status,id);
        notice(p,"INITIAL",state.equals("UNKNOWN")?"Payment outcome is unknown. Do not pay again while we verify provider status.":"Payment failed. Review payment recovery, retry timing, and your access grace period.");
        billing.event((String)p.get("tenant"),"DunningUpdated",id);
    }
    Map<String,Object> retry(String tenant,UUID id,String key,boolean automatic){
        var p=payment(tenant,id);
        if(!p.get("provider").equals("SIMULATOR"))throw conflict("This provider requires customer-present checkout; automatic retry is unsupported");
        UUID op=billing.operation(tenant,"PAYMENT_RETRY:"+id,key,"retry");
        var replay=db.queryForList("select * from payment_attempts where id=?",op);if(!replay.isEmpty())return replay.getFirst();
        if(!p.get("state").equals("DECLINED"))throw conflict("Retry requires a verified failed outcome; pending/unknown payments must be reconciled");
        boolean recovered=Boolean.TRUE.equals(p.get("recovery_authorized"));
        if(!(retryable(Objects.toString(p.get("failure_class"),"")) || (!automatic&&recovered)))throw conflict("Update the payment method or complete provider action before retrying");
        Instant now=clock.instant();
        if(p.get("retry_deadline")==null || !now.isBefore(instant(p.get("retry_deadline"))) || num(p,"attempt_count")>=num(p,"max_attempts"))throw conflict("Payment retry budget exhausted");
        if(p.get("provider_not_before")!=null && now.isBefore(instant(p.get("provider_not_before"))))throw conflict("Provider Retry-After has not elapsed");
        if(!recovered && (p.get("next_retry_at")==null || now.isBefore(instant(p.get("next_retry_at")))))throw conflict("Retry is not due yet; respect backoff and Retry-After");
        var invoice=billing.one("select * from invoices where id=? for update",p.get("invoice"));
        var sub=billing.subscription(tenant,(UUID)invoice.get("subscription"));
        if(!invoice.get("status").equals("OPEN") || Set.of("CANCELLED","PAUSED").contains(sub.get("state")))throw conflict("Collection is not eligible for this invoice/subscription");
        int number=(int)num(p,"attempt_count")+1;
        String scenario=recovered?"SUCCESS":(String)p.get("scenario");
        db.update("insert into payment_attempts(id,payment,number,state,method_version,scenario,created_at) values (?,?,?,'PENDING',?,?,?)",op,id,number,p.get("method_version"),scenario,ts(now));
        db.update("update payments set state='PENDING',failure_class=null,attempt_count=?,next_retry_at=null,recovery_authorized=false where id=?",number,id);
        billing.event(tenant,"PaymentRetryRequested",id);return latest(id);
    }
    Map<String,Object> updateMethod(String tenant,UUID id,String key){
        var p=payment(tenant,id);if(!p.get("provider").equals("SIMULATOR"))throw conflict("Simulator recovery is unavailable for Razorpay payments");UUID op=billing.operation(tenant,"SIMULATOR_METHOD:"+id,key,"approved-simulator-method");
        if(!db.queryForList("select id from simulator_method_updates where id=?",op).isEmpty())return p;
        if(!p.get("state").equals("DECLINED"))throw conflict("Payment method recovery requires a verified decline");
        if(p.get("retry_deadline")==null || !clock.instant().isBefore(instant(p.get("retry_deadline"))) || num(p,"attempt_count")>=num(p,"max_attempts"))throw conflict("Retry budget exhausted; contact finance for an audited resolution");
        db.update("insert into simulator_method_updates values (?,?,?,?)",op,id,num(p,"method_version")+1,ts(clock.instant()));
        db.update("update payments set method_version=method_version+1,recovery_authorized=true where id=?",id);
        billing.event(tenant,"SimulatorPaymentMethodUpdated",op);return payment(tenant,id);
    }
    void externalOutcome(UUID id,boolean captured){
        var p=billing.one("select * from payments where id=? for update",id);
        if(!p.get("provider").equals("RAZORPAY"))throw conflict("Wrong payment provider");
        if(p.get("state").equals("SUCCEEDED"))return;
        outcome(p,latest(id),captured?"SUCCEEDED":"UNKNOWN",captured?null:"UNKNOWN");
    }
    int reconcile(String tenant){
        int fixed=0;
        for(var candidate:db.queryForList("select id from payments where tenant=? and provider='SIMULATOR' and state='UNKNOWN' order by id limit 100",tenant)){
            var p=payment(tenant,(UUID)candidate.get("id"));if(!p.get("state").equals("UNKNOWN"))continue;
            var a=latest((UUID)p.get("id"));var result=db.queryForList("select * from provider_payments where id=?",a.get("id"));
            if(!result.isEmpty() && num(result.getFirst(),"amount")==num(p,"amount") && Set.of("SUCCEEDED","DECLINED").contains(result.getFirst().get("state"))){
                String state=(String)result.getFirst().get("state");outcome(p,a,state,state.equals("DECLINED")?"HARD_DECLINE":null);fixed++;
            } else db.update("insert into discrepancies(id,tenant,reference,kind) values (?,?,?,'UNKNOWN_PAYMENT') on conflict do nothing",UUID.randomUUID(),tenant,p.get("id"));
        }return fixed;
    }
    void advance(UUID id){
        var p=billing.one("select * from payments where id=? for update",id);
        var cases=db.queryForList("select * from dunning_cases where payment=?",id);if(cases.isEmpty())return;
        var d=cases.getFirst();if(Set.of("RECOVERED","STOPPED","RESTRICTED").contains(d.get("status")))return;
        var invoice=billing.one("select * from invoices where id=? for update",p.get("invoice"));
        var sub=billing.subscription((String)p.get("tenant"),(UUID)invoice.get("subscription"));
        if(sub.get("state").equals("CANCELLED")){
            db.update("update payments set next_retry_at=null where id=?",id);db.update("update dunning_cases set status='STOPPED',resolved_at=? where payment=?",ts(clock.instant()),id);return;
        }
        Instant now=clock.instant();
        if(!now.isBefore(instant(d.get("grace_until")))){
            db.update("update dunning_cases set status='RESTRICTED' where payment=?",id);db.update("update payments set next_retry_at=null where id=?",id);
            notice(p,"RESTRICTED","The payment grace period has ended. Service access is restricted until outstanding payments are resolved.");
            billing.event((String)p.get("tenant"),"EntitlementRestricted",id);return;
        }
        if(!now.isBefore(instant(d.get("reminder_at"))))notice(p,"REMINDER","Payment remains outstanding. Check recovery actions before the grace period ends.");
        if(!now.isBefore(instant(d.get("final_reminder_at"))))notice(p,"FINAL_REMINDER","Final reminder: resolve the outstanding payment before the displayed grace deadline.");
        if(p.get("retry_deadline")!=null && !now.isBefore(instant(p.get("retry_deadline"))) && d.get("status").equals("OPEN")){
            db.update("update dunning_cases set status='EXHAUSTED' where payment=?",id);db.update("update payments set next_retry_at=null where id=?",id);
        }
    }
}
