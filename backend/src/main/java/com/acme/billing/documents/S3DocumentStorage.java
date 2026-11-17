package com.acme.billing.documents;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
public final class S3DocumentStorage implements DocumentStorage {
 private final S3Client client;private final String bucket;private final boolean encryption;
 public S3DocumentStorage(S3Client client,String bucket,boolean encryption){this.client=client;this.bucket=bucket;this.encryption=encryption;}
 public void put(String key,byte[] bytes){
  DocumentStorage.validateKey(key);if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("Document exceeds size limit");
  try{var request=PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/pdf").ifNoneMatch("*");if(encryption)request.serverSideEncryption(ServerSideEncryption.AES256);client.putObject(request.build(),RequestBody.fromBytes(bytes));}
  catch(S3Exception e){if(e.statusCode()!=412)throw new IllegalStateException("Document storage unavailable");}catch(RuntimeException e){throw new IllegalStateException("Document storage unavailable");}
 }
 public byte[] get(String key){
  DocumentStorage.validateKey(key);
  try{return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),(response,stream)->{if(response.contentLength()>MAX_BYTES)throw new IllegalStateException("Document exceeds size limit");byte[] bytes=stream.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new IllegalStateException("Document exceeds size limit");return bytes;});}
  catch(RuntimeException e){throw new IllegalStateException("Document storage unavailable");}
 }
}
