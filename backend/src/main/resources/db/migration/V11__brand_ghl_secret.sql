-- The per-brand HMAC secret an inbound GHL webhook is verified against. Each
-- brand is a separate GHL sub-account, so each has its own secret alongside its
-- own endpoint token.
--
-- Nullable on purpose, and that fails closed: a brand with no secret cannot
-- verify anything, so every webhook to its endpoint is rejected until the secret
-- is set. The alternative (a NOT NULL with a default) would ship a known secret.
ALTER TABLE brand ADD COLUMN ghl_webhook_secret text;
