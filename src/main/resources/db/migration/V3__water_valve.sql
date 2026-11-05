-- The counter of the water valve (HAS-316): a row only when its total changed, last_checked_at of the latest row moved
-- while it is the same. total_m3 as the valve reports it (value.counter.total of object:200, m3, nine decimals);
-- measured_at is the house time the total was first reported, last_checked_at the last time it was. Readings older than
-- 730 days are deleted every night, never the latest row.
CREATE TABLE water_meter_reading
(
    id              BIGSERIAL PRIMARY KEY,
    total_m3        NUMERIC(18, 9) NOT NULL,
    measured_at     TIMESTAMP      NOT NULL,
    last_checked_at TIMESTAMP      NOT NULL
);

CREATE INDEX water_meter_reading_measured_at ON water_meter_reading (measured_at);

-- The valve as it was last reported (HAS-316): one current row, id 1. In the units of the device - flow_rate m3/min
-- (number:200), pressure kPa (number:201), temperature degrees C (number:202), open the state of boolean:200; a value not
-- reported within the staleness of the service is NULL. measured_at moves only when a value changed, last_checked_at
-- with every snapshot.
CREATE TABLE water_valve_status
(
    id              SMALLINT PRIMARY KEY CHECK (id = 1),
    open            BOOLEAN,
    flow_rate       DOUBLE PRECISION,
    pressure        DOUBLE PRECISION,
    temperature     DOUBLE PRECISION,
    measured_at     TIMESTAMP NOT NULL,
    last_checked_at TIMESTAMP NOT NULL
);
