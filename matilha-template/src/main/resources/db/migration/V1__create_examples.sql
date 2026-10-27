CREATE TABLE examples (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ,
    version     BIGINT,
    CONSTRAINT uk_examples_name UNIQUE (name),
    CONSTRAINT ck_examples_name_not_blank CHECK (btrim(name) <> '')
);
