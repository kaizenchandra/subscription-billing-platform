package com.acme.billing;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.acme.billing.BillingService.*;

/** Quotes and application share the subscription row lock with renewal and cancellation. */
@Service
public class PlanChanges {
    private final BillingService billing;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final Clock clock;

    @Autowired
    public PlanChanges(BillingService billing, org.springframework.jdbc.core.JdbcTemplate db) {
        this(billing, db, Clock.systemUTC());
    }
    PlanChanges(BillingService billing, org.springframework.jdbc.core.JdbcTemplate db, Clock clock) {
        this.billing = billing; this.db = db; this.clock = clock;
    }
    private static java.sql.Timestamp ts(Instant time) { return java.sql.Timestamp.from(time); }

    @Transactional
    public Map<String,Object> preview(String tenant, UUID subscription, String plan, int quantity, String mode) {
        if (quantity < 1 || quantity > 10000 || !Set.of("NOW", "RENEWAL").contains(mode))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid quantity or effective mode");
        var sub = billing.subscription(tenant, subscription);
        eligible(sub);
        var target = billing.one("select * from plans where id=? and active=true", plan);
        if (!sub.get("currency").equals(target.get("currency")) || num(sub,"months") != num(target,"months"))
            throw conflict("Currency and interval changes require a new contract");
        long oldTotal = Math.multiplyExact(num(sub,"amount"), num(sub,"quantity"));
        long newTotal = Math.multiplyExact(num(target,"amount"), quantity);
        if (sub.get("plan").equals(plan) && num(sub,"quantity") == quantity)
            throw conflict("Plan and quantity are unchanged");
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant start = instant(sub.get("period_start")), end = instant(sub.get("period_end"));
        if (now.isBefore(start) || !now.isBefore(end)) throw conflict("Renewal must complete before changing plans");
        long credit = 0, charge = 0;
        if (mode.equals("NOW")) {
            if (newTotal < oldTotal) throw conflict("Schedule downgrades for renewal");
            if (!db.queryForList("select id from invoices where subscription=? and status='OPEN'",subscription).isEmpty())
                throw conflict("Pay outstanding invoices before an immediate change");
            if (db.queryForList("select id from invoices where subscription=? and cycle=? and status='PAID'", subscription, num(sub,"cycle")-1).isEmpty())
                throw conflict("Current period must be paid");
            credit = CalendarPolicy.prorate(oldTotal,start,end,now);
            charge = CalendarPolicy.prorate(newTotal,start,end,now);
        }
        UUID id = UUID.randomUUID();
        db.update("insert into plan_change_quotes values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,tenant,subscription,sub.get("revision"),plan,target.get("amount"),quantity,mode,sub.get("currency"),
            ts(mode.equals("NOW")?now:end),ts(now.plusSeconds(300)),credit,charge,charge-credit,newTotal,ts(end));
        return billing.one("select * from plan_change_quotes where id=?",id);
    }

    private void eligible(Map<String,Object> sub) {
        if (!sub.get("state").equals("ACTIVE") || Boolean.TRUE.equals(sub.get("cancel_at_end")))
            throw conflict("Only active subscriptions without pending cancellation may change plans");
        if (!db.queryForList("select id from subscription_changes where subscription=? and status='SCHEDULED'", sub.get("id")).isEmpty())
            throw conflict("A renewal change is already scheduled");
    }

    @Transactional
    public Map<String,Object> apply(String tenant, UUID subscription, String key, UUID quote) {
        var sub = billing.subscription(tenant, subscription);
        UUID op = billing.operation(tenant,"PLAN_CHANGE:"+subscription,key,quote.toString());
        var existing = db.queryForList("select * from subscription_changes where id=?", op);
        if (!existing.isEmpty()) return existing.getFirst();
        var q = billing.one("select * from plan_change_quotes where tenant=? and subscription=? and id=?",tenant,subscription,quote);
        eligible(sub);
        Instant now = clock.instant();
        if (!now.isBefore(instant(q.get("expires_at"))) || num(sub,"revision")!=num(q,"revision") || !now.isBefore(instant(sub.get("period_end"))))
            throw conflict("Quote expired or subscription changed; request a new preview");
        UUID invoice = null;
        boolean immediate = q.get("mode").equals("NOW");
        if (immediate) {
            if (!db.queryForList("select id from invoices where subscription=? and status='OPEN'", subscription).isEmpty())
                throw conflict("Outstanding invoice appeared after preview");
            long net = num(q,"net");
            if (net>0) {
                invoice=UUID.randomUUID();
                db.update("insert into invoices(id,tenant,subscription,cycle,currency,total,period_start,period_end,status,kind) values (?,?,?,null,?,?,?,?, 'OPEN','PRORATION')",
                    invoice,tenant,subscription,q.get("currency"),net,q.get("effective_at"),q.get("period_end"));
                line(invoice,"Unused old plan credit",-num(q,"old_credit"),sub.get("plan"),q);
                line(invoice,"Remaining new plan charge",num(q,"new_charge"),q.get("target_plan"),q);
                billing.journal(tenant,"invoice:"+invoice,(String)q.get("currency"),"RECEIVABLE","DEFERRED_REVENUE",net);
                billing.event(tenant,"InvoiceFinalized",invoice);
            }
            db.update("update subscriptions set plan=?,amount=?,quantity=?,revision=revision+1 where id=?",q.get("target_plan"),q.get("target_amount"),q.get("quantity"),subscription);
        } else db.update("update subscriptions set revision=revision+1 where id=?",subscription);
        db.update("insert into subscription_changes(id,tenant,subscription,quote,invoice,status,applied_at) values (?,?,?,?,?,?,?)",
            op,tenant,subscription,quote,invoice,immediate?"APPLIED":"SCHEDULED",immediate?ts(now):null);
        billing.event(tenant,immediate?"SubscriptionPlanChanged":"SubscriptionPlanChangeScheduled",op);
        return billing.one("select * from subscription_changes where id=?",op);
    }

    private void line(UUID invoice,String description,long amount,Object price,Map<String,Object> quote) {
        db.update("insert into invoice_lines values (?,?,?,?,?,?,?,?)",UUID.randomUUID(),invoice,description,amount,quote.get("currency"),price,quote.get("effective_at"),quote.get("period_end"));
    }
}
