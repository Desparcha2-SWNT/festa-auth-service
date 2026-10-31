-- Autorización de tratamiento de datos personales (Ley 1581 de 2012).
-- Se guarda cuándo y bajo qué versión de la política la aceptó el titular.
-- Es NULL en cuentas anteriores a esta migración y en el admin sembrado.
ALTER TABLE accounts
    ADD COLUMN privacy_accepted_at     TIMESTAMPTZ,
    ADD COLUMN privacy_policy_version  TEXT;
