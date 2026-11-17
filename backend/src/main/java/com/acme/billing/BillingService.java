package com.acme.billing;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service
public class BillingService {
 final JdbcTemplate db;
 private final PaymentRecovery recovery;
 BillingService(JdbcTemplate db){this(db,Clock.systemUTC(),RetryPolicy.standard());}
 BillingService(JdbcTemplate db,Clock clock,RetryPolicy policy){this.db=db;this.recovery=new PaymentRecovery(this,db,clock,policy);}
 @org.springframework.beans.factory.annotation.Autowired
 BillingService(JdbcTemplate db,
  @org.springframework.beans.factory.annotation.Value("${billing.retry.max-attempts:4}") int max,
  @org.springframework.beans.factory.annotation.Value("${billing.retry.base-seconds:21600}") long base,
  @org.springframework.beans.factory.annotation.Value("${billing.retry.window-seconds:604800}") long window,
  @org.springframework.beans.factory.annotation.Value("${billing.retry.grace-seconds:864000}") long grace){
  this(db,Clock.systemUTC(),new RetryPolicy(max,Duration.ofSeconds(base),Duration.ofSeconds(window),Duration.ofSeconds(grace)));
 }
 @Transactional public Map<String,Object> retryPayment(String tenant,UUID id,String key){return recovery.retry(tenant,id,key,false);}
 @Transactional public Map<String,Object> automaticRetry(String tenant,UUID id,String key){return recovery.retry(tenant,id,key,true);}
 @Transactional public Map<String,Object> updateSimulatorMethod(String tenant,UUID id,String key){return recovery.updateMethod(tenant,id,key);}
 @Transactional public void providerCaptured(UUID id){recovery.externalOutcome(id,true);}
 @Transactional public void providerUnknown(UUID id){recovery.externalOutcome(id,false);}
 @Transactional public void advanceDunning(UUID id){recovery.advance(id);}

