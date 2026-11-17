package com.acme.billing;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
@Component
class RazorpayWorker {
 private final JdbcTemplate db;private final RazorpayWebhooks hooks;private final RazorpayStore store;
 RazorpayWorker(JdbcTemplate db,RazorpayWebhooks hooks,RazorpayStore store){this.db=db;this.hooks=hooks;this.store=store;}
 @Scheduled(fixedDelay=1000) public void process(){
  for(var e:db.queryForList("select id from razorpay_events where status='RECEIVED' and next_run<=now() order by received_at limit 16"))try{hooks.process((UUID)e.get("id"));}catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Razorpay event processing failed: {}",ex.getClass().getSimpleName());}
  for(var c:db.queryForList("select id from razorpay_checkouts where state='CREATING' and created_at<now()-interval '1 minute' limit 16"))try{store.uncertain((UUID)c.get("id"));}catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Razorpay order requires reconciliation: {}",ex.getClass().getSimpleName());}
 }
}
