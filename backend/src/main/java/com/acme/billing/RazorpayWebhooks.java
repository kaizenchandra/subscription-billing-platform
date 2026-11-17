package com.acme.billing;
import com.acme.billing.provider.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.security.MessageDigest;
import static com.acme.billing.BillingService.*;

@Service
public class RazorpayWebhooks {
 private final RazorpaySettings settings;private final JdbcTemplate db;private final BillingService billing;private final RazorpayStore store;
 private final JsonMapper json=new JsonMapper();
 public RazorpayWebhooks(RazorpaySettings settings,JdbcTemplate db,BillingService billing,RazorpayStore store){this.settings=settings;this.db=db;this.billing=billing;this.store=store;}
 @Transactional public UUID receive(String eventId,String signature,byte[] raw){
  if(raw.length>65536)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
  if(!settings.webhookValid(raw,signature))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid webhook signature");
  if(eventId==null||!eventId.matches("[A-Za-z0-9_-]{1,160}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid event identity");
  String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception e){throw new IllegalStateException(e);}
  tools.jackson.databind.JsonNode envelope;try{envelope=json.readTree(raw);}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Malformed webhook JSON");}
  if(!settings.account().equals(envelope.path("account_id").asText()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Wrong provider account");
  String type=envelope.path("event").asText();
  var payment=envelope.path("payload").path("payment").path("entity");
  boolean supported=Set.of("payment.captured","payment.authorized","payment.failed","order.paid").contains(type);
  String order=payment.path("order_id").asText(),pay=payment.path("id").asText();
  if(supported && (!order.matches("order_[A-Za-z0-9]{1,64}")||!pay.matches("pay_[A-Za-z0-9]{1,64}")||!payment.path("amount").isIntegralNumber()||!payment.path("amount").canConvertToLong()||payment.path("amount").asLong()<0||!payment.path("currency").asText().matches("[A-Z]{3}")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid payment event");
  UUID id=UUID.randomUUID();
  db.update("insert into razorpay_events(id,account_id,event_id,body_hash,event_type,order_id,payment_id,amount,currency,captured,method,last4,status) values (?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing",
    id,settings.account(),eventId,hash,type,supported?order:null,supported?pay:null,supported?payment.path("amount").asLong():null,supported?payment.path("currency").asText():null,
    supported&&payment.path("captured").asBoolean(false)&&payment.path("status").asText().equals("captured"),RazorpayClient.safeMethod(payment.path("method").asText()),RazorpayClient.safeLast4(payment.path("card").path("last4").asText()),supported?"RECEIVED":"IGNORED");
  var byId=db.queryForList("select * from razorpay_events where account_id=? and event_id=?",settings.account(),eventId);
  if(!byId.isEmpty()&&!hash.equals(byId.getFirst().get("body_hash")))throw conflict("Webhook event identity reused with different content");
  return (UUID)billing.one("select id from razorpay_events where account_id=? and body_hash=?",settings.account(),hash).get("id");
 }
 @Transactional public void process(UUID id){
  var e=billing.one("select * from razorpay_events where id=? for update",id);if(!e.get("status").equals("RECEIVED"))return;
  var sessions=db.queryForList("select * from razorpay_checkouts where account_id=? and order_id=?",e.get("account_id"),e.get("order_id"));
  if(sessions.isEmpty()){
   db.update("update razorpay_events set attempts=attempts+1,next_run=now()+interval '30 seconds',status=case when attempts>=19 then 'REVIEW' else status end where id=?",id);return;
  }
  var c=sessions.getFirst();UUID checkout=(UUID)c.get("id");
  var p=billing.one("select * from payments where id=? for update",checkout);
  c=billing.one("select * from razorpay_checkouts where id=?",checkout);
  // Do not use unauthenticated notes/customer IDs to select an invoice or tenant.
  if(num(e,"amount")!=num(p,"amount")||!Objects.equals(e.get("currency"),"INR")){
   review(e,c);return;
  }
  if(Boolean.TRUE.equals(e.get("captured")) && Set.of("payment.captured","order.paid").contains(e.get("event_type"))){
   if(c.get("payment_id")!=null&&!c.get("payment_id").equals(e.get("payment_id"))){review(e,c);return;}
   var invoice=billing.one("select * from invoices where id=? for update",p.get("invoice"));
   if(!c.get("state").equals("PAID")&&(!invoice.get("status").equals("OPEN")||num(invoice,"total")-num(invoice,"paid")-num(invoice,"credit")!=num(p,"amount"))){review(e,c);return;}
   store.captured(checkout,new PaymentProvider.Payment((String)e.get("payment_id"),(String)e.get("order_id"),num(e,"amount"),(String)e.get("currency"),"captured",true,(String)e.get("method"),(String)e.get("last4")));
  } else store.observed(checkout,(String)e.get("event_type"),(String)e.get("payment_id")); // No financial allocation for failures/authorizations.
  db.update("update razorpay_events set status='PROCESSED',attempts=attempts+1 where id=?",id);
 }
 private void review(Map<String,Object> e,Map<String,Object> c){
  db.update("update razorpay_events set status='REVIEW' where id=?",e.get("id"));
  db.update("insert into discrepancies(id,tenant,reference,kind) values (?,?,?,'RAZORPAY_EVENT_MISMATCH') on conflict do nothing",UUID.randomUUID(),c.get("tenant"),e.get("id"));
 }
}
