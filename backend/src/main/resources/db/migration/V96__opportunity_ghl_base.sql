-- Spec 86 §2: the value each locally edited shared field held BEFORE the desk's first unconfirmed edit
-- (field name -> value as text). The outbox compares GHL's current value against it to tell "GHL did
-- not touch this" from "GHL changed it too". Null at rest, cleared with `locally_edited_fields`.
ALTER TABLE opportunity ADD COLUMN ghl_base jsonb;
