package com.acme.billing;
import com.acme.billing.provider.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import static com.acme.billing.FakeRazorpayConfiguration.*;
@Testcontainers
class RazorpayTest {
 @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
 static ConfigurableApplicationContext app;
 JdbcTemplate db;BillingService billing;RazorpayService service;RazorpayStore store;RazorpayWebhooks hooks;FakeRazorpay fake;
 String tenant,customer;UUID invoice;
 @BeforeAll static void boot(){app=new org.springframework.boot.builder.SpringApplicationBuilder(Application.class,FakeRazorpayConfiguration.class).run("--server.port=0","--billing.demo=true","--billing.auto-collect=false","--spring.datasource.url="+pg.getJdbcUrl(),"--spring.datasource.username="+pg.getUsername(),"--spring.datasource.password="+pg.getPassword());}
 @AfterAll static void close(){if(app!=null)app.close();}
 @BeforeEach void setup(){
  db=app.getBean(JdbcTemplate.class);billing=app.getBean(BillingService.class);service=app.getBean(RazorpayService.class);store=app.getBean(RazorpayStore.class);hooks=app.getBean(RazorpayWebhooks.class);fake=app.getBean(FakeRazorpay.class);fake.loseResponse=false;fake.authorizeOnly=false;fake.beforeReturn=null;
  tenant=UUID.randomUUID().toString();customer="alice";
  UUID sub=(UUID)billing.create(tenant,customer,"create","starter-v1",1,0,"UTC").get("id");UUID job=db.queryForObject("select id from jobs where subscription=?",UUID.class,sub);billing.bill(job);invoice=db.queryForObject("select id from invoices where subscription=?",UUID.class,sub);
 }
 byte[] body(String order,String pay,String type,long amount,String currency){return ("{\"account_id\":\"acc_fixture\",\"event\":\""+type+"\",\"payload\":{\"payment\":{\"entity\":{\"id\":\""+pay+"\",\"order_id\":\""+order+"\",\"amount\":"+amount+",\"currency\":\""+currency+"\",\"status\":\""+(type.equals("payment.captured")?"captured":type.equals("payment.failed")?"failed":"authorized")+"\",\"captured\":"+type.equals("payment.captured")+",\"method\":\"card\",\"card\":{\"last4\":\"1234\"},\"email\":\"do-not-store@example.test\"}}}}").getBytes(StandardCharsets.UTF_8);}
 UUID deliver(String key,byte[] body){return hooks.receive(key,sign(body,WEBHOOK_SECRET),body);}
 String pay(Map<String,Object> c){return "pay_"+((String)c.get("order_id")).substring(6);}
 @Test void concurrentSetupAndDuplicateOutOfOrderEventsApplyCaptureOnce() throws Exception {
  int before=fake.posts.get();var pool=Executors.newFixedThreadPool(3);Map<String,Object> c;
  try{var calls=List.<Callable<Map<String,Object>>>of(()->service.setup(tenant,customer,invoice,"key"),()->service.setup(tenant,customer,invoice,"key"),()->service.setup(tenant,customer,invoice,"key"));var results=pool.invokeAll(calls);c=results.getFirst().get();for(var r:results)assertEquals(c.get("id"),r.get().get("id"));}finally{pool.shutdownNow();}
  c=service.view(tenant,customer,(UUID)c.get("id"));assertEquals(before+1,fake.posts.get());
  String order=(String)c.get("order_id"),pay=pay(c);UUID id=(UUID)c.get("id");byte[] capture=body(order,pay,"payment.captured",49900,"INR");
  UUID event=deliver("event-1-"+id,capture);assertEquals(event,deliver("event-1-"+id,capture));assertEquals(event,deliver("different-header-"+id,capture));
  hooks.process(event);hooks.process(event);
  hooks.process(deliver("authorized-"+id,body(order,pay,"payment.authorized",49900,"INR")));hooks.process(deliver("failed-"+id,body(order,pay,"payment.failed",49900,"INR")));
  assertEquals("PAID",db.queryForObject("select status from invoices where id=?",String.class,invoice));assertEquals(1,db.queryForObject("select count(*) from journals where source=?",Integer.class,"payment:"+id));
  assertEquals(1,db.queryForObject("select count(*) from payment_method_references where tenant=?",Integer.class,tenant));
  assertThrows(Exception.class,()->billing.refund(tenant,id,"fake-refund",1));
  assertThrows(Exception.class,()->billing.retryPayment(tenant,id,"fake-retry"));assertThrows(Exception.class,()->billing.updateSimulatorMethod(tenant,id,"fake-method"));
  UUID method=db.queryForObject("select id from payment_method_references where tenant=?",UUID.class,tenant);assertThrows(Exception.class,()->store.archive("other",customer,method,"cross"));store.archive(tenant,customer,method,"archive");store.archive(tenant,customer,method,"archive");
  assertTrue(db.queryForObject("select archived from payment_method_references where id=?",Boolean.class,method));
 }
 @Test void failedAndAuthorizedEventsCannotMarkInvoicePaidAndCallbacksRequireServerEvidence(){
  var c=service.setup(tenant,customer,invoice,"setup");UUID id=(UUID)c.get("id");String order=(String)c.get("order_id"),pay=pay(c);
  hooks.process(deliver("fail-"+id,body(order,pay,"payment.failed",49900,"INR")));assertEquals("OPEN",db.queryForObject("select status from invoices where id=?",String.class,invoice));
  assertThrows(Exception.class,()->service.confirm(tenant,customer,id,pay,"bad"));assertThrows(Exception.class,()->service.confirm("other",customer,id,pay,"bad"));
  String signature=sign((order+"|"+pay).getBytes(StandardCharsets.UTF_8),API_SECRET);fake.authorizeOnly=true;assertEquals("authorized",service.confirm(tenant,customer,id,pay,signature).get("last_status"));assertEquals("OPEN",db.queryForObject("select status from invoices where id=?",String.class,invoice));
  fake.authorizeOnly=false;service.confirm(tenant,customer,id,pay,signature);service.confirm(tenant,customer,id,pay,signature);assertEquals("PAID",db.queryForObject("select status from invoices where id=?",String.class,invoice));
 }
 @Test void webhookBeforeOrderResponseIsDurableAndLaterMapped(){
  var seen=new java.util.concurrent.atomic.AtomicReference<UUID>();fake.beforeReturn=o->{var event=deliver("early-"+o.id(),body(o.id(),"pay_"+o.id().substring(6),"payment.captured",o.amount(),o.currency()));hooks.process(event);seen.set(event);};
  var c=service.setup(tenant,customer,invoice,"setup");hooks.process(seen.get());assertEquals("PAID",service.view(tenant,customer,(UUID)c.get("id")).get("state"));
 }
 @Test void lostOrderResponseCannotBeRepostedAndCanBeRecoveredByVerifiedLookup(){
  int before=fake.posts.get();fake.loseResponse=true;assertThrows(Exception.class,()->service.setup(tenant,customer,invoice,"setup"));
  var c=service.setup(tenant,customer,invoice,"setup");assertEquals("UNKNOWN",c.get("state"));assertEquals(before+1,fake.posts.get());UUID id=(UUID)c.get("id");assertEquals(0,billing.reconcile(tenant));
  String order="order_"+id.toString().replace("-","");service.recoverOrder(tenant,customer,id,order);
  hooks.process(deliver("recovery-"+id,body(order,"pay_"+order.substring(6),"payment.captured",49900,"INR")));assertEquals("PAID",service.view(tenant,customer,id).get("state"));
 }
 @Test void invalidSignaturesAmountsAccountsAndEventConflictsDoNotAllocate(){
  var c=service.setup(tenant,customer,invoice,"setup");UUID id=(UUID)c.get("id");String order=(String)c.get("order_id"),pay=pay(c);byte[] raw=body(order,pay,"payment.captured",50000,"INR");
  assertThrows(Exception.class,()->hooks.receive("bad","invalid",raw));byte[] wrongAccount=new String(raw,StandardCharsets.UTF_8).replace("acc_fixture","acc_other").getBytes(StandardCharsets.UTF_8);assertThrows(Exception.class,()->deliver("other",wrongAccount));
  UUID event=deliver("mismatch-"+id,raw);hooks.process(event);assertEquals("REVIEW",db.queryForObject("select status from razorpay_events where id=?",String.class,event));assertEquals("OPEN",db.queryForObject("select status from invoices where id=?",String.class,invoice));
  assertThrows(Exception.class,()->deliver("mismatch-"+id,body(order,pay,"payment.captured",49900,"INR")));
  byte[] valid=body(order,pay,"payment.captured",49900,"INR");assertThrows(Exception.class,()->hooks.receive("altered",sign(valid,WEBHOOK_SECRET),new String(valid,StandardCharsets.UTF_8).replace("49900","59900").getBytes()));
 }

