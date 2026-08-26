-- Unit 28 -- the funnel cache is keyed by the RESOLVED WINDOW, not by the range's name.
--
-- WHY THIS IS A CORRECTNESS FIX AND NOT A RENAME.
--
-- V25 keyed a row on `(funnel, range_name)` where `range_name` was a `DateRange` constant --
-- TODAY, WEEK, MONTH, YEAR. That worked only while every range name described exactly one
-- window. The date filter now offers `custom`, an arbitrary from/to pair the caller chooses,
-- and EVERY custom window is named `custom`. Keyed by name, two different custom windows share
-- one row and serve each other's figures for a whole TTL.
--
-- That failure is invisible on screen. The two payloads have identical shape -- same stages,
-- same sources, same fields -- so a January total rendered under a March heading looks entirely
-- plausible, and nothing in a log contradicts it. It is the same class of bug the `funnel` half
-- of this key already prevents, which is why the fix is the same shape: put the thing that
-- actually distinguishes the rows INTO the key.
--
-- So the column now holds the resolved days, `2026-08-01..2026-08-26`, and is renamed to say so.
-- Storing a window under a column called `range_name` would be a lie the schema tells.
--
-- IT ALSO FIXES A SMALLER EXISTING FAULT, FREE. A row written for `month` used to keep answering
-- for `month` after midnight -- by which time "this month" was a different window -- until the
-- TTL expired. Keyed by the window, the new day is simply a new key and a cold read.

ALTER TABLE ghl_funnel_cache RENAME COLUMN range_name TO window_key;

-- Renaming the column leaves the constraint's NAME describing a key it no longer has; recreate it
-- so the two agree. `range_name` inside the old constraint follows the column automatically, so
-- this is about the name a DBA reads in an error message, not about the constraint's behaviour.
ALTER TABLE ghl_funnel_cache DROP CONSTRAINT uq_ghl_funnel_cache_window;
ALTER TABLE ghl_funnel_cache ADD CONSTRAINT uq_ghl_funnel_cache_window UNIQUE (funnel, window_key);

COMMENT ON COLUMN ghl_funnel_cache.window_key IS
    'The resolved inclusive day window, "yyyy-mm-dd..yyyy-mm-dd" -- NOT the range name. Two '
    'different custom windows are both named "custom", so keying on the name would let them '
    'share a row and serve each other''s figures. See DateWindow.key().';

-- EVERY EXISTING ROW IS DELETED, and that is the cheapest correct migration here.
--
-- The old keys ("MONTH") cannot be translated into new ones: the window a row was computed for
-- depends on the day it was written, which the row does not record -- `read_at` is when GHL was
-- asked, and a row written on 31 July under "MONTH" covers a different window from one written
-- on 1 August. Guessing would put figures under a window they were never computed for, which is
-- exactly the defect this migration exists to prevent.
--
-- Deleting is free BECAUSE THIS IS A CACHE and the table says so in V25: every row is
-- overwritten on refresh, nothing references it, and losing all of it costs one slow page load
-- per window somebody opens next. A cache that cannot be safely emptied is not a cache.
DELETE FROM ghl_funnel_cache;
