CREATE TABLE simulator_method_updates (
 id uuid PRIMARY KEY, payment uuid NOT NULL REFERENCES payments,
 version int NOT NULL CHECK(version>0), created_at timestamptz NOT NULL,
 UNIQUE(payment,version)
);
-- Preserve completed recovery identities if upgrading a database that already ran V5-V7.
-- Operations commit with the method update, so they remain evidence even if outbox rows were pruned.
INSERT INTO simulator_method_updates(id,payment,version,created_at)
 SELECT o.result,p.id,row_number() OVER (PARTITION BY p.id ORDER BY o.result)::int,p.created_at
 FROM operations o JOIN payments p ON o.tenant=p.tenant AND o.kind='SIMULATOR_METHOD:'||p.id::text;
CREATE TRIGGER immutable_simulator_method_update BEFORE UPDATE OR DELETE ON simulator_method_updates FOR EACH ROW EXECUTE FUNCTION immutable_financial();
