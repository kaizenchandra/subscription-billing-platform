package com.acme.billing.provider;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** No automatic POST retries; no redirects or provider response bodies in exceptions/logs. */
@Component
public final class RazorpayClient implements PaymentProvider {
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 private final Semaphore calls=new Semaphore(4);
 private final RazorpaySettings settings;private final URI base;private final JsonMapper json=new JsonMapper();
 @Autowired public RazorpayClient(RazorpaySettings settings){this(settings,URI.create("https://api.razorpay.com/v1/"));}
 RazorpayClient(RazorpaySettings settings,URI base){this.settings=settings;this.base=base;}
 public Capabilities capabilities(){return new Capabilities(true,false,false,false);}
 public Order createOrder(long amount,String currency,String receipt){
  var result=request("POST","orders",json.writeValueAsString(Map.of("amount",amount,"currency",currency,"receipt",receipt,"partial_payment",false)));
  return new Order(required(result,"id","order_"),amount(result),result.path("currency").asText(),result.path("receipt").asText());
 }
 public Order fetchOrder(String id){
  if(!id.matches("order_[A-Za-z0-9]{1,64}"))throw new IllegalArgumentException("Invalid provider order identifier");
  var order=request("GET","orders/"+id,null);return new Order(required(order,"id","order_"),amount(order),order.path("currency").asText(),order.path("receipt").asText());
 }
 public Payment fetchPayment(String id){
  if(!id.matches("pay_[A-Za-z0-9]{1,64}"))throw new IllegalArgumentException("Invalid provider payment identifier");
  var p=request("GET","payments/"+id,null);
  return new Payment(required(p,"id","pay_"),p.path("order_id").asText(),amount(p),p.path("currency").asText(),p.path("status").asText(),p.path("captured").asBoolean(false),safeMethod(p.path("method").asText()),safeLast4(p.path("card").path("last4").asText()));
 }
 public static String safeMethod(String method){return Set.of("card","upi","netbanking","wallet","emi","paylater").contains(method)?method:"other";}
 public static String safeLast4(String last4){return last4!=null&&last4.matches("[0-9]{4}")?last4:null;}
 private long amount(JsonNode node){var a=node.path("amount");if(!a.isIntegralNumber()||!a.canConvertToLong()||a.asLong()<0)throw new IllegalStateException("Invalid provider monetary amount");return a.asLong();}
 private String required(JsonNode n,String field,String prefix){String v=n.path(field).asText();if(!v.matches(prefix+"[A-Za-z0-9]{1,64}"))throw new IllegalStateException("Invalid provider response");return v;}
 private JsonNode request(String method,String path,String body){
  if(!settings.enabled)throw new IllegalStateException("Razorpay sandbox is disabled");
  if(!calls.tryAcquire())throw new IllegalStateException("Provider concurrency limit reached");
  try{
   String auth=Base64.getEncoder().encodeToString((settings.publicKey()+":"+settings.secret()).getBytes(StandardCharsets.UTF_8));
   var req=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).header("Authorization","Basic "+auth).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
   var pending=client.sendAsync(req,info->new LimitedBody());
   HttpResponse<byte[]> res;
   try{res=pending.get(5,java.util.concurrent.TimeUnit.SECONDS);}
   catch(java.util.concurrent.TimeoutException|java.util.concurrent.ExecutionException e){pending.cancel(true);throw new IllegalStateException("Provider outcome unknown");}
   catch(InterruptedException e){pending.cancel(true);throw e;}
   if(res.statusCode()<200||res.statusCode()>=300)throw new IllegalStateException("Provider request was not confirmed");
   try{return json.readTree(res.body());}catch(RuntimeException e){throw new IllegalStateException("Invalid provider response");}
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Provider outcome unknown");}
  finally{calls.release();}
 }
 private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
  private java.util.concurrent.Flow.Subscription subscription;private long bytes;
  public java.util.concurrent.CompletionStage<byte[]> getBody(){return delegate.getBody();}
  public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
  public void onNext(java.util.List<java.nio.ByteBuffer> buffers){
   for(var b:buffers)bytes+=b.remaining();
   if(bytes>65536){subscription.cancel();delegate.onError(new java.io.IOException("Provider response exceeds limit"));}else delegate.onNext(buffers);
  }
  public void onError(Throwable error){delegate.onError(error);}public void onComplete(){delegate.onComplete();}
 }
}
