package com.acme.billing;
import com.acme.billing.provider.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;

@RestController @RequestMapping("/api/v1")
class RazorpayApi {
 private final Api auth;private final RazorpayService service;private final RazorpayStore store;private final RazorpayWebhooks hooks;private final RazorpaySettings settings;private final PaymentProvider provider;private final JdbcTemplate db;
 RazorpayApi(Api auth,RazorpayService service,RazorpayStore store,RazorpayWebhooks hooks,RazorpaySettings settings,PaymentProvider provider,JdbcTemplate db){this.auth=auth;this.service=service;this.store=store;this.hooks=hooks;this.settings=settings;this.provider=provider;this.db=db;}
 record Setup(UUID invoice){} record Confirm(String paymentId,String signature){}
 @GetMapping("/payments/providers") Object providers(){return Map.of("razorpayEnabled",settings.enabled,"mode","test","capabilities",provider.capabilities());}
 @PostMapping("/payments/setup") Object setup(Authentication a,@RequestHeader("Idempotency-Key") String key,@RequestBody Setup body){return service.setup(auth.tenant(a),a.getName(),body.invoice(),key);}
 @GetMapping("/payments/checkouts/{id}") Object checkout(Authentication a,@PathVariable UUID id){return service.view(auth.tenant(a),a.getName(),id);}
 @GetMapping("/customers/me/checkouts") Object checkouts(Authentication a){return db.queryForList("select c.*,p.amount from razorpay_checkouts c join payments p on p.id=c.id where c.tenant=? and c.customer=? order by c.created_at desc limit 100",auth.tenant(a),a.getName());}
 @PostMapping("/payments/checkouts/{id}/verify") Object verify(Authentication a,@PathVariable UUID id,@RequestBody Confirm body){return service.confirm(auth.tenant(a),a.getName(),id,body.paymentId(),body.signature());}
 @GetMapping("/customers/me/payment-methods") Object methods(Authentication a){return db.queryForList("select id,provider,method,last4,archived,created_at from payment_method_references where tenant=? and customer=? order by created_at desc limit 100",auth.tenant(a),a.getName());}
 @PostMapping("/customers/me/payment-methods/{id}/archive") Object archive(Authentication a,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key){store.archive(auth.tenant(a),a.getName(),id,key);return Map.of("archived",true);}
 @PostMapping("/webhooks/payments/razorpay") Object webhook(@RequestHeader(value="X-Razorpay-Event-Id",required=false) String id,@RequestHeader(value="X-Razorpay-Signature",required=false) String signature,HttpServletRequest request) throws java.io.IOException {
  return Map.of("event",hooks.receive(id,signature,request.getInputStream().readNBytes(65537)));
 }
 record RecoverOrder(String orderId){}
 @GetMapping("/admin/razorpay/checkouts") Object adminCheckouts(Authentication a){auth.finance(a);return db.queryForList("select * from razorpay_checkouts where tenant=? order by created_at desc limit 100",auth.tenant(a));}
 @PostMapping("/admin/razorpay/checkouts/{id}/reconcile-order") Object reconcileOrder(Authentication a,@PathVariable UUID id,@RequestBody RecoverOrder body){auth.finance(a);var c=auth.s.one("select * from razorpay_checkouts where tenant=? and id=?",auth.tenant(a),id);return service.recoverOrder(auth.tenant(a),(String)c.get("customer"),id,body.orderId());}
 @GetMapping("/admin/razorpay/events") Object events(Authentication a){auth.finance(a);return db.queryForList("select e.id,e.event_type,e.order_id,e.payment_id,e.status,e.attempts,e.received_at from razorpay_events e join razorpay_checkouts c on c.order_id=e.order_id and c.account_id=e.account_id where c.tenant=? order by e.received_at desc limit 100",auth.tenant(a));}
}
