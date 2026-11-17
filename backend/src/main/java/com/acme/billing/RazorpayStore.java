package com.acme.billing;
import com.acme.billing.provider.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.acme.billing.BillingService.*;

@Service
public class RazorpayStore {
 private final JdbcTemplate db;private final BillingService billing;private final RazorpaySettings settings;
 public RazorpayStore(JdbcTemplate db,BillingService billing,RazorpaySettings settings){this.db=db;this.billing=billing;this.settings=settings;}
 @Transactional public Map<String,Object> reserve(String tenant,String customer,UUID invoice,String key){
  var i=billing.one("select i.* from invoices i join subscriptions s on s.id=i.subscription where i.tenant=? and s.customer=? and i.id=? for update of i",tenant,customer,invoice);
  UUID id=billing.operation(tenant,"RAZORPAY_SETUP:"+invoice,key,invoice.toString());
  var old=db.queryForList("select * from razorpay_checkouts where invoice=?",invoice);if(!old.isEmpty())return old.getFirst();
  if(!i.get("status").equals("OPEN") || !i.get("currency").equals("INR") || num(i,"total")-num(i,"paid")-num(i,"credit")<100)throw conflict("Checkout requires an open INR invoice of at least 100 minor units");
  if(!db.queryForList("select id from payments where invoice=?",invoice).isEmpty())throw conflict("Invoice already has a collection operation; reconcile it before changing provider");
  var sub=billing.one("select state from subscriptions where id=?",i.get("subscription"));
  if(Set.of("CANCELLED","PAUSED").contains(sub.get("state")))throw conflict("Subscription is not eligible for new checkout");
  db.update("insert into payments(id,tenant,invoice,amount,state,scenario,provider,attempt_count) values (?,?,?,?,'PENDING','RAZORPAY','RAZORPAY',1)",id,tenant,invoice,num(i,"total")-num(i,"paid")-num(i,"credit"));
  db.update("insert into payment_attempts(id,payment,number,state,method_version,scenario,created_at) values (?,?,1,'PENDING',0,'RAZORPAY',now())",id,id);
  db.update("insert into razorpay_checkouts(id,tenant,customer,invoice,account_id,state) values (?,?,?,?,?,'PREPARED')",id,tenant,customer,invoice,settings.account());
  billing.event(tenant,"RazorpayCheckoutPrepared",id);return owned(tenant,customer,id);
 }
 public Map<String,Object> owned(String tenant,String customer,UUID id){return billing.one("select * from razorpay_checkouts where tenant=? and customer=? and id=?",tenant,customer,id);}
 public long amount(UUID id){return num(billing.one("select amount from payments where id=?",id),"amount");}
 @Transactional public boolean claim(UUID id){return db.update("update razorpay_checkouts set state='CREATING' where id=? and state='PREPARED'",id)==1;}
 @Transactional public void bind(UUID id,PaymentProvider.Order order){
  var p=billing.one("select * from payments where id=? for update",id);
  var c=billing.one("select * from razorpay_checkouts where id=? for update",id);
  if(order.amount()!=num(p,"amount")||!order.currency().equals("INR")||!order.receipt().equals(id.toString()))throw conflict("Provider order does not match the prepared checkout");
  if(c.get("order_id")!=null&&!c.get("order_id").equals(order.id()))throw conflict("Order identity already bound");
  db.update("update razorpay_checkouts set order_id=?,state=case when state='PAID' then state else 'READY' end where id=?",order.id(),id);
  db.update("update razorpay_events set status='RECEIVED',attempts=0,next_run=now() where account_id=? and order_id=? and status='REVIEW'",c.get("account_id"),order.id());
 }
 @Transactional public void uncertain(UUID id){
  var p=billing.one("select * from payments where id=? for update",id);
  var c=billing.one("select * from razorpay_checkouts where id=? for update",id);
  if(c.get("state").equals("PAID")||c.get("order_id")!=null)return;
  db.update("update razorpay_checkouts set state='UNKNOWN' where id=?",id);
  billing.providerUnknown(id);
 }
 @Transactional public Map<String,Object> captured(UUID id,PaymentProvider.Payment payment){
  var p=billing.one("select * from payments where id=? for update",id);
  var c=billing.one("select * from razorpay_checkouts where id=? for update",id);
  if(!payment.order().equals(c.get("order_id"))||payment.amount()!=num(p,"amount")||!payment.currency().equals("INR"))throw conflict("Provider amount, currency or order mismatch");
  if(!payment.captured()||!payment.status().equals("captured")){
   if(payment.status().equals("authorized"))observed(id,"payment.authorized",payment.id());
   return billing.one("select * from razorpay_checkouts where id=?",id); // authorization is not collection
  }
  if(c.get("state").equals("PAID")){
   if(!payment.id().equals(c.get("payment_id")))throw conflict("Unexpected second captured payment for one order");return c;
  }
  var i=billing.one("select * from invoices where id=? for update",p.get("invoice"));
  if(!i.get("status").equals("OPEN")||num(i,"total")-num(i,"paid")-num(i,"credit")!=payment.amount())throw conflict("Invoice balance changed before capture allocation");
  db.update("update razorpay_checkouts set state='PAID',payment_id=?,last_status='captured' where id=?",payment.id(),id);
  billing.providerCaptured(id);
  db.update("insert into payment_method_references(id,tenant,customer,provider,provider_payment_id,method,last4) values (?,?,?,'RAZORPAY',?,?,?) on conflict(provider_payment_id) do nothing",UUID.randomUUID(),c.get("tenant"),c.get("customer"),payment.id(),RazorpayClient.safeMethod(payment.method()),RazorpayClient.safeLast4(payment.last4()));
  return billing.one("select * from razorpay_checkouts where id=?",id);
 }
 @Transactional public void observed(UUID id,String type,String paymentId){
  billing.one("select id from payments where id=? for update",id);
  var c=billing.one("select * from razorpay_checkouts where id=? for update",id);if(c.get("state").equals("PAID"))return;
  if(type.equals("payment.authorized"))db.update("update razorpay_checkouts set last_status='authorized',authorized_payment_id=? where id=?",paymentId,id);
  if(type.equals("payment.failed") && (c.get("authorized_payment_id")==null || paymentId.equals(c.get("authorized_payment_id"))))db.update("update razorpay_checkouts set last_status='failed',authorized_payment_id=null where id=?",id);
 }
 @Transactional public void archive(String tenant,String customer,UUID id,String key){
  var ref=billing.one("select * from payment_method_references where tenant=? and customer=? and id=? for update",tenant,customer,id);
  billing.operation(tenant,"ARCHIVE_METHOD:"+id,key,id.toString());db.update("update payment_method_references set archived=true where id=?",ref.get("id"));
 }
}
