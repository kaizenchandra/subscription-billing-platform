ALTER TABLE subscriptions ADD COLUMN revision bigint NOT NULL DEFAULT 0;
ALTER TABLE invoices ALTER COLUMN cycle DROP NOT NULL;
ALTER TABLE invoices ADD COLUMN kind text NOT NULL DEFAULT 'RECURRING' CHECK(kind IN ('RECURRING','PRORATION'));
CREATE TABLE invoice_lines (
 id uuid PRIMARY KEY, invoice uuid NOT NULL REFERENCES invoices, description text NOT NULL,
 amount bigint NOT NULL, currency text NOT NULL, price text NOT NULL REFERENCES plans,
 period_start timestamptz NOT NULL, period_end timestamptz NOT NULL,
 CHECK(period_end > period_start)
);
CREATE TRIGGER immutable_invoice_lines BEFORE UPDATE OR DELETE ON invoice_lines FOR EACH ROW EXECUTE FUNCTION immutable_financial();
CREATE TABLE plan_change_quotes (
 id uuid PRIMARY KEY, tenant text NOT NULL, subscription uuid NOT NULL REFERENCES subscriptions,
 revision bigint NOT NULL, target_plan text NOT NULL REFERENCES plans,
 target_amount bigint NOT NULL CHECK(target_amount>=0), quantity int NOT NULL CHECK(quantity BETWEEN 1 AND 10000),
 mode text NOT NULL CHECK(mode IN ('NOW','RENEWAL')), currency text NOT NULL,
 effective_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 old_credit bigint NOT NULL CHECK(old_credit>=0), new_charge bigint NOT NULL CHECK(new_charge>=0),
 net bigint NOT NULL CHECK(net>=0), recurring_total bigint NOT NULL CHECK(recurring_total>=0),
 period_end timestamptz NOT NULL
);
CREATE TABLE subscription_changes (
 id uuid PRIMARY KEY, tenant text NOT NULL, subscription uuid NOT NULL REFERENCES subscriptions,
 quote uuid NOT NULL UNIQUE REFERENCES plan_change_quotes, invoice uuid UNIQUE REFERENCES invoices,
 status text NOT NULL CHECK(status IN ('SCHEDULED','APPLIED','CANCELLED')),
 created_at timestamptz NOT NULL DEFAULT now(), applied_at timestamptz
);
CREATE UNIQUE INDEX one_scheduled_change ON subscription_changes(subscription) WHERE status='SCHEDULED';
CREATE INDEX changes_history ON subscription_changes(tenant,subscription,created_at);
-- Backfill a truthful aggregate line for invoices from V1; no historical price version is invented.
INSERT INTO invoice_lines SELECT gen_random_uuid(),i.id,'Recurring subscription',i.total,i.currency,s.plan,i.period_start,i.period_end FROM invoices i JOIN subscriptions s ON s.id=i.subscription WHERE i.period_end>i.period_start;
