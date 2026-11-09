-- The readings of the water softener (HAS-398), as GET /home/water/softener serves them: a row only when one of the
-- values changed, otherwise last_checked_at of the latest row moves (the rule of the status tables). Litres and l/min as
-- the iQua cloud converts them, the salt in per cent as its application shows it, the hardness in grains per gallon; a
-- value the cloud left out is NULL. measured_at is the house time of the read that brought the values first,
-- last_checked_at of the last read that brought them again. Rows older than 730 days are deleted every night, never the
-- latest.
CREATE TABLE softener_reading
(
    id                       BIGSERIAL PRIMARY KEY,
    total_litres             NUMERIC(12, 1),
    used_today_litres        NUMERIC(9, 1),
    treated_available_litres NUMERIC(9, 1),
    flow_litres_per_minute   NUMERIC(6, 1),
    salt_level_percent       NUMERIC(4, 1),
    salt_days_left           INTEGER,
    hardness_gpg             NUMERIC(5, 1),
    total_regenerations      INTEGER,
    low_salt                 BOOLEAN,
    leak                     BOOLEAN,
    excessive_water_use      BOOLEAN,
    shutoff_valve            BOOLEAN,
    resin                    BOOLEAN,
    error                    BOOLEAN,
    measured_at              TIMESTAMP NOT NULL,
    last_checked_at          TIMESTAMP NOT NULL
);

CREATE INDEX softener_reading_measured_at ON softener_reading (measured_at);

-- The regenerations of the softener (HAS-398) from the regeneration events of the iQua cloud, one row per event by its id
-- in the cloud. started_at / ended_at - house time, ended_at NULL while the regeneration goes on (completed by a later
-- read); device_started_at - the start as the clock of the softener had it. Rows started more than 730 days ago are
-- deleted every night
CREATE TABLE softener_regeneration
(
    id                BIGSERIAL PRIMARY KEY,
    cloud_id          VARCHAR(64) NOT NULL UNIQUE,
    started_at        TIMESTAMP   NOT NULL,
    ended_at          TIMESTAMP,
    device_started_at TIMESTAMP
);

CREATE INDEX softener_regeneration_started_at ON softener_regeneration (started_at);
