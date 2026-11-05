-- The commands of the water valve (HAS-321), one row for each command the valve took: commanded_at - when it was sent,
-- on the clock of the house; state - what was asked (open or close); source - who asked (dashboard, rule, ...);
-- confirmed_at - the report of the valve in that state that answered it, NULL while it has not or never did. A command
-- the valve refused is not stored - its caller got the 502
CREATE TABLE water_valve_command
(
    id           BIGSERIAL PRIMARY KEY,
    commanded_at TIMESTAMP   NOT NULL,
    state        VARCHAR(5)  NOT NULL CHECK (state IN ('open', 'close')),
    source       VARCHAR(20) NOT NULL,
    confirmed_at TIMESTAMP
);

CREATE INDEX water_valve_command_commanded_at ON water_valve_command (commanded_at);
