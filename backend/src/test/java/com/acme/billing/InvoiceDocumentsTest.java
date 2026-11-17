package com.acme.billing;
import com.acme.billing.documents.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
@Testcontainers
class InvoiceDocumentsTest {
 @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");static ConfigurableApplicationContext app;
 JdbcTemplate db;BillingService billing;InvoiceDocuments documents;InvoiceDocumentStore records;MemoryStorage storage;String tenant,actor;UUID invoice;
 @TestConfiguration(proxyBeanMethods=false) static class StorageFixture {
  @Bean @Primary MemoryStorage fixtureStorage(){return new MemoryStorage();}
 }
 static class MemoryStorage implements DocumentStorage {
  final Map<String,byte[]> objects=new ConcurrentHashMap<>();final java.util.concurrent.atomic.AtomicInteger puts=new java.util.concurrent.atomic.AtomicInteger(),gets=new java.util.concurrent.atomic.AtomicInteger();volatile boolean fail=false;
  public void put(String key,byte[] bytes){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());puts.incrementAndGet();if(fail)throw new IllegalStateException("Fixture unavailable");objects.putIfAbsent(key,bytes.clone());}
  public byte[] get(String key){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());gets.incrementAndGet();return objects.get(key).clone();}
 }
 @BeforeAll static void boot(){app=new org.springframework.boot.builder.SpringApplicationBuilder(Application.class,StorageFixture.class).run("--server.port=0","--billing.demo=true","--billing.auto-collect=false","--spring.datasource.url="+pg.getJdbcUrl(),"--spring.datasource.username="+pg.getUsername(),"--spring.datasource.password="+pg.getPassword());}
 @AfterAll static void close(){if(app!=null)app.close();}
 @BeforeEach void setup(TestInfo info){db=app.getBean(JdbcTemplate.class);billing=app.getBean(BillingService.class);documents=app.getBean(InvoiceDocuments.class);records=app.getBean(InvoiceDocumentStore.class);storage=app.getBean(MemoryStorage.class);storage.fail=false;tenant=info.getTestMethod().orElseThrow().getName().equals("customerAndFinanceHttpDownloadsEnforceAuthorization")?"demo":UUID.randomUUID().toString();actor=tenant.equals("demo")?"demo":"alice";
  UUID sub=(UUID)billing.create(tenant,actor,"checkout","starter-v1",1,0,"UTC").get("id");UUID job=db.queryForObject("select id from jobs where subscription=?",UUID.class,sub);billing.bill(job);invoice=db.queryForObject("select id from invoices where subscription=?",UUID.class,sub);
 }
 @Test void immutableArtifactSurvivesPaymentsAndNeverReadsStorageForAnotherTenant() throws Exception {
  var issued=db.queryForObject("select issued_at from invoices where id=?",java.sql.Timestamp.class,invoice);db.update("delete from outbox where aggregate_id=?",invoice);
  var first=documents.download(tenant,actor,invoice,false);assertTrue(first.filename().startsWith("invoice-INV-"));try(var pdf=Loader.loadPDF(first.bytes())){assertTrue(new PDFTextStripper().getText(pdf).contains("INR 499.00"));assertTrue(new PDFTextStripper().getText(pdf).contains(issued.toInstant().toString()));}
  int reads=storage.gets.get();assertThrows(Exception.class,()->documents.download("another",actor,invoice,false));assertThrows(Exception.class,()->documents.download(tenant,"other-customer",invoice,false));assertEquals(reads,storage.gets.get());
  UUID pay=(UUID)billing.collect(tenant,invoice,"pay","SUCCESS").get("id");billing.simulate(pay);var second=documents.download(tenant,actor,invoice,false);assertArrayEquals(first.bytes(),second.bytes());assertEquals(first.sha256(),second.sha256());
  assertThrows(Exception.class,()->db.update("update invoices set issued_at=now()+interval '1 day' where id=?",invoice));assertThrows(Exception.class,()->db.update("update invoices set tenant='other' where id=?",invoice));
  assertThrows(Exception.class,()->db.update("update invoice_documents set sha256=? where invoice=?","b".repeat(64),invoice));assertThrows(Exception.class,()->db.update("update invoice_documents set snapshot='{}' where invoice=?",invoice));
 }
 @Test void failuresAndCorruptionNeverModifyInvoiceOrLedger(){
  long journals=db.queryForObject("select count(*) from journals",Long.class);storage.fail=true;assertThrows(Exception.class,()->documents.download(tenant,actor,invoice,false));assertEquals("OPEN",db.queryForObject("select status from invoices where id=?",String.class,invoice));assertEquals(journals,db.queryForObject("select count(*) from journals",Long.class));
  storage.fail=false;db.update("update invoice_documents set next_run=now() where invoice=?",invoice);documents.download(tenant,actor,invoice,false);
  String key=db.queryForObject("select object_key from invoice_documents where invoice=?",String.class,invoice);storage.objects.put(key,"tampered".getBytes());assertThrows(Exception.class,()->documents.download(tenant,actor,invoice,false));
 }
 @Test void concurrentRequestsPublishOneArtifactAndExpiredLeaseIsFenced() throws Exception {
  int before=storage.puts.get();var pool=Executors.newFixedThreadPool(3);
  try{var results=pool.invokeAll(List.<Callable<Boolean>>of(()->downloadOrBusy(),()->downloadOrBusy(),()->downloadOrBusy()));assertTrue(results.stream().anyMatch(r->{try{return r.get();}catch(Exception e){throw new RuntimeException(e);}}));}finally{pool.shutdownNow();}
  assertEquals(before+1,storage.puts.get());assertEquals("READY",records.current(invoice).get("state"));
  // A separate invoice supplies a recoverable abandoned claim without changing published state.
  UUID other=(UUID)billing.create(tenant,actor,"second","starter-v1",1,0,"UTC").get("id");billing.bill(db.queryForObject("select id from jobs where subscription=?",UUID.class,other));UUID inv=db.queryForObject("select id from invoices where subscription=?",UUID.class,other);
  records.prepare(tenant,actor,inv,false);UUID old=records.claim(inv);db.update("update invoice_documents set lease_until=now()-interval '1 second' where invoice=?",inv);UUID fresh=records.claim(inv);assertNotEquals(old,fresh);assertFalse(records.publish(inv,old,"unused","a".repeat(64),100));
 }
 boolean downloadOrBusy(){try{documents.download(tenant,actor,invoice,false);return true;}catch(org.springframework.web.server.ResponseStatusException e){assertEquals(503,e.getStatusCode().value());return false;}}
 @Test void customerAndFinanceHttpDownloadsEnforceAuthorization() throws Exception {
  String base="http://localhost:"+app.getEnvironment().getProperty("local.server.port")+"/api/v1/";var client=java.net.http.HttpClient.newHttpClient();
  java.util.function.BiFunction<String,String,java.net.http.HttpRequest> req=(path,user)->java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+path)).header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":local-only").getBytes())).GET().build();
  var pdf=client.send(req.apply("invoices/"+invoice+"/pdf","demo"),java.net.http.HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,pdf.statusCode());assertTrue(pdf.headers().firstValue("Content-Type").orElse("").startsWith("application/pdf"));assertTrue(pdf.headers().firstValue("Cache-Control").orElse("").contains("no-store"));assertTrue(new String(pdf.body(),0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF"));
  assertEquals(404,client.send(req.apply("invoices/"+invoice+"/pdf","customer"),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(403,client.send(req.apply("admin/invoices/"+invoice+"/pdf","customer"),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(200,client.send(req.apply("admin/invoices/"+invoice+"/pdf","demo"),java.net.http.HttpResponse.BodyHandlers.ofByteArray()).statusCode());
 }
}
