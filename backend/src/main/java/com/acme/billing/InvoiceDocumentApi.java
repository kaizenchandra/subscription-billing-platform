package com.acme.billing;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.http.*;
import java.util.UUID;
@RestController @RequestMapping("/api/v1")
class InvoiceDocumentApi {
 private final Api auth;private final InvoiceDocuments documents;private final org.springframework.jdbc.core.JdbcTemplate db;
 InvoiceDocumentApi(Api auth,InvoiceDocuments documents,org.springframework.jdbc.core.JdbcTemplate db){this.auth=auth;this.documents=documents;this.db=db;}
 @GetMapping(value="/invoices/{id}/pdf",produces="application/pdf") ResponseEntity<byte[]> customer(Authentication a,@PathVariable UUID id){return response(documents.download(auth.tenant(a),a.getName(),id,false));}
 @GetMapping("/admin/invoices") Object invoices(Authentication a){auth.finance(a);return db.queryForList("select * from invoices where tenant=? order by number desc limit 100",auth.tenant(a));}
 @GetMapping(value="/admin/invoices/{id}/pdf",produces="application/pdf") ResponseEntity<byte[]> finance(Authentication a,@PathVariable UUID id){auth.finance(a);return response(documents.download(auth.tenant(a),a.getName(),id,true));}
 private ResponseEntity<byte[]> response(InvoiceDocuments.Download download){return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+download.filename()+"\"").header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").header("X-Document-SHA256",download.sha256()).body(download.bytes());}
}
