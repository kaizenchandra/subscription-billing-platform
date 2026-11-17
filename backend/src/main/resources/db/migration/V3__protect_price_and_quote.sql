CREATE FUNCTION protect_price() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.currency,NEW.amount,NEW.months) IS DISTINCT FROM (OLD.id,OLD.currency,OLD.amount,OLD.months) THEN
  RAISE EXCEPTION 'Create a new price version instead of changing contractual pricing';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_price_terms BEFORE UPDATE ON plans FOR EACH ROW EXECUTE FUNCTION protect_price();
CREATE TRIGGER immutable_plan_quote BEFORE UPDATE OR DELETE ON plan_change_quotes FOR EACH ROW EXECUTE FUNCTION immutable_financial();
