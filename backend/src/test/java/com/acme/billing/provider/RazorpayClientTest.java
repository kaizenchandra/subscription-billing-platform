package com.acme.billing.provider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
class RazorpayClientTest {
 static RazorpaySettings settings(){return new RazorpaySettings(true,"rzp_test_fixture","test-only-secret","test-webhook-secret-123","","acc_fixture");}
 @Test void usesDocumentedOrderContractAndBasicAuthWithoutRetryingErrors() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
  server.createContext("/v1/orders",e->{calls.incrementAndGet();String auth=e.getRequestHeaders().getFirst("Authorization");assertEquals("Basic "+java.util.Base64.getEncoder().encodeToString("rzp_test_fixture:test-only-secret".getBytes(StandardCharsets.UTF_8)),auth);String body=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertTrue(body.contains("49900"));assertTrue(body.contains("\"receipt\":\"stable\""));assertTrue(body.contains("\"partial_payment\":false"));byte[] response="{\"id\":\"order_fixture\",\"amount\":49900,\"currency\":\"INR\",\"receipt\":\"stable\"}".getBytes();e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);e.close();});
  server.start();try{var client=new RazorpayClient(settings(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/"));assertEquals("order_fixture",client.createOrder(49900,"INR","stable").id());assertFalse(client.capabilities().automaticRecurring());}finally{server.stop(0);}
  assertEquals(1,calls.get());
 }
 @Test void httpFailureDoesNotRepostOrExposeProviderBody() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
  server.createContext("/v1/orders",e->{calls.incrementAndGet();byte[] response="secret-provider-detail".getBytes();e.sendResponseHeaders(503,response.length);e.getResponseBody().write(response);e.close();});server.start();
  try{var client=new RazorpayClient(settings(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/"));var error=assertThrows(IllegalStateException.class,()->client.createOrder(49900,"INR","stable"));assertFalse(error.getMessage().contains("secret-provider-detail"));assertEquals(1,calls.get());}finally{server.stop(0);}
 }
 @Test void signaturesUseRawBytesAndRejectTampering(){
  byte[] body="{ \"x\": 1 }".getBytes(StandardCharsets.UTF_8);String signature=sign(body,"test-webhook-secret-123");assertTrue(settings().webhookValid(body,signature));assertFalse(settings().webhookValid("{\"x\":1}".getBytes(),signature));assertFalse(settings().webhookValid(body,"bad"));
  var rotated=new RazorpaySettings(true,"rzp_test_fixture","test-only-secret","new-webhook-secret-123","previous-webhook-secret","acc_fixture");assertTrue(rotated.webhookValid(body,sign(body,"previous-webhook-secret")));
  assertThrows(IllegalArgumentException.class,()->new RazorpaySettings(true,"rzp_live_anything","secret","test-webhook-secret-123","","acc_fixture"));
 }
 @Test void fullResponseTimeoutIsBoundedAndNeverReposted() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
  server.createContext("/v1/orders",e->{calls.incrementAndGet();try{e.sendResponseHeaders(200,0);e.getResponseBody().write('{');e.getResponseBody().flush();Thread.sleep(6000);}catch(Exception ignored){}finally{e.close();}});server.start();
  try{var client=new RazorpayClient(settings(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/"));assertTimeoutPreemptively(java.time.Duration.ofSeconds(9),()->assertThrows(IllegalStateException.class,()->client.createOrder(49900,"INR","stable")));assertEquals(1,calls.get());}finally{server.stop(0);}
 }
 @Test void rejectsFractionalMoneyAndOversizedResponses() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
  server.createContext("/v1/orders",e->{String body=calls.incrementAndGet()==1?"{\"id\":\"order_fixture\",\"amount\":49900.5,\"currency\":\"INR\",\"receipt\":\"stable\"}":"x".repeat(70000);byte[] bytes=body.getBytes();try{e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}finally{e.close();}});server.start();
  try{var client=new RazorpayClient(settings(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/"));assertThrows(IllegalStateException.class,()->client.createOrder(49900,"INR","stable"));assertThrows(IllegalStateException.class,()->client.createOrder(49900,"INR","stable"));assertEquals(2,calls.get());}finally{server.stop(0);}
 }
 public static String sign(byte[] bytes,String key){try{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return java.util.HexFormat.of().formatHex(mac.doFinal(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
