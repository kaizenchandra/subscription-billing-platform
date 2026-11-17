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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
@Testcontainers
class DunningTest {
 @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
 JdbcTemplate db;BillingService service;TransactionTemplate tx;TestClock clock;String tenant;UUID subscription,invoice;
 static class TestClock extends Clock {
  Instant now=Instant.parse("2030-01-01T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
 }
 @BeforeEach void setup(TestInfo info){
  var ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());db=new JdbcTemplate(ds);Flyway.configure().dataSource(ds).load().migrate();tx=new TransactionTemplate(new JdbcTransactionManager(ds));clock=new TestClock();
  service=new BillingService(db,clock,new RetryPolicy(3,Duration.ofSeconds(10),Duration.ofSeconds(100),Duration.ofSeconds(200)));
  tenant=info.getTestMethod().orElseThrow().getName().equals("apiEnforcesFinanceRoleAndCrossTenantRecoveryOwnership")?"demo":UUID.randomUUID().toString();subscription=(UUID)tx.execute(t->service.create(tenant,tenant.equals("demo")?"demo":"alice","checkout","starter-v1",1,0,"UTC")).get("id");
  UUID job=db.queryForObject("select id from jobs where subscription=?",UUID.class,subscription);tx.executeWithoutResult(t->service.bill(job));invoice=db.queryForObject("select id from invoices where subscription=?",UUID.class,subscription);
 }
 UUID fail(String scenario){UUID p=(UUID)tx.execute(t->service.collect(tenant,invoice,"collect",scenario)).get("id");tx.executeWithoutResult(t->service.simulate(p));return p;}
 Map<String,Object> p(UUID id){return db.queryForMap("select * from payments where id=?",id);}
 Map<String,Object> d(UUID id){return db.queryForMap("select * from dunning_cases where payment=?",id);}
 void due(UUID id){clock.now=BillingService.instant(p(id).get("next_retry_at"));}
 @Test void boundedRetriesAndDeduplicatedReminders(){
  UUID id=fail("SOFT_DECLINE");assertEquals("OPEN",d(id).get("status"));
  assertThrows(Exception.class,()->tx.execute(t->service.retryPayment(tenant,id,"early")));
  for(int n=2;n<=3;n++){due(id);int attempt=n;tx.execute(t->service.automaticRetry(tenant,id,"auto-"+attempt));tx.executeWithoutResult(t->service.simulate(id));}
  assertEquals(3,BillingService.num(p(id),"attempt_count"));assertEquals("EXHAUSTED",d(id).get("status"));assertNull(p(id).get("next_retry_at"));
  assertThrows(Exception.class,()->tx.execute(t->service.retryPayment(tenant,id,"excess")));
  clock.now=Instant.parse("2030-01-01T00:02:31Z");tx.executeWithoutResult(t->service.advanceDunning(id));tx.executeWithoutResult(t->service.advanceDunning(id));
  assertEquals(3,db.queryForObject("select count(*) from billing_notifications where payment=?",Integer.class,id));
  clock.now=Instant.parse("2030-01-01T00:03:20Z");tx.executeWithoutResult(t->service.advanceDunning(id));tx.executeWithoutResult(t->service.advanceDunning(id));assertEquals("RESTRICTED",d(id).get("status"));assertEquals(4,db.queryForObject("select count(*) from billing_notifications where payment=?",Integer.class,id));
 }
 @Test void concurrentRecoveryChargesOnceAndKeepsImmutableAttemptHistory() throws Exception {
  UUID id=fail("SOFT_DECLINE_THEN_SUCCESS");due(id);var pool=Executors.newFixedThreadPool(3);
  try{var calls=new ArrayList<Callable<Map<String,Object>>>();for(int n=0;n<3;n++)calls.add(()->tx.execute(t->service.retryPayment(tenant,id,"same-retry")));var results=pool.invokeAll(calls);for(var r:results)assertEquals(results.getFirst().get().get("id"),r.get().get("id"));}finally{pool.shutdownNow();}
  assertEquals(2,BillingService.num(p(id),"attempt_count"));tx.executeWithoutResult(t->service.simulate(id));tx.executeWithoutResult(t->service.simulate(id));tx.executeWithoutResult(t->service.advanceDunning(id));
  assertEquals("SUCCEEDED",p(id).get("state"));assertEquals("RECOVERED",d(id).get("status"));assertEquals("PAID",db.queryForObject("select status from invoices where id=?",String.class,invoice));
  assertEquals(1,db.queryForObject("select count(*) from journals where source=?",Integer.class,"payment:"+id));
  assertEquals(1,db.queryForObject("select count(*) from provider_payments where id in (select id from payment_attempts where payment=?) and state='SUCCEEDED'",Integer.class,id));
  assertThrows(Exception.class,()->db.update("update payment_attempts set state='PENDING' where payment=? and number=1",id));
 }
 @Test void unknownAndHardDeclineNeverBlindlyRetry(){
  UUID id=fail("ACCEPTED_RESPONSE_LOST");assertThrows(Exception.class,()->tx.execute(t->service.retryPayment(tenant,id,"unknown")));assertThrows(Exception.class,()->tx.execute(t->service.updateSimulatorMethod(tenant,id,"unknown-method")));
  assertEquals(1,tx.execute(t->service.reconcile(tenant)).intValue());assertEquals(0,tx.execute(t->service.reconcile(tenant)).intValue());assertEquals("RECOVERED",d(id).get("status"));
 }
 @Test void hardDeclineNeedsMethodRecoveryAndRespectsBudgetAndTenant(){
  UUID id=fail("HARD_DECLINE");assertEquals("ACTION_REQUIRED",d(id).get("status"));assertNull(p(id).get("next_retry_at"));
  assertThrows(Exception.class,()->tx.execute(t->service.retryPayment(tenant,id,"hard")));
  assertThrows(Exception.class,()->tx.execute(t->service.updateSimulatorMethod("other",id,"cross")));
  tx.execute(t->service.updateSimulatorMethod(tenant,id,"method"));db.update("delete from outbox where aggregate_id in (select id from simulator_method_updates where payment=?)",id);tx.execute(t->service.updateSimulatorMethod(tenant,id,"method"));assertEquals(1,BillingService.num(p(id),"method_version"));
  assertThrows(Exception.class,()->tx.execute(t->service.automaticRetry(tenant,id,"no-auto")));
  tx.execute(t->service.retryPayment(tenant,id,"manual"));tx.executeWithoutResult(t->service.simulate(id));assertEquals("SUCCEEDED",p(id).get("state"));
 }
 @Test void retryWindowCancellationAndRateLimitAreEnforced(){
  UUID id=fail("RATE_LIMIT");assertEquals("EXHAUSTED",d(id).get("status")); // Retry-After (24h) exceeds this 100s policy window.
  tx.execute(t->service.updateSimulatorMethod(tenant,id,"replace"));
  // No retry can bypass a provider limit, including with a new method.
  assertThrows(Exception.class,()->tx.execute(t->service.retryPayment(tenant,id,"rate-limited")));
  clock.now=clock.now.plusSeconds(101);assertThrows(Exception.class,()->tx.execute(t->service.retryPayment(tenant,id,"expired")));
  tx.execute(t->service.change(tenant,subscription,"cancel","cancel"));tx.executeWithoutResult(t->service.advanceDunning(id));assertEquals("STOPPED",d(id).get("status"));
 }

