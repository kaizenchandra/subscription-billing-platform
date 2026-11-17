ALTER TABLE jobs ADD COLUMN expected_cycle bigint;
-- Only outstanding jobs need an executable cycle identity; completed legacy jobs stay archival.
UPDATE jobs j SET expected_cycle=s.cycle FROM subscriptions s WHERE s.id=j.subscription AND j.status='READY';
ALTER TABLE jobs ADD CONSTRAINT ready_job_has_cycle CHECK(status<>'READY' OR expected_cycle IS NOT NULL);