 @Test void captureAndOrderPaidEventsCanRaceWithoutDowngradeOrDoubleAllocation() throws Exception {
  var c=service.setup(tenant,customer,invoice,"setup");String order=(String)c.get("order_id"),pay=pay(c);UUID root=(UUID)c.get("id");
  byte[] raw=body(order,pay,"payment.captured",49900,"INR");UUID capture=deliver("capture-race-"+root,raw);
  UUID paid=deliver("paid-race-"+root,new String(raw,StandardCharsets.UTF_8).replace("payment.captured","order.paid").getBytes(StandardCharsets.UTF_8));
  var pool=Executors.newFixedThreadPool(2);
  try{var results=pool.invokeAll(List.<Callable<Boolean>>of(()->{hooks.process(capture);return true;},()->{hooks.process(paid);return true;}));for(var result:results)assertTrue(result.get());}finally{pool.shutdownNow();}
  assertEquals("PROCESSED",db.queryForObject("select status from razorpay_events where id=?",String.class,capture));assertEquals("PROCESSED",db.queryForObject("select status from razorpay_events where id=?",String.class,paid));assertEquals(1,db.queryForObject("select count(*) from journals where source=?",Integer.class,"payment:"+root));
 }
 @Test void publicWebhookEndpointRequiresSignatureAndCustomerEndpointsRemainProtected() throws Exception {
  String base="http://localhost:"+app.getEnvironment().getProperty("local.server.port")+"/api/v1/";var client=java.net.http.HttpClient.newHttpClient();
  var c=service.setup(tenant,customer,invoice,"setup");byte[] raw=body((String)c.get("order_id"),pay(c),"payment.captured",49900,"INR");
  var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"webhooks/payments/razorpay")).header("X-Razorpay-Event-Id","public-"+c.get("id")).header("X-Razorpay-Signature",sign(raw,WEBHOOK_SECRET)).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(raw)).build();
  assertEquals(200,client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
  var bad=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"webhooks/payments/razorpay")).header("X-Razorpay-Event-Id","bad").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(raw)).build();assertEquals(401,client.send(bad,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
  var oversized=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"webhooks/payments/razorpay")).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[65537])).build();assertEquals(413,client.send(oversized,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
  var get=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"customers/me/payment-methods")).GET().build();assertEquals(401,client.send(get,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
  String auth="Basic "+Base64.getEncoder().encodeToString("customer:local-only".getBytes(StandardCharsets.UTF_8));var cross=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"payments/checkouts/"+c.get("id"))).header("Authorization",auth).GET().build();assertEquals(404,client.send(cross,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
 }
}
