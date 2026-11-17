package com.acme.billing;
import com.acme.billing.provider.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Local contract fixture only: never packaged in the production jar. */
@TestConfiguration(proxyBeanMethods=false)
public class FakeRazorpayConfiguration {
 public static final String API_SECRET="fixture-api-secret",WEBHOOK_SECRET="fixture-webhook-secret";
 @Bean @Primary RazorpaySettings fakeSettings(){return new RazorpaySettings(true,"rzp_test_fixture",API_SECRET,WEBHOOK_SECRET,"fixture-previous-secret","acc_fixture");}
 @Bean @Primary FakeRazorpay fakeProvider(){return new FakeRazorpay();}
 public static String sign(byte[] body,String secret){try{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(body));}catch(Exception e){throw new IllegalStateException(e);}}
 public static class FakeRazorpay implements PaymentProvider {
  final AtomicInteger posts=new AtomicInteger(),gets=new AtomicInteger();
  final Map<String,Order> orders=new ConcurrentHashMap<>();volatile boolean loseResponse=false,authorizeOnly=false;volatile java.util.function.Consumer<Order> beforeReturn;
  public Capabilities capabilities(){return new Capabilities(true,false,false,false);}
  public Order createOrder(long amount,String currency,String receipt){
   if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("Network call inside a financial transaction");
   posts.incrementAndGet();var order=new Order("order_"+receipt.replace("-",""),amount,currency,receipt);orders.put(order.id(),order);
   var callback=beforeReturn;if(callback!=null)callback.accept(order);
   if(loseResponse)throw new IllegalStateException("Simulated accepted-but-response-lost");return order;
  }
  public Order fetchOrder(String id){return Objects.requireNonNull(orders.get(id));}
  public Payment fetchPayment(String id){
   if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("Network call inside a financial transaction");
   gets.incrementAndGet();Order order=fetchOrder("order_"+id.substring(4));return new Payment(id,order.id(),order.amount(),order.currency(),authorizeOnly?"authorized":"captured",!authorizeOnly,"card","1234");
  }
 }
}
