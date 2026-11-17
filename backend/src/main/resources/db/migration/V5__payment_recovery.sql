ALTER TABLE payments ADD COLUMN attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 8);
ALTER TABLE payments ADD COLUMN failure_class text;
ALTER TABLE payments ADD COLUMN next_retry_at timestamptz;
ALTER TABLE payments ADD COLUMN retry_deadline timestamptz;
ALTER TABLE payments ADD COLUMN max_attempts int NOT NULL DEFAULT 4 CHECK(max_attempts BETWEEN 1 AND 8);
ALTER TABLE payments ADD COLUMN method_version int NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN recovery_authorized boolean NOT NULL DEFAULT false;
CREATE TABLE payment_attempts (
 id uuid PRIMARY KEY, payment uuid NOT NULL REFERENCES payments,
 number int NOT NULL CHECK(number>0), state text NOT NULL CHECK(state IN ('PENDING','UNKNOWN','SUCCEEDED','DECLINED')),
 failure_class text, method_version int NOT NULL, scenario text NOT NULL,
 created_at timestamptz NOT NULL, completed_at timestamptz,
 UNIQUE(payment,number)
);
CREATE UNIQUE INDEX one_live_attempt ON payment_attempts(payment) WHERE state IN ('PENDING','UNKNOWN');
CREATE TABLE dunning_cases (
 payment uuid PRIMARY KEY REFERENCES payments, tenant text NOT NULL,
 started_at timestamptz NOT NULL, grace_until timestamptz NOT NULL,
 reminder_at timestamptz NOT NULL, final_reminder_at timestamptz NOT NULL,
 status text NOT NULL CHECK(status IN ('OPEN','ACTION_REQUIRED','UNKNOWN','EXHAUSTED','RESTRICTED','RECOVERED','STOPPED')),
 resolved_at timestamptz
);
CREATE INDEX payment_retry_due ON payments(next_retry_at) WHERE state='DECLINED' AND next_retry_at IS NOT NULL;
CREATE INDEX dunning_due ON dunning_cases(grace_until) WHERE status NOT IN ('RECOVERED','STOPPED','RESTRICTED');
CREATE TABLE billing_notifications (
 id uuid PRIMARY KEY, tenant text NOT NULL, payment uuid NOT NULL REFERENCES payments,
 kind text NOT NULL, message text NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(payment,kind)
);
CREATE FUNCTION protect_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.state IN ('SUCCEEDED','DECLINED') OR NEW.id<>OLD.id OR NEW.payment<>OLD.payment OR NEW.number<>OLD.number OR NEW.scenario<>OLD.scenario OR NEW.method_version<>OLD.method_version OR NEW.created_at<>OLD.created_at THEN
  RAISE EXCEPTION 'Completed payment attempts and request identity are immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_payment_attempt BEFORE UPDATE ON payment_attempts FOR EACH ROW EXECUTE FUNCTION protect_attempt();
CREATE TRIGGER no_delete_attempt BEFORE DELETE ON payment_attempts FOR EACH ROW EXECUTE FUNCTION immutable_financial();
-- Preserve V1 payment evidence as attempt 1, using its original provider operation identity.
INSERT INTO payment_attempts(id,payment,number,state,method_version,scenario,created_at,completed_at)
 SELECT id,id,1,state,0,scenario,created_at,CASE WHEN state IN ('SUCCEEDED','DECLINED') THEN created_at ELSE null END FROM payments;
UPDATE payments SET attempt_count=1;
-- Legacy declined outcomes are deliberately not made retryable without verified classification.
UPDATE payments SET failure_class=CASE WHEN state='UNKNOWN' THEN 'UNKNOWN' WHEN state='DECLINED' THEN 'HARD_DECLINE' ELSE null END;
