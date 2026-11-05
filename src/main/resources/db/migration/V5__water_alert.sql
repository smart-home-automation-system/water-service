-- The alarms of the water valve (HAS-319), one row for each episode the household is told of: no mains water (the
-- pressure below its bound), the valve offline (not heard), frost (the water close to freezing).
-- opened_at - since when: the first pass that saw the pressure or the temperature below its bound, the last time the
-- valve was heard. last_told_at - when the alert (or its last reminder) was published; NULL while the alert has not got
-- through yet, so that its return announces it. closed_at - the return, NULL while it lasts: at most one open row a
-- kind (the partial unique index) - what a restart takes up again
CREATE TABLE water_alert
(
    id           BIGSERIAL PRIMARY KEY,
    kind         VARCHAR(20) NOT NULL CHECK (kind IN ('no mains', 'offline', 'frost')),
    opened_at    TIMESTAMP   NOT NULL,
    last_told_at TIMESTAMP,
    closed_at    TIMESTAMP
);

CREATE INDEX water_alert_opened_at ON water_alert (opened_at);

CREATE UNIQUE INDEX water_alert_one_open ON water_alert (kind) WHERE closed_at IS NULL;
