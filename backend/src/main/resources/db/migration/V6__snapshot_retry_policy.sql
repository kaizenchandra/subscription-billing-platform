ALTER TABLE payments ADD COLUMN retry_base_seconds bigint CHECK(retry_base_seconds BETWEEN 1 AND 172800);
UPDATE payments SET retry_base_seconds=21600 WHERE state IN ('DECLINED','UNKNOWN');
-- Backfill conservative recovery policies for legacy failures, without issuing any retry.
INSERT INTO dunning_cases(payment,tenant,started_at,grace_until,reminder_at,final_reminder_at,status)
 SELECT id,tenant,created_at,created_at+interval '10 days',created_at+interval '5 days',created_at+interval '7 days 12 hours',
 CASE WHEN state='UNKNOWN' THEN 'UNKNOWN' ELSE 'ACTION_REQUIRED' END FROM payments WHERE state IN ('DECLINED','UNKNOWN') ON CONFLICT DO NOTHING;
UPDATE payments SET retry_deadline=created_at+interval '7 days' WHERE state IN ('DECLINED','UNKNOWN') AND retry_deadline IS NULL;

ALTER TABLE payments ADD COLUMN provider_not_before timestamptz;
