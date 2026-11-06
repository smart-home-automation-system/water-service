-- The runs of the hot-water circulation pump (HAS-404), relay 1 of the Shelly Uni of the hot water: one row a run the
-- service started. started_at / ended_at - house time, ended_at NULL while the run goes on; trigger - what started it
-- (manual today, motion / light / window with the automation of HAS-405); source - who asked (dashboard, rule, ...);
-- stopped_by - temperature (the return reached its target), limit (water.circulation.max-run), device (the relay was found
-- off before either - the timer of the device or a hand in the Shelly app), failure (the pump could not be switched off,
-- or was not heard while its run was due to end - the timer of the device stops it); return_at_start / return_at_end - the
-- temperature of the return in degrees C, NULL when not read. Kept 365 days
CREATE TABLE circulation_run
(
    id              BIGSERIAL PRIMARY KEY,
    started_at      TIMESTAMP   NOT NULL,
    ended_at        TIMESTAMP,
    trigger         VARCHAR(10) NOT NULL CHECK (trigger IN ('manual', 'motion', 'light', 'window')),
    source          VARCHAR(20) NOT NULL,
    stopped_by      VARCHAR(12) CHECK (stopped_by IN ('temperature', 'limit', 'device', 'failure')),
    return_at_start NUMERIC(5, 2),
    return_at_end   NUMERIC(5, 2)
);

CREATE INDEX circulation_run_started_at ON circulation_run (started_at);
