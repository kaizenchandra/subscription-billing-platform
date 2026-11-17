package com.acme.billing;
import com.acme.billing.documents.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.acme.billing.BillingService.*;

@Service
public class InvoiceDocumentStore {
 private final JdbcTemplate db;private final BillingService billing;private final JsonMapper json=new JsonMapper();
 public InvoiceDocumentStore(JdbcTemplate db,BillingService billing){this.db=db;this.billing=billing;}
 public void authorize(String tenant,String actor,UUID id,boolean finance){
  if(finance)billing.one("select id from invoices where tenant=? and id=?",tenant,id);
  else billing.one("select i.id from invoices i join subscriptions s on s.id=i.subscription where i.tenant=? and s.customer=? and i.id=?",tenant,actor,id);
 }
 @Transactional public Map<String,Object> prepare(String tenant,String actor,UUID id,boolean finance){
  authorize(tenant,actor,id,finance);
  var i=billing.one("select i.*,s.customer from invoices i join subscriptions s on s.id=i.subscription where i.tenant=? and i.id=? for update of i",tenant,id);
  var existing=db.queryForList("select * from invoice_documents where invoice=?",id);if(!existing.isEmpty())return existing.getFirst();
  var rows=db.queryForList("select * from invoice_lines where invoice=? order by amount,id limit 1001",id);
  if(rows.isEmpty()||rows.size()>1000)throw conflict("Invoice PDF requires 1 to 1000 finalized lines");
  long sum=0;var lines=new ArrayList<InvoiceSnapshot.Line>();
  for(var row:rows){sum=Math.addExact(sum,num(row,"amount"));if(!row.get("currency").equals(i.get("currency")))throw conflict("Invoice line currency mismatch");String description=(String)row.get("description");if(description.length()>2000)throw conflict("Invoice line exceeds document layout limit");lines.add(new InvoiceSnapshot.Line(description,(String)row.get("price"),instant(row.get("period_start")).toString(),instant(row.get("period_end")).toString(),num(row,"amount")));}
  if(sum!=num(i,"total"))throw conflict("Finalized invoice line total mismatch; reconcile before rendering");
  var issue=(java.sql.Timestamp)i.get("issued_at");
  if(((String)i.get("customer")).length()>2048)throw conflict("Billing account exceeds document layout limit");
  var snapshot=new InvoiceSnapshot(id.toString(),num(i,"number"),"Accrual sandbox billing",(String)i.get("customer"),issue==null?"Not recorded (legacy invoice)":issue.toInstant().toString(),instant(i.get("period_start")).toString(),instant(i.get("period_end")).toString(),(String)i.get("currency"),num(i,"total"),(String)i.get("kind"),lines);
  String encoded=json.writeValueAsString(snapshot);if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw conflict("Invoice exceeds document snapshot size limit");
  db.update("insert into invoice_documents(invoice,tenant,snapshot) values (?,?,?)",id,tenant,encoded);return billing.one("select * from invoice_documents where invoice=?",id);
 }
 @Transactional public UUID claim(UUID id){
  UUID token=UUID.randomUUID();int changed=db.update("update invoice_documents set lease_token=?,lease_until=now()+interval '30 seconds',attempts=attempts+1 where invoice=? and state='PENDING' and attempts<8 and next_run<=now() and (lease_until is null or lease_until<now())",token,id);return changed==1?token:null;
 }
 @Transactional public boolean publish(UUID id,UUID token,String key,String sha,int size){return db.update("update invoice_documents set state='READY',object_key=?,sha256=?,size_bytes=?,ready_at=now(),lease_until=null where invoice=? and lease_token=? and state='PENDING'",key,sha,size,id,token)==1;}
 @Transactional public void failed(UUID id,UUID token){db.update("update invoice_documents set lease_until=null,next_run=now()+interval '5 seconds' where invoice=? and lease_token=? and state='PENDING'",id,token);}
 public Map<String,Object> current(UUID id){return billing.one("select * from invoice_documents where invoice=?",id);}
 @Transactional public void access(String tenant,String actor,UUID id,boolean finance){db.update("insert into invoice_document_access(id,tenant,invoice,actor,access_scope) values (?,?,?,?,?)",UUID.randomUUID(),tenant,id,actor,finance?"FINANCE":"CUSTOMER");}
}
