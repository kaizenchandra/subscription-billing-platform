ALTER TABLE invoices ADD COLUMN issued_at timestamptz;
-- Recover only recorded issuance evidence. Legacy invoices with no evidence remain explicitly unknown.
UPDATE invoices i SET issued_at=(SELECT min(e.created_at) FROM outbox e WHERE e.aggregate_id=i.id AND e.type='InvoiceFinalized.v1');
ALTER TABLE invoices ALTER COLUMN issued_at SET DEFAULT CURRENT_TIMESTAMP;
CREATE OR REPLACE FUNCTION protect_invoice() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.tenant,NEW.kind,NEW.issued_at,NEW.total,NEW.currency,NEW.number,NEW.subscription,NEW.cycle,NEW.period_start,NEW.period_end) IS DISTINCT FROM
    (OLD.id,OLD.tenant,OLD.kind,OLD.issued_at,OLD.total,OLD.currency,OLD.number,OLD.subscription,OLD.cycle,OLD.period_start,OLD.period_end) THEN
  RAISE EXCEPTION 'Finalized invoice identity, issue time and financial terms are immutable';
 END IF;
 RETURN NEW;
END $$;
