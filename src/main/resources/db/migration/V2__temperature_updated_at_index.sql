-- the history is read by a range of updated_at, and status/temperature by its newest value
CREATE INDEX temperature_updated_at_idx ON temperature (updated_at);