 @Test void successRacingReminderClosesCaseWithoutDuplicateEffects() throws Exception {
  UUID id=fail("SOFT_DECLINE_THEN_SUCCESS");due(id);tx.execute(t->service.retryPayment(tenant,id,"retry"));clock.now=Instant.parse("2030-01-01T00:02:31Z");
  var pool=Executors.newFixedThreadPool(2);
  try{var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();tx.executeWithoutResult(t->service.simulate(id));return true;});var b=pool.submit(()->{gate.await();tx.executeWithoutResult(t->service.advanceDunning(id));return true;});gate.countDown();assertTrue(a.get());assertTrue(b.get());}finally{pool.shutdownNow();}
  assertEquals("RECOVERED",d(id).get("status"));assertEquals("SUCCEEDED",p(id).get("state"));
  int notices=db.queryForObject("select count(*) from billing_notifications where payment=?",Integer.class,id);tx.executeWithoutResult(t->service.advanceDunning(id));assertEquals(notices,db.queryForObject("select count(*) from billing_notifications where payment=?",Integer.class,id));
  assertEquals(1,db.queryForObject("select count(*) from journals where source=?",Integer.class,"payment:"+id));
 }
 @Test void renewalRecoveryDoesNotClearOtherOutstandingFailures(){
  UUID first=fail("HARD_DECLINE");
  UUID otherInvoice=UUID.randomUUID();
  db.update("insert into invoices(id,tenant,subscription,cycle,currency,total,period_start,period_end,status) values (?,?,?,99,'INR',49900,now(),now()+interval '1 month','OPEN')",otherInvoice,tenant,subscription);
  UUID second=(UUID)tx.execute(t->service.collect(tenant,otherInvoice,"other","HARD_DECLINE")).get("id");tx.executeWithoutResult(t->service.simulate(second));
  tx.execute(t->service.updateSimulatorMethod(tenant,first,"replace"));tx.execute(t->service.retryPayment(tenant,first,"recover"));tx.executeWithoutResult(t->service.simulate(first));
  assertEquals("PAST_DUE",db.queryForObject("select state from subscriptions where id=?",String.class,subscription));
  tx.execute(t->service.updateSimulatorMethod(tenant,second,"replace"));tx.execute(t->service.retryPayment(tenant,second,"recover"));tx.executeWithoutResult(t->service.simulate(second));
  assertEquals("ACTIVE",db.queryForObject("select state from subscriptions where id=?",String.class,subscription));
 }
 @Test void apiEnforcesFinanceRoleAndCrossTenantRecoveryOwnership() throws Exception {
  // Use the real customer principal here; fake clock means retry jobs cannot run early in this application test.
  UUID id=fail("HARD_DECLINE");
  try(var app=new org.springframework.boot.builder.SpringApplicationBuilder(Application.class).run("--server.port=0","--billing.demo=true","--billing.auto-collect=false","--spring.datasource.url="+pg.getJdbcUrl(),"--spring.datasource.username="+pg.getUsername(),"--spring.datasource.password="+pg.getPassword())){
   String base="http://localhost:"+app.getEnvironment().getProperty("local.server.port")+"/api/v1/";var client=java.net.http.HttpClient.newHttpClient();
   java.util.function.BiFunction<String,String,java.net.http.HttpRequest.Builder> req=(path,user)->java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+path)).header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":local-only").getBytes()));
   var finance=client.send(req.apply("admin/dunning","demo").GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,finance.statusCode());assertTrue(finance.body().contains("recovery_authorized"));
   assertEquals(200,client.send(req.apply("admin/payment-attempts","demo").GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
   assertEquals(403,client.send(req.apply("admin/dunning","customer").GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
   assertEquals(404,client.send(req.apply("payments/"+id+"/retry","customer").header("Idempotency-Key","cross").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
   var access=client.send(req.apply("customers/me/entitlements","demo").GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,access.statusCode());assertTrue(access.body().contains("\"access\":false"),access.body());
   UUID previous=UUID.randomUUID();db.update("insert into invoices(id,tenant,subscription,cycle,currency,total,paid,period_start,period_end,status) values (?,'demo',?,-1,'INR',49900,49900,now()-interval '1 month',now(),'PAID')",previous,subscription);
   access=client.send(req.apply("customers/me/entitlements","demo").GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertTrue(access.body().contains("\"access\":true"),access.body());
   db.update("update dunning_cases set grace_until=now()-interval '1 second' where payment=?",id);
   access=client.send(req.apply("customers/me/entitlements","demo").GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertTrue(access.body().contains("\"access\":false"),access.body());
  }
 }

 @Test void realWorkerAutomaticallyRecoversSoftDecline() throws Exception {
  try(var app=new org.springframework.boot.builder.SpringApplicationBuilder(Application.class).run("--server.port=0","--billing.demo=true","--billing.auto-collect=false","--billing.retry.base-seconds=1","--billing.retry.window-seconds=30","--billing.retry.grace-seconds=60","--spring.datasource.url="+pg.getJdbcUrl(),"--spring.datasource.username="+pg.getUsername(),"--spring.datasource.password="+pg.getPassword())){
   var live=app.getBean(BillingService.class);String account=UUID.randomUUID().toString();UUID sid=(UUID)live.create(account,"alice","live","starter-v1",1,0,"UTC").get("id");
   UUID job=db.queryForObject("select id from jobs where subscription=?",UUID.class,sid);live.bill(job);UUID inv=db.queryForObject("select id from invoices where subscription=?",UUID.class,sid);
   UUID pay=(UUID)live.collect(account,inv,"soft","SOFT_DECLINE_THEN_SUCCESS").get("id");
   long deadline=System.nanoTime()+Duration.ofSeconds(12).toNanos();while(System.nanoTime()<deadline&&!p(pay).get("state").equals("SUCCEEDED"))Thread.sleep(100);
   assertEquals("SUCCEEDED",p(pay).get("state"));assertEquals(2,BillingService.num(p(pay),"attempt_count"));assertEquals("RECOVERED",d(pay).get("status"));
  }
 }

 @Test void migrationPreservesLegacyMethodRecoveryKeysWithoutOutbox() throws Exception {
  String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");db.execute("create schema "+schema);
  try {
   String url=pg.getJdbcUrl()+(pg.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema;
   var ds=new DriverManagerDataSource(url,pg.getUsername(),pg.getPassword());var legacy=new JdbcTemplate(ds);
   Flyway.configure().dataSource(ds).defaultSchema(schema).schemas(schema).target("7").load().migrate();
   UUID sub=UUID.randomUUID(),inv=UUID.randomUUID(),pay=UUID.randomUUID(),op=UUID.randomUUID();
   legacy.update("insert into subscriptions(id,tenant,customer,plan,currency,amount,months,quantity,state,anchor,zone,period_start,period_end) values (?,'legacy','alice','starter-v1','INR',49900,1,1,'PAST_DUE',now(),'UTC',now(),now()+interval '1 month')",sub);
   legacy.update("insert into invoices(id,tenant,subscription,cycle,currency,total,period_start,period_end,status) values (?,'legacy',?,0,'INR',49900,now(),now()+interval '1 month','OPEN')",inv,sub);
   legacy.update("insert into payments(id,tenant,invoice,amount,state,method_version,recovery_authorized) values (?,'legacy',?,49900,'DECLINED',1,true)",pay,inv);
   String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("approved-simulator-method".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   legacy.update("insert into operations values ('legacy',?,'old-key',?,?)","SIMULATOR_METHOD:"+pay,hash,op);
   Flyway.configure().dataSource(ds).defaultSchema(schema).schemas(schema).load().migrate();
   var upgraded=new BillingService(legacy);var upgradedTx=new TransactionTemplate(new JdbcTransactionManager(ds));
   upgradedTx.execute(t->upgraded.updateSimulatorMethod("legacy",pay,"old-key"));
   assertEquals(1,legacy.queryForObject("select method_version from payments where id=?",Integer.class,pay));assertEquals(1,legacy.queryForObject("select count(*) from simulator_method_updates",Integer.class));
  } finally {db.execute("drop schema "+schema+" cascade");}
 }

 @Test void resumeCannotBypassFailureObservedWhilePaused(){
  UUID initial=fail("SUCCESS");assertEquals("SUCCEEDED",p(initial).get("state"));
  UUID renewal=UUID.randomUUID();db.update("insert into invoices(id,tenant,subscription,cycle,currency,total,period_start,period_end,status) values (?,?,?,10,'INR',49900,now(),now()+interval '1 month','OPEN')",renewal,tenant,subscription);
  UUID next=(UUID)tx.execute(t->service.collect(tenant,renewal,"pending","HARD_DECLINE")).get("id");
  tx.execute(t->service.change(tenant,subscription,"pause","pause"));tx.executeWithoutResult(t->service.simulate(next));assertEquals("PAUSED",db.queryForObject("select state from subscriptions where id=?",String.class,subscription));
  tx.execute(t->service.change(tenant,subscription,"resume","resume"));assertEquals("PAST_DUE",db.queryForObject("select state from subscriptions where id=?",String.class,subscription));
 }
}
