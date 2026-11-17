CREATE TABLE invoice_documents (
 invoice uuid PRIMARY KEY REFERENCES invoices, tenant text NOT NULL, template_version int NOT NULL DEFAULT 1,
 snapshot text NOT NULL CHECK(octet_length(snapshot)<=262144), state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','READY')),
 object_key text, sha256 text, size_bytes int CHECK(size_bytes BETWEEN 1 AND 2097152),
 lease_token uuid, lease_until timestamptz, attempts int NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),
 next_run timestamptz NOT NULL DEFAULT now(), created_at timestamptz NOT NULL DEFAULT now(), ready_at timestamptz,
 CHECK(state<>'READY' OR (object_key IS NOT NULL AND sha256 ~ '^[0-9a-f]{64}$' AND size_bytes IS NOT NULL AND ready_at IS NOT NULL))
);
CREATE TABLE invoice_document_access (
 id uuid PRIMARY KEY, tenant text NOT NULL, invoice uuid NOT NULL REFERENCES invoices, actor text NOT NULL,
 access_scope text NOT NULL CHECK(access_scope IN ('CUSTOMER','FINANCE')), prepared_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION protect_invoice_document() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.invoice,NEW.tenant,NEW.template_version,NEW.snapshot,NEW.created_at) IS DISTINCT FROM (OLD.invoice,OLD.tenant,OLD.template_version,OLD.snapshot,OLD.created_at) OR OLD.state='READY' THEN
  RAISE EXCEPTION 'Invoice document snapshot and published artifact are immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_invoice_document BEFORE UPDATE ON invoice_documents FOR EACH ROW EXECUTE FUNCTION protect_invoice_document();
CREATE TRIGGER no_delete_invoice_document BEFORE DELETE ON invoice_documents FOR EACH ROW EXECUTE FUNCTION immutable_financial();
CREATE TRIGGER immutable_document_access BEFORE UPDATE OR DELETE ON invoice_document_access FOR EACH ROW EXECUTE FUNCTION immutable_financial();
