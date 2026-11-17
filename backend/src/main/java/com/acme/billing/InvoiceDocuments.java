package com.acme.billing;
import com.acme.billing.documents.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.security.MessageDigest;
import java.util.concurrent.Semaphore;

@Service
public class InvoiceDocuments {
 public record Download(byte[] bytes,String filename,String sha256){}
 private final InvoiceDocumentStore records;private final DocumentStorage storage;private final InvoicePdfRenderer renderer;private final JsonMapper json=new JsonMapper();private final Semaphore renders=new Semaphore(4);
 public InvoiceDocuments(InvoiceDocumentStore records,DocumentStorage storage,InvoicePdfRenderer renderer){this.records=records;this.storage=storage;this.renderer=renderer;}
 public Download download(String tenant,String actor,UUID id,boolean finance){
  var row=records.prepare(tenant,actor,id,finance); // Authorization precedes any storage access, including cache hits.
  if(!row.get("state").equals("READY")){
   if(!renders.tryAcquire())throw unavailable();UUID lease=null;
   try{lease=records.claim(id);if(lease==null){row=records.current(id);if(!row.get("state").equals("READY"))throw unavailable();}
    else {var invoice=json.readValue((String)row.get("snapshot"),InvoiceSnapshot.class);byte[] bytes=renderer.render(invoice);String sha=digest(bytes);String key="invoices/"+id+"/v1/"+sha+".pdf";storage.put(key,bytes);if(!records.publish(id,lease,key,sha,bytes.length))throw unavailable();row=records.current(id);}
   }catch(RuntimeException e){if(lease!=null)records.failed(id,lease);if(e instanceof ResponseStatusException)throw e;throw unavailable();}finally{renders.release();}
  }
  byte[] bytes;try{bytes=storage.get((String)row.get("object_key"));if(bytes.length!=((Number)row.get("size_bytes")).intValue()||!digest(bytes).equals(row.get("sha256")))throw unavailable();}catch(RuntimeException e){throw unavailable();}
  records.access(tenant,actor,id,finance);var snapshot=json.readValue((String)row.get("snapshot"),InvoiceSnapshot.class);
  return new Download(bytes,"invoice-INV-"+snapshot.number()+".pdf",(String)row.get("sha256"));
 }
 private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invoice document is being prepared or storage is unavailable. Retry later; financial records are unchanged.");}
 public static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
