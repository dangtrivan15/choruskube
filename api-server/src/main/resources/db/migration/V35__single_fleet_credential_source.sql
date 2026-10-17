-- One row per Worker registration token: every Worker presenting that token is answered with this source,
-- so Workers sharing a token must declare the same one.
CREATE TABLE public.single_fleet_credential_source (
    credential_digest text        NOT NULL,
    credential_source text        NOT NULL,
    instance_id       uuid        NOT NULL,
    declared_at       timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.single_fleet_credential_source
    ADD CONSTRAINT pk_single_fleet_credential_source PRIMARY KEY (credential_digest);
ALTER TABLE public.single_fleet_credential_source
    ADD CONSTRAINT ck_single_fleet_credential_source_value CHECK (credential_source IN ('control-plane', 'local'));
