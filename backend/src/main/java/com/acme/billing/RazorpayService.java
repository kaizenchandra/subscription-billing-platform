package com.acme.billing;
import java.util.*;
import com.acme.billing.provider.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Remote calls deliberately run outside PostgreSQL transactions. */
@Service
public class RazorpayService {
 private final RazorpayStore store;private final PaymentProvider provider;private final RazorpaySettings settings;
 public RazorpayService(RazorpayStore store,PaymentProvider provider,RazorpaySettings settings){this.store=store;this.provider=provider;this.settings=settings;}
 private void enabled(){if(!settings.enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Razorpay sandbox is not configured");}
 public Map<String,Object> setup(String tenant,String customer,UUID invoice,String key){
  enabled();var c=store.reserve(tenant,customer,invoice,key);UUID id=(UUID)c.get("id");
  if(store.claim(id)){
   try{ // amount is immutable and sourced from invoice; no caller-provided charge
    var order=provider.createOrder(store.amount(id),"INR",id.toString());store.bind(id,order);
   }catch(RuntimeException e){store.uncertain(id);throw new ResponseStatusException(HttpStatus.CONFLICT,"Order creation outcome is uncertain. Preserve the checkout identity; do not create another order.");}
  }
  return view(tenant,customer,id);
 }
 public Map<String,Object> view(String tenant,String customer,UUID id){
  var c=store.owned(tenant,customer,id);var result=new LinkedHashMap<String,Object>(c);result.put("key_id",settings.publicKey());result.put("amount",store.amount(id));result.put("currency","INR");return result;
 }
 public Map<String,Object> recoverOrder(String tenant,String customer,UUID id,String orderId){
  enabled();store.owned(tenant,customer,id);var verified=provider.fetchOrder(orderId);
  if(!verified.id().equals(orderId))throw BillingService.conflict("Order lookup identity mismatch");
  store.bind(id,verified);return view(tenant,customer,id);
 }
 public Map<String,Object> confirm(String tenant,String customer,UUID id,String payment,String signature){
  enabled();var c=store.owned(tenant,customer,id);
  if(c.get("order_id")==null || !settings.checkoutValid((String)c.get("order_id"),payment,signature))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid checkout signature");
  var verified=provider.fetchPayment(payment);
  if(!verified.id().equals(payment))throw BillingService.conflict("Provider payment identity mismatch");
  return store.captured(id,verified);
 }
}
