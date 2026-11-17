package com.acme.billing.documents;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.auth.credentials.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
@Configuration
public class DocumentConfiguration {
 @Bean @ConditionalOnProperty(name="billing.documents.storage",havingValue="local",matchIfMissing=true)
 DocumentStorage local(@Value("${billing.documents.local-path:./var/invoice-documents}") String path){return new LocalDocumentStorage(Path.of(path));}
 @Bean(destroyMethod="close") @ConditionalOnProperty(name="billing.documents.storage",havingValue="s3") @ConditionalOnMissingBean(AwsCredentialsProvider.class)
 DefaultCredentialsProvider credentials(){return DefaultCredentialsProvider.create();}
 @Bean(destroyMethod="close") @ConditionalOnProperty(name="billing.documents.storage",havingValue="s3")
 S3Client s3(AwsCredentialsProvider credentials,@Value("${billing.documents.s3.region:us-east-1}") String region,@Value("${billing.documents.s3.endpoint:}") String endpoint){
  var builder=S3Client.builder().credentialsProvider(credentials).region(Region.of(region)).forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(3)))
   .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(4)).retryPolicy(r->r.numRetries(1)));
  if(!endpoint.isBlank())builder.endpointOverride(URI.create(endpoint));return builder.build();
 }
 @Bean @ConditionalOnProperty(name="billing.documents.storage",havingValue="s3")
 DocumentStorage objectStorage(S3Client client,@Value("${billing.documents.s3.bucket}") String bucket,@Value("${billing.documents.s3.encryption:true}") boolean encryption){if(!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))throw new IllegalArgumentException("Invalid invoice bucket");return new S3DocumentStorage(client,bucket,encryption);}
}
