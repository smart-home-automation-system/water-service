-- The flow alarms of the water valve (HAS-320) are episodes of water_alert too: a high flow, a flow that does not stop,
-- a flow while nobody is at home. Only the kinds a row may have change; 0.10.0 reads a row of a new kind as one it does
-- not know (a WARN, the row left alone)
ALTER TABLE water_alert DROP CONSTRAINT water_alert_kind_check;

ALTER TABLE water_alert
    ADD CONSTRAINT water_alert_kind_check
        CHECK (kind IN ('no mains', 'offline', 'frost', 'high flow', 'continuous flow', 'empty house flow'));
