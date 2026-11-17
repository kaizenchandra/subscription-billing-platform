package com.acme.billing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.flywaydb.core.Flyway;
import java.util.*;
import java.util.concurrent.*;
@Testcontainers
class PostgresTest {
 @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
 JdbcTemplate db;BillingService service;TransactionTemplate tx;
 @BeforeEach void setup(){var ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());db=new JdbcTemplate(ds);Flyway.configure().dataSource(ds).load().migrate();service=new BillingService(db);tx=new TransactionTemplate(new JdbcTransactionManager(ds));}
 @Test void concurrentCheckoutRenewalAndRefund() throws Exception {
  var executor=Executors.newFixedThreadPool(3);
  try{
   var calls=new ArrayList<Callable<Map<String,Object>>>();for(int n=0;n<3;n++)calls.add(()->tx.execute(t->service.create("tenant-a","alice","same","starter-v1",1,0,"UTC")));
   var results=executor.invokeAll(calls);UUID id=(UUID)results.getFirst().get().get("id");for(var result:results)assertEquals(id,result.get().get("id"));
   UUID job=db.queryForObject("select id from jobs where subscription=?",UUID.class,id);
   var renewals=new ArrayList<Callable<Void>>();for(int n=0;n<3;n++)renewals.add(()->{tx.executeWithoutResult(t->service.bill(job));return null;});for(var f:executor.invokeAll(renewals))f.get();
   assertEquals(1,db.queryForObject("select count(*) from invoices where subscription=?",Integer.class,id));
   UUID invoice=db.queryForObject("select id from invoices where subscription=?",UUID.class,id);
   var payment=tx.execute(t->service.collect("tenant-a",invoice,"charge","ACCEPTED_RESPONSE_LOST"));UUID pid=(UUID)payment.get("id");tx.executeWithoutResult(t->service.simulate(pid));
   assertEquals("UNKNOWN",db.queryForObject("select state from payments where id=?",String.class,pid));
   assertEquals(1,tx.execute(t->service.reconcile("tenant-a")).intValue());assertEquals(0,tx.execute(t->service.reconcile("tenant-a")).intValue());
   assertThrows(Exception.class,()->tx.execute(t->service.refund("tenant-b",pid,"x",1)));
   tx.execute(t->service.refund("tenant-a",pid,"refund",30000));
   assertThrows(Exception.class,()->tx.execute(t->service.refund("tenant-a",pid,"excess",30000)));
   assertEquals(30000,db.queryForObject("select refunded from payments where id=?",Long.class,pid));
   assertThrows(Exception.class,()->db.update("update invoices set total=1 where id=?",invoice));
   assertThrows(Exception.class,()->db.update("delete from journals where source=?","payment:"+pid));
   assertThrows(Exception.class,()->tx.execute(t->service.create("tenant-a","alice","same","professional-v1",1,0,"UTC")));
  }finally{executor.shutdownNow();}
 }
 @Test void realSpringApplicationAndScheduler() throws Exception {
  try(var app=new org.springframework.boot.builder.SpringApplicationBuilder(Application.class).run("--server.port=0","--billing.demo=true","--billing.auto-collect=true","--spring.datasource.url="+pg.getJdbcUrl(),"--spring.datasource.username="+pg.getUsername(),"--spring.datasource.password="+pg.getPassword())){
   String base="http://localhost:"+app.getEnvironment().getProperty("local.server.port")+"/api/v1/";
   var client=java.net.http.HttpClient.newHttpClient();
   String auth="Basic "+Base64.getEncoder().encodeToString("demo:local-only".getBytes());
   var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"subscriptions")).header("Authorization",auth).header("Content-Type","application/json").header("Idempotency-Key","spring-checkout").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"plan\":\"starter-v1\",\"quantity\":1,\"trialDays\":0,\"zone\":\"UTC\"}")).build();
   var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());
   long deadline=System.nanoTime()+java.time.Duration.ofSeconds(12).toNanos();
   while(System.nanoTime()<deadline && db.queryForObject("select count(*) from invoices where tenant='demo' and status='PAID'",Integer.class)==0)Thread.sleep(100);
   assertEquals(1,db.queryForObject("select count(*) from invoices where tenant='demo' and status='PAID'",Integer.class));
   var get=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"plans")).header("Authorization",auth).GET().build();assertEquals(200,client.send(get,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
  }
 }

 UUID paidSubscription(String tenant) {
  UUID id=UUID.randomUUID();
  db.update("insert into subscriptions(id,tenant,customer,plan,currency,amount,months,quantity,state,anchor,zone,period_start,period_end) values (?,?,?,'starter-v1','INR',49900,1,1,'INCOMPLETE','2026-01-01','UTC','2026-01-01','2026-01-01')",id,tenant,"alice");
  UUID job=UUID.randomUUID();db.update("insert into jobs(id,subscription,due,expected_cycle) values (?,?,'2026-01-01',0)",job,id);
  tx.executeWithoutResult(t->service.bill(job));
  UUID invoice=db.queryForObject("select id from invoices where subscription=?",UUID.class,id);
  UUID payment=(UUID)tx.execute(t->service.collect(tenant,invoice,"initial","SUCCESS")).get("id");
  tx.executeWithoutResult(t->service.simulate(payment));return id;
 }
 PlanChanges changes(java.time.Instant now){return new PlanChanges(service,db,java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));}
 @Test void exactHalfPeriodUpgradeIsIdempotentAndImmutable() {
  String tenant=UUID.randomUUID().toString();UUID id=paidSubscription(tenant);
  var changes=changes(java.time.Instant.parse("2026-01-16T12:00:00Z"));
  var quote=tx.execute(t->changes.preview(tenant,id,"professional-v1",1,"NOW"));
  assertEquals(24950L,BillingService.num(quote,"old_credit"));assertEquals(74950L,BillingService.num(quote,"new_charge"));assertEquals(50000L,BillingService.num(quote,"net"));
  var result=tx.execute(t->changes.apply(tenant,id,"upgrade",(UUID)quote.get("id")));
  assertEquals(result,tx.execute(t->changes.apply(tenant,id,"upgrade",(UUID)quote.get("id"))));
  UUID adjustment=(UUID)result.get("invoice");
  assertEquals(50000L,db.queryForObject("select sum(amount) from invoice_lines where invoice=?",Long.class,adjustment));
  assertEquals(49900L,db.queryForObject("select total from invoices where subscription=? and cycle=0",Long.class,id));
  assertThrows(Exception.class,()->db.update("update invoice_lines set amount=0 where invoice=?",adjustment));
  assertThrows(Exception.class,()->tx.execute(t->changes.preview(tenant,id,"professional-v1",2,"NOW")));
  var payment=tx.execute(t->service.collect(tenant,adjustment,"pay-upgrade","SUCCESS"));tx.executeWithoutResult(t->service.simulate((UUID)payment.get("id")));
  var second=tx.execute(t->changes.preview(tenant,id,"professional-v1",2,"NOW"));
  assertEquals(74950L,BillingService.num(second,"net"));
  assertThrows(Exception.class,()->tx.execute(t->changes.preview(tenant,id,"starter-v1",1,"NOW")));
  assertThrows(Exception.class,()->tx.execute(t->changes.preview(tenant,id,"annual-v1",1,"RENEWAL")));
 }
 @Test void quoteExpiryConcurrencyAndTenantIsolation() throws Exception {
  String tenant=UUID.randomUUID().toString();UUID id=paidSubscription(tenant);
  var at=java.time.Instant.parse("2026-01-16T12:00:00Z");var changes=changes(at);
  var quote=tx.execute(t->changes.preview(tenant,id,"professional-v1",1,"NOW"));UUID qid=(UUID)quote.get("id");
  assertThrows(Exception.class,()->tx.execute(t->changes(at.plusSeconds(300)).apply(tenant,id,"expired",qid)));
  assertThrows(Exception.class,()->tx.execute(t->changes.apply("other-tenant",id,"cross",qid)));
  var executor=Executors.newFixedThreadPool(2);
  try {var results=executor.invokeAll(List.<Callable<Map<String,Object>>>of(()->tx.execute(t->changes.apply(tenant,id,"same",qid)),()->tx.execute(t->changes.apply(tenant,id,"same",qid))));assertEquals(results.get(0).get(),results.get(1).get());}
  finally{executor.shutdownNow();}
  assertEquals(1,db.queryForObject("select count(*) from invoices where subscription=? and kind='PRORATION'",Integer.class,id));
  assertThrows(Exception.class,()->tx.execute(t->changes.apply(tenant,id,"other-key",qid)));
 }
 @Test void scheduledQuantityChangeAppliesOnceAtRenewalAndCancellationInvalidatesQuote() {
  String tenant=UUID.randomUUID().toString();UUID id=paidSubscription(tenant);var changes=changes(java.time.Instant.parse("2026-01-16T12:00:00Z"));
  var quote=tx.execute(t->changes.preview(tenant,id,"starter-v1",2,"RENEWAL"));
  var scheduled=tx.execute(t->changes.apply(tenant,id,"schedule",(UUID)quote.get("id")));
  assertEquals("SCHEDULED",scheduled.get("status"));assertEquals(1,db.queryForObject("select quantity from subscriptions where id=?",Integer.class,id));
  UUID job=db.queryForObject("select id from jobs where subscription=? and status='READY'",UUID.class,id);
  tx.executeWithoutResult(t->service.bill(job));tx.executeWithoutResult(t->service.bill(job));
  assertEquals(99800L,db.queryForObject("select total from invoices where subscription=? and cycle=1",Long.class,id));
  assertEquals("APPLIED",db.queryForObject("select status from subscription_changes where id=?",String.class,scheduled.get("id")));
  UUID other=paidSubscription(tenant+"-cancel");
  var q=tx.execute(t->changes.preview(tenant+"-cancel",other,"professional-v1",1,"NOW"));
  tx.execute(t->service.change(tenant+"-cancel",other,"cancel","cancel"));
  assertThrows(Exception.class,()->tx.execute(t->changes.apply(tenant+"-cancel",other,"stale",(UUID)q.get("id"))));
 }

 @Test void duplicateJobIdentityDoesNotBillAnotherPeriod() {
  String tenant=UUID.randomUUID().toString();UUID id=paidSubscription(tenant);
  UUID duplicate=UUID.randomUUID();
  db.update("insert into jobs(id,subscription,due,expected_cycle) values (?,?,'2026-01-01',0)",duplicate,id);
  tx.executeWithoutResult(t->service.bill(duplicate));
  assertEquals(1,db.queryForObject("select count(*) from invoices where subscription=?",Integer.class,id));
  assertEquals(1L,db.queryForObject("select cycle from subscriptions where id=?",Long.class,id));
 }

 @Test void upgradeRacingRenewalSerializesFinancialEffects() throws Exception {
  String tenant=UUID.randomUUID().toString();UUID id=paidSubscription(tenant);var changes=changes(java.time.Instant.parse("2026-01-16T12:00:00Z"));
  var quote=tx.execute(t->changes.preview(tenant,id,"professional-v1",1,"NOW"));
  UUID job=db.queryForObject("select id from jobs where subscription=? and status='READY'",UUID.class,id);
  var executor=Executors.newFixedThreadPool(2);
  try {
   var gate=new CountDownLatch(1);
   var upgrade=executor.submit(()->{gate.await();try{tx.execute(t->changes.apply(tenant,id,"racing",(UUID)quote.get("id")));return true;}catch(org.springframework.web.server.ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});
   var renewal=executor.submit(()->{gate.await();tx.executeWithoutResult(t->service.bill(job));return true;});gate.countDown();
   boolean applied=upgrade.get();assertTrue(renewal.get());
   assertEquals(applied?149900L:49900L,db.queryForObject("select total from invoices where subscription=? and cycle=1",Long.class,id));
   assertEquals(applied?1:0,db.queryForObject("select count(*) from invoices where subscription=? and kind='PRORATION'",Integer.class,id));
   assertEquals(1,db.queryForObject("select count(*) from invoices where subscription=? and cycle=1",Integer.class,id));
  }finally{executor.shutdownNow();}
 }
}