 static ResponseStatusException conflict(String s){return new ResponseStatusException(HttpStatus.CONFLICT,s);}
 static Instant instant(Object v){return ((java.sql.Timestamp)v).toInstant();}
 static long num(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
 Map<String,Object> subscription(String tenant,UUID id){return one("select * from subscriptions where tenant=? and id=? for update",tenant,id);}
 Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.getFirst();}
 UUID operation(String tenant,String kind,String key,String payload){
  if(key==null || key.isBlank() || key.length()>160)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key required (max 160)");
  String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
  UUID id=UUID.randomUUID();
  db.update("insert into operations values (?,?,?,?,?) on conflict do nothing",tenant,kind,key,hash,id);
  var row=one("select * from operations where tenant=? and kind=? and key=?",tenant,kind,key);
  if(!hash.equals(row.get("fingerprint")))throw conflict("Idempotency payload mismatch");
  return (UUID)row.get("result");
 }
 void event(String tenant,String type,UUID id){db.update("insert into outbox(id,tenant,type,aggregate_id) values (?,?,?,?)",UUID.randomUUID(),tenant,type+".v1",id);}
 void journal(String tenant,String source,String currency,String debit,String credit,long amount){db.update("insert into journals(id,tenant,source,currency,debit_account,credit_account,amount) values (?,?,?,?,?,?,?) on conflict(source) do nothing",UUID.randomUUID(),tenant,source,currency,debit,credit,amount);}
 @Transactional public Map<String,Object> create(String tenant,String customer,String key,String plan,int quantity,int trialDays,String zone){
  if(quantity<1 || quantity>10000 || trialDays<0 || trialDays>30)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  ZoneId tz=ZoneId.of(zone);
  UUID id=operation(tenant,"CREATE",key,customer+"|"+plan+"|"+quantity+"|"+trialDays+"|"+zone);
  if(!db.queryForList("select id from subscriptions where id=?",id).isEmpty())return one("select * from subscriptions where id=?",id);
  // Serialize trial eligibility for an account without relying on an in-memory lock.
  db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",tenant+":"+customer);
  if(trialDays>0 && !db.queryForList("select id from subscriptions where tenant=? and customer=?",tenant,customer).isEmpty())throw conflict("Trial already used by this account");
  var price=one("select * from plans where id=? and active=true",plan);
  Instant now=Instant.now();Instant anchorInstant=now.plus(Duration.ofDays(trialDays));
  LocalDateTime anchor=LocalDateTime.ofInstant(anchorInstant,tz);
  db.update("insert into subscriptions(id,tenant,customer,plan,currency,amount,months,quantity,state,anchor,zone,period_start,period_end) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",id,tenant,customer,plan,price.get("currency"),price.get("amount"),price.get("months"),quantity,trialDays>0?"TRIALING":"INCOMPLETE",anchor,zone,java.sql.Timestamp.from(now),java.sql.Timestamp.from(anchorInstant));
  job(id,anchorInstant);event(tenant,"SubscriptionCreated",id);return one("select * from subscriptions where id=?",id);
 }
 void job(UUID id,Instant due){db.update("insert into jobs(id,subscription,due,expected_cycle) select ?,?,?,cycle from subscriptions where id=?",UUID.randomUUID(),id,java.sql.Timestamp.from(due),id);}
 @Transactional public Map<String,Object> change(String tenant,UUID id,String key,String action){
  var s=subscription(tenant,id);UUID op=operation(tenant,"CHANGE:"+id,key,action);
  if(!db.queryForList("select id from outbox where aggregate_id=?",op).isEmpty())return s;
  String state=(String)s.get("state");
  switch(action){
   case "pause" -> {if(!state.equals("ACTIVE"))throw conflict("Only ACTIVE may pause");db.update("update subscriptions set revision=revision+1,state='PAUSED' where id=?",id);}
   case "resume" -> {if(!state.equals("PAUSED"))throw conflict("Only PAUSED may resume");db.update("update subscriptions set revision=revision+1,state=case when exists(select 1 from payments p join invoices i on i.id=p.invoice where i.subscription=? and p.state in ('DECLINED','UNKNOWN')) then 'PAST_DUE' else 'ACTIVE' end where id=?",id,id);}
   case "cancel" -> {db.update("update subscriptions set revision=revision+1,state='CANCELLED' where id=?",id);db.update("update subscription_changes set status='CANCELLED' where subscription=? and status='SCHEDULED'",id);}
   case "cancel-at-end" -> {if(state.equals("CANCELLED"))throw conflict("Already cancelled");db.update("update subscriptions set revision=revision+1,cancel_at_end=true where id=?",id);db.update("update subscription_changes set status='CANCELLED' where subscription=? and status='SCHEDULED'",id);}
   default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported action");
  }
  event(tenant,"SubscriptionChanged",op);return one("select * from subscriptions where id=?",id);
 }
 @Transactional public void bill(UUID jobId){
  var j=one("select * from jobs where id=? for update",jobId);
  if(!j.get("status").equals("READY") || instant(j.get("due")).isAfter(Instant.now()))return;
  var s=one("select * from subscriptions where id=? for update",j.get("subscription"));
  UUID sid=(UUID)s.get("id");String tenant=(String)s.get("tenant");
  if(num(j,"expected_cycle")!=num(s,"cycle")){db.update("update jobs set status='DONE' where id=?",jobId);return;}
  if(s.get("state").equals("CANCELLED")){db.update("update jobs set status='DONE' where id=?",jobId);return;}
  if(s.get("state").equals("PAUSED")){db.update("update jobs set due=now()+interval '1 hour',lease_until=null where id=?",jobId);return;}
  if(Boolean.TRUE.equals(s.get("cancel_at_end"))){db.update("update subscriptions set revision=revision+1,state='CANCELLED' where id=?",sid);db.update("update jobs set status='DONE' where id=?",jobId);return;}
  var scheduled=db.queryForList("select c.id,q.target_plan,q.target_amount,q.quantity from subscription_changes c join plan_change_quotes q on q.id=c.quote where c.subscription=? and c.status='SCHEDULED' for update of c",sid);
  if(!scheduled.isEmpty()){
   var change=scheduled.getFirst();
   db.update("update subscriptions set plan=?,amount=?,quantity=? where id=?",change.get("target_plan"),change.get("target_amount"),change.get("quantity"),sid);
   db.update("update subscription_changes set status='APPLIED',applied_at=now() where id=?",change.get("id"));
   event(tenant,"SubscriptionPlanChanged",(UUID)change.get("id"));
   s=one("select * from subscriptions where id=?",sid);
  }
  long cycle=num(s,"cycle");
  var anchor=((java.sql.Timestamp)s.get("anchor")).toLocalDateTime();var zone=ZoneId.of((String)s.get("zone"));int months=((Number)s.get("months")).intValue();
  Instant start=CalendarPolicy.boundary(anchor,zone,months,cycle),end=CalendarPolicy.boundary(anchor,zone,months,cycle+1);
  UUID invoice=UUID.randomUUID();long total=Math.multiplyExact(num(s,"amount"),num(s,"quantity"));
  int inserted=db.update("insert into invoices(id,tenant,subscription,cycle,currency,total,period_start,period_end,status) values (?,?,?,?,?,?,?,?,'OPEN') on conflict(subscription,cycle) do nothing",invoice,tenant,sid,cycle,s.get("currency"),total,java.sql.Timestamp.from(start),java.sql.Timestamp.from(end));
  if(inserted==1){
   db.update("insert into invoice_lines values (?,?,?,?,?,?,?,?)",UUID.randomUUID(),invoice,"Recurring subscription",total,s.get("currency"),s.get("plan"),java.sql.Timestamp.from(start),java.sql.Timestamp.from(end));
   journal(tenant,"invoice:"+invoice,(String)s.get("currency"),"RECEIVABLE","DEFERRED_REVENUE",total);event(tenant,"InvoiceFinalized",invoice);}
  db.update("update subscriptions set revision=revision+1,cycle=cycle+1,period_start=?,period_end=?,state=case when state='TRIALING' then 'INCOMPLETE' else state end where id=?",java.sql.Timestamp.from(start),java.sql.Timestamp.from(end),sid);
  db.update("update jobs set status='DONE' where id=?",jobId);job(sid,end);
 }
 @Transactional public Map<String,Object> collect(String tenant,UUID invoice,String key,String scenario){
  if(!PaymentRecovery.SCENARIOS.contains(scenario))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown simulator scenario");
  var i=one("select * from invoices where tenant=? and id=? for update",tenant,invoice);
  UUID id=operation(tenant,"COLLECT:"+invoice,key,scenario);
  var existing=db.queryForList("select * from payments where invoice=?",invoice);if(!existing.isEmpty())return existing.getFirst();
  if(!i.get("status").equals("OPEN"))throw conflict("Invoice not collectable");
  db.update("insert into payments(id,tenant,invoice,amount,state,scenario) values (?,?,?,?,'PENDING',?)",id,tenant,invoice,num(i,"total")-num(i,"credit"),scenario);recovery.initial(id);event(tenant,"PaymentRequested",id);return one("select * from payments where id=?",id);
 }
 @Transactional public void simulate(UUID id){recovery.simulate(id);}

