package cloud.cholewa.water.softener;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

//One frame of the live stream of the softener (HAS-401), as the iQua cloud sends it while a client keeps it open:
//{"name", "type": "property", "value", "converted_property": {"value", "units", ...}, "timestamp"} - checked on the real
//socket on 2026-11-09 (iqua/live-frames.json: app_active, service_active, current_water_flow_gpm, rf_signal_*,
//current_time_secs). Only the flow is taken: current_water_flow_gpm by its converted value when the units are litres a
//minute (value is in tenths of a gpm), rounded as the reading rounds it. A frame that is not JSON, not a property or
//without a number is nothing. Without I/O - pinned by LiveFrameTest
public record LiveFrame(double litresPerMinute, Instant at) {

    static final String FLOW_PROPERTY = "current_water_flow_gpm";

    static Optional<LiveFrame> flow(final String text, final JsonMapper jsonMapper) {
        final JsonNode frame;
        try {
            frame = jsonMapper.readTree(text);
        } catch (final RuntimeException notJson) {
            return Optional.empty();
        }
        final JsonNode converted = frame.path("converted_property");
        if (!"property".equals(frame.path("type").asString("")) || !FLOW_PROPERTY.equals(frame.path("name").asString(""))
            || !converted.path("units").asString("").trim().toLowerCase(Locale.ROOT).startsWith("liter")
            || !converted.path("value").isNumber()) {
            return Optional.empty();
        }
        final double litres = converted.path("value").decimalValue().setScale(1, RoundingMode.HALF_UP).doubleValue();
        return Optional.of(new LiveFrame(Math.max(0, litres), instant(frame.path("timestamp").asString(""))));
    }

    //the time of the frame, null when it has none - the tracker dates it by its arrival then
    private static Instant instant(final String timestamp) {
        try {
            return timestamp.isBlank() ? null : Instant.parse(timestamp);
        } catch (final DateTimeParseException notInstant) {
            return null;
        }
    }
}
