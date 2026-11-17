package com.acme.billing;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component
class Workers {
 final BillingService service;final boolean autoCollect;final org.springframework.jdbc.core.JdbcTemplate db;
 Workers(BillingService service,org.springframework.jdbc.core.JdbcTemplate db,com.acme.billing.provider.RazorpaySettings razorpay,@org.springframework.beans.factory.annotation.Value("${billing.auto-collect:true}") boolean autoCollect){this.service=service;this.autoCollect=autoCollect&&!razorpay.enabled;this.db=db;}
 @Scheduled(fixedDelay=1000) public void tick(){
  // Atomic claim is committed before work. A crashed claimant is eligible after 30 seconds.
  var jobs=db.queryForList("update jobs set lease_until=now()+interval '30 seconds',attempts=attempts+1 where id in (select id from jobs where status='READY' and due<=now() and (lease_until is null or lease_until<now()) and attempts<8 order by due limit 16 for update skip locked) returning id");
  for(var j:jobs)try{service.bill((UUID)j.get("id"));}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Billing job failed: {}",e.getClass().getSimpleName());}
  if(autoCollect) for(var i:db.queryForList("select i.id,i.tenant from invoices i where i.status='OPEN' and not exists(select 1 from payments p where p.invoice=i.id) order by i.number limit 16"))try{service.collect((String)i.get("tenant"),(UUID)i.get("id"),"automatic:"+i.get("id"),"SUCCESS");}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Collection scheduling failed: {}",e.getClass().getSimpleName());}
  for(var p:db.queryForList("select p.id,p.tenant,p.attempt_count from payments p join invoices i on i.id=p.invoice join subscriptions s on s.id=i.subscription where p.state='DECLINED' and p.next_retry_at<=now() and p.retry_deadline>now() and p.attempt_count<p.max_attempts and s.state in ('ACTIVE','PAST_DUE','INCOMPLETE') order by p.next_retry_at limit 16"))try{service.automaticRetry((String)p.get("tenant"),(UUID)p.get("id"),"scheduled:"+p.get("attempt_count"));}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Retry worker rejected: {}",e.getClass().getSimpleName());}
  for(var d:db.queryForList("select d.payment from dunning_cases d where d.status not in ('RECOVERED','STOPPED','RESTRICTED') and (d.grace_until<=now() or (d.reminder_at<=now() and not exists(select 1 from billing_notifications n where n.payment=d.payment and n.kind='REMINDER')) or (d.final_reminder_at<=now() and not exists(select 1 from billing_notifications n where n.payment=d.payment and n.kind='FINAL_REMINDER')) or exists(select 1 from payments p where p.id=d.payment and p.retry_deadline<=now() and d.status='OPEN') or exists(select 1 from payments p join invoices i on i.id=p.invoice join subscriptions s on s.id=i.subscription where p.id=d.payment and s.state='CANCELLED')) order by d.started_at limit 16"))try{service.advanceDunning((UUID)d.get("payment"));}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Dunning worker failed: {}",e.getClass().getSimpleName());}
  for(var p:db.queryForList("select id from payments where provider='SIMULATOR' and state='PENDING' order by created_at limit 16"))try{service.simulate((UUID)p.get("id"));}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Payment worker failed: {}",e.getClass().getSimpleName());}
 }
}
