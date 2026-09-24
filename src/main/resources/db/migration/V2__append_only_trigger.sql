-- ============================================================================
-- V2__append_only_trigger.sql
-- The evidence table is append-only at the database level. UPDATE and DELETE
-- are rejected for ordinary sessions, which both prevents application bugs
-- from rewriting history and means that any successful tampering requires
-- deliberate, superuser-level intervention.
--
-- Tests that need to *simulate* an attacker set the session-local GUC
-- evidence.allow_tamper = 'on' before mutating rows.
-- ============================================================================

CREATE OR REPLACE FUNCTION evidence_append_only() RETURNS trigger AS $$
BEGIN
    IF current_setting('evidence.allow_tamper', true) = 'on' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION
        'plan_evidence_event is append-only (op=% triggered on plan_id=%)',
        TG_OP, COALESCE(NEW.plan_id, OLD.plan_id)
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_evidence_no_update
    BEFORE UPDATE ON plan_evidence_event
    FOR EACH ROW EXECUTE FUNCTION evidence_append_only();

CREATE TRIGGER trg_evidence_no_delete
    BEFORE DELETE ON plan_evidence_event
    FOR EACH ROW EXECUTE FUNCTION evidence_append_only();
