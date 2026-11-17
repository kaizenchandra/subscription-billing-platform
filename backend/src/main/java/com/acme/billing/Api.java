package com.acme.billing;
import java.util.*;
import java.time.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/v1")
class Api {
 final boolean demo;final PlanChanges plans;final BillingService s;final org.springframework.jdbc.core.JdbcTemplate db;Api(BillingService s,org.springframework.jdbc.core.JdbcTemplate db,PlanChanges plans,@org.springframework.beans.factory.annotation.Value("${billing.demo}") boolean demo){this.s=s;this.db=db;this.plans=plans;this.demo=demo;}
 String tenant(Authentication a){if(a.getPrincipal() instanceof Jwt j){String t=j.getClaimAsString("tenant_id");if(t==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);return t;}return a.getName();}
 void finance(Authentication a){boolean allowed=a.getAuthorities().stream().anyMatch(x->Set.of("ROLE_FINANCE","SCOPE_finance").contains(x.getAuthority()));if(!allowed)throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
 record Create(String plan,int quantity,int trialDays,String zone){}
 record Collect(String scenario){}
 record Amount(long amount,String reason){}
 record Usage(String eventId,UUID subscription,long quantity,Instant occurredAt){}
 record PlanPreview(UUID subscription,String plan,int quantity,String mode){}
 record ApplyPlan(UUID quote){}
 @PostMapping("/billing/preview") Object preview(Authentication a,@RequestBody PlanPreview p){ownSubscription(a,p.subscription());return plans.preview(tenant(a),p.subscription(),p.plan(),p.quantity(),p.mode());}
 @PostMapping("/subscriptions/{id}/upgrade") Object upgrade(Authentication a,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody ApplyPlan p){ownSubscription(a,id);return plans.apply(tenant(a),id,key,p.quote());}
 @PostMapping("/subscriptions/{id}/downgrade") Object downgrade(Authentication a,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody ApplyPlan p){ownSubscription(a,id);return plans.apply(tenant(a),id,key,p.quote());}
 @GetMapping("/subscriptions/{id}/changes") Object changes(Authentication a,@PathVariable UUID id){ownSubscription(a,id);return db.queryForList("select c.*,q.target_plan,q.quantity,q.effective_at,q.net,q.currency from subscription_changes c join plan_change_quotes q on q.id=c.quote where c.tenant=? and c.subscription=? order by c.created_at desc limit 100",tenant(a),id);}
 @GetMapping("/invoices/{id}/lines") Object lines(Authentication a,@PathVariable UUID id){ownInvoice(a,id);return db.queryForList("select * from invoice_lines where invoice=? order by amount",id);}

 void ownPayment(Authentication a,UUID id){s.one("select p.id from payments p join invoices i on i.id=p.invoice join subscriptions x on x.id=i.subscription where p.tenant=? and x.customer=? and p.id=?",tenant(a),a.getName(),id);}
 @PostMapping("/payments/{id}/retry") Object retry(Authentication a,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key){ownPayment(a,id);return s.retryPayment(tenant(a),id,key);}
 @PostMapping("/payments/{id}/simulator-method") Object method(Authentication a,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key){if(!demo)throw new ResponseStatusException(HttpStatus.NOT_FOUND);ownPayment(a,id);return s.updateSimulatorMethod(tenant(a),id,key);}
 @GetMapping("/payments/{id}/attempts") Object attempts(Authentication a,@PathVariable UUID id){ownPayment(a,id);return db.queryForList("select * from payment_attempts where payment=? order by number",id);}
 @GetMapping("/customers/me/dunning") Object customerDunning(Authentication a){return db.queryForList("select d.*,p.invoice,p.state,p.failure_class,p.next_retry_at,p.provider_not_before,p.retry_deadline,p.attempt_count,p.max_attempts,p.recovery_authorized,p.method_version,i.number,i.currency,p.amount,s.id as subscription from dunning_cases d join payments p on p.id=d.payment join invoices i on i.id=p.invoice join subscriptions s on s.id=i.subscription where d.tenant=? and s.customer=? order by d.started_at desc limit 100",tenant(a),a.getName());}
 @GetMapping("/customers/me/notifications") Object notifications(Authentication a){return db.queryForList("select n.* from billing_notifications n join payments p on p.id=n.payment join invoices i on i.id=p.invoice join subscriptions s on s.id=i.subscription where n.tenant=? and s.customer=? order by n.created_at desc limit 100",tenant(a),a.getName());}
 @GetMapping("/admin/dunning") Object dunning(Authentication a){finance(a);return db.queryForList("select d.*,p.state,p.failure_class,p.next_retry_at,p.provider_not_before,p.attempt_count,p.max_attempts,p.retry_deadline,p.recovery_authorized,p.method_version,p.invoice,i.number,i.currency,p.amount from dunning_cases d join payments p on p.id=d.payment join invoices i on i.id=p.invoice where d.tenant=? order by d.started_at desc limit 100",tenant(a));}
 @GetMapping("/admin/payment-attempts") Object adminAttempts(Authentication a){finance(a);return db.queryForList("select a.* from payment_attempts a join payments p on p.id=a.payment where p.tenant=? order by a.created_at desc limit 100",tenant(a));}
 @PostMapping("/admin/payments/{id}/retry") Object adminRetry(Authentication a,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key){finance(a);return s.retryPayment(tenant(a),id,key);}
 @GetMapping("/billing/capabilities") Object capabilities(){return Map.of("simulatorMethodRecovery",demo);}
 @GetMapping("/plans") Object plans(){return db.queryForList("select * from plans where active=true order by amount");}
 @GetMapping("/subscriptions") Object subscriptions(Authentication a){return db.queryForList("select * from subscriptions where tenant=? and customer=? order by period_start desc limit 100",tenant(a),a.getName());}
 @PostMapping("/subscriptions") Object create(Authentication a,@RequestHeader("Idempotency-Key") String key,@RequestBody Create c){return s.create(tenant(a),a.getName(),key,c.plan(),c.quantity(),c.trialDays(),c.zone());}
 @PostMapping("/subscriptions/{id}/{action}") Object change(Authentication a,@PathVariable UUID id,@PathVariable String action,@RequestHeader("Idempotency-Key") String key){ownSubscription(a,id);return s.change(tenant(a),id,key,action);}
 void ownSubscription(Authentication a,UUID id){s.one("select id from subscriptions where tenant=? and customer=? and id=?",tenant(a),a.getName(),id);}
 void ownInvoice(Authentication a,UUID id){s.one("select i.id from invoices i join subscriptions x on x.id=i.subscription where i.tenant=? and x.customer=? and i.id=?",tenant(a),a.getName(),id);}
 @GetMapping("/invoices") Object invoices(Authentication a){return db.queryForList("select i.* from invoices i join subscriptions x on x.id=i.subscription where i.tenant=? and x.customer=? order by i.number desc limit 100",tenant(a),a.getName());}
 @GetMapping("/invoices/{id}") Object invoice(Authentication a,@PathVariable UUID id){ownInvoice(a,id);return s.one("select * from invoices where tenant=? and id=?",tenant(a),id);}
 @PostMapping("/invoices/{id}/collect") Object collect(Authentication a,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody Collect c){ownInvoice(a,id);return s.collect(tenant(a),id,key,c.scenario());}
 @PostMapping("/payments/{id}/refunds") Object refund(Authentication a,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody Amount c){finance(a);return s.refund(tenant(a),id,key,c.amount());}
 @PostMapping("/invoices/{id}/credits") Object credit(Authentication a,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody Amount c){finance(a);return s.credit(tenant(a),id,key,c.amount(),c.reason());}
 @GetMapping("/payments") Object payments(Authentication a){return db.queryForList("select p.* from payments p join invoices i on i.id=p.invoice join subscriptions x on x.id=i.subscription where p.tenant=? and x.customer=? order by p.created_at desc limit 100",tenant(a),a.getName());}
 @GetMapping("/customers/me/entitlements") Object entitlements(Authentication a){return db.queryForList("select id,plan,quantity,state,case when state in ('ACTIVE','TRIALING') then true when state='PAST_DUE' and exists(select 1 from invoices i where i.subscription=subscriptions.id and i.status='PAID') and exists(select 1 from dunning_cases d join payments p on p.id=d.payment join invoices i on i.id=p.invoice where i.subscription=subscriptions.id and d.status not in ('RECOVERED','STOPPED') and d.grace_until>now()) and not exists(select 1 from dunning_cases d join payments p on p.id=d.payment join invoices i on i.id=p.invoice where i.subscription=subscriptions.id and d.status not in ('RECOVERED','STOPPED') and d.grace_until<=now()) then true else false end as access from subscriptions where tenant=? and customer=? limit 100",tenant(a),a.getName());}
 @GetMapping("/admin/ledger") Object ledger(Authentication a){finance(a);return db.queryForList("select * from journals where tenant=? order by created_at desc limit 100",tenant(a));}
 @GetMapping("/admin/reconciliation") Object discrepancies(Authentication a){finance(a);return db.queryForList("select * from discrepancies where tenant=? order by created_at desc limit 100",tenant(a));}
 @PostMapping("/admin/reconciliation/run") Object reconcile(Authentication a){finance(a);return Map.of("recovered",s.reconcile(tenant(a)));}
 @PostMapping("/usage/events") Object usage(Authentication a,@RequestBody Usage u){ownSubscription(a,u.subscription());return s.usage(tenant(a),u.eventId(),u.subscription(),u.quantity(),u.occurredAt());}
 @GetMapping("/usage/summary") Object usageSummary(Authentication a){return db.queryForList("select u.subscription,sum(u.quantity) as quantity from usage_events u join subscriptions x on x.id=u.subscription where u.tenant=? and x.customer=? group by u.subscription limit 100",tenant(a),a.getName());}
}
@RestControllerAdvice
class Errors {
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<ProblemDetail> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(ProblemDetail.forStatusAndDetail(e.getStatusCode(),Objects.toString(e.getReason(),"Request rejected")));}
 @ExceptionHandler({IllegalArgumentException.class,DateTimeException.class,NullPointerException.class}) ResponseEntity<ProblemDetail> invalid(Exception e){return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Invalid request fields"));}
}
