-- The alarms of the water softener (HAS-399) are episodes of water_alert too: the salt low, the leak the softener itself
-- watches (flow_monitor_alert), an excessive use of water, its shutoff valve, its resin, an error code, and the softener
-- not heard from the iQua cloud. Only the kinds a row may have change; 0.16.0 reads a row of a new kind as one it does not
-- know (a WARN, the row left alone)
ALTER TABLE water_alert DROP CONSTRAINT water_alert_kind_check;

ALTER TABLE water_alert
    ADD CONSTRAINT water_alert_kind_check
        CHECK (kind IN ('no mains', 'offline', 'frost', 'high flow', 'continuous flow', 'empty house flow',
                        'low salt', 'softener leak', 'excessive use', 'softener valve', 'softener resin',
                        'softener error', 'softener offline'));
