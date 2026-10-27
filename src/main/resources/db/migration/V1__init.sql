-- Cuentas. Los datos personales (email, nombre) se guardan cifrados (AES-256-GCM);
-- email_hash es un índice ciego (HMAC-SHA256) para buscar por email sin descifrar.
CREATE TABLE accounts (
    id                 UUID PRIMARY KEY,
    email_hash         BYTEA        NOT NULL UNIQUE,
    email_enc          TEXT         NOT NULL,
    display_name_enc   TEXT         NOT NULL,
    password_hash      TEXT,
    google_sub         TEXT UNIQUE,
    role               TEXT         NOT NULL CHECK (role IN ('user', 'merchant', 'admin')),
    status             TEXT         NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'deleted')),
    suspended_until    TIMESTAMPTZ,
    failed_logins      INT          NOT NULL DEFAULT 0,
    locked_until       TIMESTAMPTZ,
    last_login_at      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT has_credential CHECK (status = 'deleted' OR password_hash IS NOT NULL OR google_sub IS NOT NULL)
);

-- Refresh tokens con rotación. Solo se guarda el hash.
-- Si se reusa un token ya rotado, se revoca toda su familia (posible robo).
CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY,
    account_id   UUID        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    family_id    UUID        NOT NULL,
    token_hash   BYTEA       NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_account_idx ON refresh_tokens (account_id) WHERE revoked_at IS NULL;
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);

-- Transactional outbox: el evento se guarda en la misma transacción que el cambio.
CREATE TABLE outbox (
    id              UUID PRIMARY KEY,           -- = eventId del sobre
    exchange        TEXT        NOT NULL,
    routing_key     TEXT        NOT NULL,
    payload         TEXT        NOT NULL,       -- sobre completo en JSON
    correlation_id  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT
);
CREATE INDEX outbox_pending_idx ON outbox (created_at) WHERE published_at IS NULL;

-- Idempotencia de consumidores.
CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    event_type    TEXT        NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
