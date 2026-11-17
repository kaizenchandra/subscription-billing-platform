package com.acme.billing.documents;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.net.URI;
import java.time.Duration;
@Testcontainers
class S3DocumentStorageTest {
 @Container static org.testcontainers.containers.PostgreSQLContainer<?> postgres=new org.testcontainers.containers.PostgreSQLContainer<>("postgres:17-alpine");
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) static class CredentialsFixture {
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary AwsCredentialsProvider testCredentials(){return StaticCredentialsProvider.create(AwsBasicCredentials.create("test","test"));}
 }

 @Container static GenericContainer<?> s3=new GenericContainer<>("localstack/localstack:4.10.0").withEnv("SERVICES","s3").withExposedPorts(4566).waitingFor(Wait.forHttp("/_localstack/health").forPort(4566));
 @Test void storesPrivateObjectsAndDoesNotOverwriteExistingArtifact() {
  try(var client=S3Client.builder().endpointOverride(URI.create("http://"+s3.getHost()+":"+s3.getMappedPort(4566))).region(Region.US_EAST_1).forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test","test"))).httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(3))).overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(15))).build()){
   client.createBucket(b->b.bucket("invoice-test"));var storage=new S3DocumentStorage(client,"invoice-test",false);String key="invoices/00000000-0000-0000-0000-000000000001/v1/"+"a".repeat(64)+".pdf";
   storage.put(key,"%PDF-original".getBytes());storage.put(key,"%PDF-replacement".getBytes());assertArrayEquals("%PDF-original".getBytes(),storage.get(key));
   var acl=client.getObjectAcl(b->b.bucket("invoice-test").key(key));assertTrue(acl.grants().stream().noneMatch(g->g.grantee().uri()!=null));
   assertThrows(IllegalArgumentException.class,()->storage.get("../tenant.pdf"));assertThrows(IllegalArgumentException.class,()->storage.put(key,new byte[DocumentStorage.MAX_BYTES+1]));
  }
 }

 @Test void realApplicationDownloadsFromS3WithPrivateAuthenticatedEndpoint() throws Exception {
  String endpoint="http://"+s3.getHost()+":"+s3.getMappedPort(4566);
  try(var client=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test","test"))).build()){
   client.createBucket(b->b.bucket("invoice-app"));
   try(var app=new org.springframework.boot.builder.SpringApplicationBuilder(com.acme.billing.Application.class,CredentialsFixture.class).run("--server.port=0","--billing.demo=true","--billing.auto-collect=false","--billing.documents.storage=s3","--billing.documents.s3.endpoint="+endpoint,"--billing.documents.s3.bucket=invoice-app","--billing.documents.s3.encryption=false","--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword())){
    var billing=app.getBean(com.acme.billing.BillingService.class);var db=app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    java.util.UUID sub=(java.util.UUID)billing.create("demo","demo","s3-checkout","starter-v1",1,0,"UTC").get("id");billing.bill(db.queryForObject("select id from jobs where subscription=?",java.util.UUID.class,sub));java.util.UUID invoice=db.queryForObject("select id from invoices where subscription=?",java.util.UUID.class,sub);
    String base="http://localhost:"+app.getEnvironment().getProperty("local.server.port");
    var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/invoices/"+invoice+"/pdf")).header("Authorization","Basic "+java.util.Base64.getEncoder().encodeToString("demo:local-only".getBytes())).GET().build();
    var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode(),new String(response.body()));
    String key=db.queryForObject("select object_key from invoice_documents where invoice=?",String.class,invoice);assertArrayEquals(response.body(),client.getObjectAsBytes(b->b.bucket("invoice-app").key(key)).asByteArray());
   }
  }
 }
}
