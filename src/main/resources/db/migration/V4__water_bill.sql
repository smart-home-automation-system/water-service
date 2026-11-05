-- The bills of the waterworks (HAS-318), entered by the owner: a period of whole days, both ends included, and what was
-- billed for it. The service answers each with what the house measured over the same period.
CREATE TABLE water_bill
(
    id               BIGSERIAL PRIMARY KEY,
    period_from      DATE           NOT NULL,
    period_to        DATE           NOT NULL,
    billed_volume_m3 NUMERIC(12, 3) NOT NULL CHECK (billed_volume_m3 >= 0),
    meter_reading_m3 NUMERIC(12, 3) CHECK (meter_reading_m3 >= 0),
    amount           NUMERIC(12, 2) CHECK (amount >= 0),
    note             VARCHAR(500),
    created_at       TIMESTAMP      NOT NULL,
    updated_at       TIMESTAMP      NOT NULL,
    CHECK (period_to >= period_from)
);

CREATE INDEX water_bill_period ON water_bill (period_from, period_to);

-- The daily consumption from before the readings of the house (HAS-318): the history of the Shelly cloud, imported once
-- by hand - the valve keeps none itself. The service refuses a day after the first day of the readings; on that first day,
-- watched only from its first row, the imported value wins over the measured part.
CREATE TABLE water_consumption_import
(
    day       DATE PRIMARY KEY,
    volume_m3 NUMERIC(12, 3) NOT NULL CHECK (volume_m3 >= 0)
);
