CREATE TRIGGER immutable_billing_notice BEFORE UPDATE OR DELETE ON billing_notifications FOR EACH ROW EXECUTE FUNCTION immutable_financial();
