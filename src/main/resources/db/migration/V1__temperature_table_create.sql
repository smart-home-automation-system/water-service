CREATE TABLE temperature
(
    id          INT PRIMARY KEY NOT NULL UNIQUE GENERATED ALWAYS AS IDENTITY,
    updated_at  TIMESTAMP       NOT NULL,
    water       NUMERIC(5, 2)   NOT NULL,
    circulation NUMERIC(5, 2)   NOT NULL
)
