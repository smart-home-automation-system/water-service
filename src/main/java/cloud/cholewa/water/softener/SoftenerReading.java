package cloud.cholewa.water.softener;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

//What the softener said in one read of the iQua cloud (HAS-397), decoded from the properties of the device by their names.
//The volumes and the flow are the converted values when the cloud converted them to litres (converted_units "Liters",
//"liters/min" - the units of the account; value itself is in gallons whatever the name says), otherwise left out. The salt
//level is the per cent the application shows, enriched_data.water_treatment.salt_level_percent - salt_level_tenths is not a
//per cent (20 while the application showed 25 %, read 2026-11-09). The hardness is hardness_grains as the softener keeps it.
//A property the answer leaves out, or one that is not a number, is null here - never a default. The flags are 0 / 1 or
//booleans. Pinned on the answer of the real softener by SoftenerReadingTest.
//The flow is the value the cloud last refreshed: it moves only while an application keeps a live session (HAS-401)
public record SoftenerReading(
    Boolean online,
    Double usedTodayLitres,
    Double averageDailyLitres,
    Double treatedAvailableLitres,
    Double totalLitres,
    Double flowLitresPerMinute,
    Double saltLevelPercent,
    Integer saltDaysLeft,
    Integer daysSinceRegeneration,
    Integer totalRegenerations,
    Double hardnessGpg,
    Integer daysInOperation,
    Integer signalDbm,
    Alerts alerts
) {

    //every property the reading takes - another one is logged once at DEBUG by the reader
    static final Set<String> KNOWN = Set.of("gallons_used_today", "avg_daily_use_gals", "treated_water_avail_gals",
        "total_outlet_water_gals", "current_water_flow_gpm", "out_of_salt_estimate_days",
        "days_since_last_regen", "total_regens", "hardness_grains", "days_in_operation", "rf_signal_strength_dbm",
        "low_salt_alert", "flow_monitor_alert", "excessive_water_use_alert", "water_shutoff_valve_alert", "resin_alert",
        "error_code_alert", "_internal_is_online", "regen_status_enum", "regen_time_secs");

    //leak - flow_monitor_alert, the continuous flow the softener itself watches
    public record Alerts(
        Boolean lowSalt,
        Boolean leak,
        Boolean excessiveWaterUse,
        Boolean shutoffValve,
        Boolean resin,
        Boolean error
    ) {
    }

    //online - the property _internal_is_online, or is_online of the device when the answer has no such property
    static SoftenerReading of(final IquaDevice device) {
        final Map<String, IquaProperty> properties = Optional.ofNullable(device.properties()).orElse(Map.of());
        final Function<String, Optional<JsonNode>> value = name -> Optional.ofNullable(properties.get(name))
            .map(IquaProperty::value);
        final Function<String, Optional<JsonNode>> litres = name -> Optional.ofNullable(properties.get(name))
            .filter(property -> property.convertedUnits() != null
                && property.convertedUnits().trim().toLowerCase(Locale.ROOT).startsWith("liter"))
            .map(IquaProperty::convertedValue);
        final Optional<JsonNode> saltPercent = Optional.ofNullable(device.enrichedData())
            .map(enriched -> enriched.path("water_treatment").path("salt_level_percent"));
        return new SoftenerReading(
            flag(value.apply("_internal_is_online")).orElse(device.online()),
            decimal(litres.apply("gallons_used_today"), 1),
            decimal(litres.apply("avg_daily_use_gals"), 1),
            decimal(litres.apply("treated_water_avail_gals"), 1),
            decimal(litres.apply("total_outlet_water_gals"), 1),
            decimal(litres.apply("current_water_flow_gpm"), 1),
            decimal(saltPercent, 1),
            whole(value.apply("out_of_salt_estimate_days")),
            whole(value.apply("days_since_last_regen")),
            whole(value.apply("total_regens")),
            decimal(value.apply("hardness_grains"), 1),
            whole(value.apply("days_in_operation")),
            whole(value.apply("rf_signal_strength_dbm")),
            new Alerts(
                flag(value.apply("low_salt_alert")).orElse(null),
                flag(value.apply("flow_monitor_alert")).orElse(null),
                flag(value.apply("excessive_water_use_alert")).orElse(null),
                flag(value.apply("water_shutoff_valve_alert")).orElse(null),
                flag(value.apply("resin_alert")).orElse(null),
                flag(value.apply("error_code_alert")).orElse(null)));
    }

    private static Optional<BigDecimal> number(final Optional<JsonNode> node) {
        return node.flatMap(present -> {
            if (present.isNumber()) {
                return Optional.of(present.decimalValue());
            }
            if (present.isString()) {
                try {
                    return Optional.of(new BigDecimal(present.asString().trim()));
                } catch (final NumberFormatException notNumber) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        });
    }

    private static Double decimal(final Optional<JsonNode> node, final int scale) {
        return number(node).map(value -> value.setScale(scale, RoundingMode.HALF_UP).doubleValue()).orElse(null);
    }

    private static Integer whole(final Optional<JsonNode> node) {
        return number(node).map(value -> value.setScale(0, RoundingMode.HALF_UP).intValue()).orElse(null);
    }

    //a boolean, or a number - 0 is false, anything else true; a string "true" / "false" / a number as text too
    private static Optional<Boolean> flag(final Optional<JsonNode> node) {
        return node.flatMap(present -> {
            if (present.isBoolean()) {
                return Optional.of(present.booleanValue());
            }
            if (present.isString() && Set.of("true", "false").contains(present.asString().trim().toLowerCase())) {
                return Optional.of(Boolean.parseBoolean(present.asString().trim()));
            }
            return number(Optional.of(present)).map(value -> value.signum() != 0);
        });
    }
}
