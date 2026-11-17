ALTER TABLE payments ADD COLUMN provider text NOT NULL DEFAULT 'SIMULATOR' CHECK(provider IN ('SIMULATOR','RAZORPAY'));
CREATE TABLE razorpay_checkouts (
 id uuid PRIMARY KEY REFERENCES payments, tenant text NOT NULL, customer text NOT NULL,
 invoice uuid NOT NULL UNIQUE REFERENCES invoices, account_id text NOT NULL,
 state text NOT NULL CHECK(state IN ('PREPARED','CREATING','READY','UNKNOWN','PAID')),
 order_id text UNIQUE, payment_id text UNIQUE, last_status text,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE razorpay_events (
 id uuid PRIMARY KEY, account_id text NOT NULL, event_id text NOT NULL, body_hash text NOT NULL,
 event_type text NOT NULL, order_id text, payment_id text, amount bigint, currency text,
 captured boolean NOT NULL, method text, last4 text,
 status text NOT NULL DEFAULT 'RECEIVED' CHECK(status IN ('RECEIVED','PROCESSED','IGNORED','REVIEW')),
 received_at timestamptz NOT NULL DEFAULT now(), next_run timestamptz NOT NULL DEFAULT now(), attempts int NOT NULL DEFAULT 0,
 UNIQUE(account_id,event_id), UNIQUE(account_id,body_hash)
);
CREATE INDEX razorpay_events_due ON razorpay_events(next_run) WHERE status='RECEIVED';
CREATE TABLE payment_method_references (
 id uuid PRIMARY KEY, tenant text NOT NULL, customer text NOT NULL, provider text NOT NULL,
 provider_payment_id text NOT NULL UNIQUE, method text NOT NULL, last4 text, archived boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now()
);