 void apply(Map<String,Object> p,String result){
  UUID id=(UUID)p.get("id");String tenant=(String)p.get("tenant");
  if(p.get("state").equals("SUCCEEDED"))return;
  var i=one("select * from invoices where id=? for update",p.get("invoice"));
  db.update("update payments set state=? where id=?",result,id);
  if(result.equals("SUCCEEDED")){
   db.update("update invoices set paid=?,status='PAID' where id=?",p.get("amount"),i.get("id"));
   journal(tenant,"payment:"+id,(String)i.get("currency"),"PROCESSOR_CLEARING","RECEIVABLE",num(p,"amount"));
   db.update("update subscriptions set revision=revision+1,state='ACTIVE' where id=? and state in ('PAST_DUE','INCOMPLETE') and not exists (select 1 from payments p join invoices x on x.id=p.invoice where x.subscription=? and p.state in ('DECLINED','UNKNOWN','PENDING'))",i.get("subscription"),i.get("subscription"));
  }else db.update("update subscriptions set revision=revision+1,state='PAST_DUE' where id=? and state in ('ACTIVE','INCOMPLETE')",i.get("subscription"));
  event(tenant,"Payment"+result,id);
 }
 @Transactional public Map<String,Object> refund(String tenant,UUID payment,String key,long amount){
  var p=one("select * from payments where tenant=? and id=? for update",tenant,payment);
  if(!p.get("provider").equals("SIMULATOR"))throw conflict("Razorpay refunds are not supported by this adapter; do not record a simulated refund");
  UUID id=operation(tenant,"REFUND:"+payment,key,Long.toString(amount));
  var old=db.queryForList("select * from refunds where id=?",id);if(!old.isEmpty())return old.getFirst();
  if(!p.get("state").equals("SUCCEEDED") || amount<=0 || amount>num(p,"amount")-num(p,"refunded"))throw conflict("Refund exceeds eligible captured balance");
  db.update("update payments set refunded=refunded+? where id=?",amount,payment);
  db.update("insert into refunds values (?,?,?,?)",id,tenant,payment,amount);
  var i=one("select * from invoices where id=?",p.get("invoice"));
  journal(tenant,"refund:"+id,(String)i.get("currency"),"REFUND_EXPENSE","PROCESSOR_CLEARING",amount);event(tenant,"RefundCompleted",id);return one("select * from refunds where id=?",id);
 }
 @Transactional public Map<String,Object> credit(String tenant,UUID invoice,String key,long amount,String reason){
  var i=one("select * from invoices where tenant=? and id=? for update",tenant,invoice);UUID id=operation(tenant,"CREDIT:"+invoice,key,amount+"|"+reason);
  var old=db.queryForList("select * from credits where id=?",id);if(!old.isEmpty())return old.getFirst();
  if(amount<=0 || amount>num(i,"total")-num(i,"paid")-num(i,"credit"))throw conflict("Credit exceeds outstanding balance");
  if(!db.queryForList("select id from payments where invoice=?",invoice).isEmpty())throw conflict("Collection already initiated; reconcile before crediting");
  db.update("insert into credits values (?,?,?,?,?)",id,tenant,invoice,amount,reason);
  db.update("update invoices set credit=credit+?,status=case when paid+credit+?=total then 'PAID' else status end where id=?",amount,amount,invoice);
  journal(tenant,"credit:"+id,(String)i.get("currency"),"DEFERRED_REVENUE","RECEIVABLE",amount);return one("select * from credits where id=?",id);
 }
 @Transactional public int reconcile(String tenant){return recovery.reconcile(tenant);}
 @Transactional public Map<String,Object> usage(String tenant,String key,UUID subscription,long quantity,Instant at){
  subscription(tenant,subscription);if(quantity<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  db.update("insert into usage_events values (?,?,?,?,?) on conflict do nothing",tenant,key,subscription,quantity,java.sql.Timestamp.from(at));
  var e=one("select * from usage_events where tenant=? and event_id=?",tenant,key);
  if(!e.get("subscription").equals(subscription)||num(e,"quantity")!=quantity||!instant(e.get("occurred_at")).equals(at))throw conflict("Usage event payload mismatch");return e;
 }
}
