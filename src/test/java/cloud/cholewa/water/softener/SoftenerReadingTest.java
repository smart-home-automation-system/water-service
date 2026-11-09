package cloud.cholewa.water.softener;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//The mapping of the properties of the softener (HAS-397), pinned on the answer of the real AquaHome 20 Smart of the house
//read on 2026-11-09 (src/test/resources/iqua/detail-or-summary.json - the account, the dealer, the serials and the ids left
//out). The application showed 25 % of salt that day, while salt_level_tenths was 20 - the salt comes from enriched_data
class SoftenerReadingTest {

    @Test
    void should_read_the_real_softener() {
        final SoftenerReading reading = SoftenerReading.of(SoftenerSamples.device());

        assertThat(reading).isEqualTo(new SoftenerReading(true, 102.0, 503.0, 2218.0, 425408.0, 0.0, 25.0, 73, 0, 156,
            12.0, 917, -70, new SoftenerReading.Alerts(false, false, false, false, false, false)));
    }

    //the properties the reading takes are in the answer of the real softener - a renamed one would be null silently
    @Test
    void should_find_every_property_it_reads_in_the_real_answer() {
        assertThat(SoftenerSamples.device().properties()).containsKeys(SoftenerReading.KNOWN.toArray(String[]::new));
    }

    //value is in gallons whatever the account shows: a volume the cloud did not convert to litres is left out
    @Test
    void should_leave_out_a_volume_not_in_litres() {
        final IquaDevice device = device(Map.of(
            "gallons_used_today", property("gallons_used_today", "27", "27", "gallons"),
            "current_water_flow_gpm", property("current_water_flow_gpm", "2", "7.6", "liters/min"),
            "avg_daily_use_gals", property("avg_daily_use_gals", "133", null, null)), null);

        final SoftenerReading reading = SoftenerReading.of(device);

        assertThat(reading.usedTodayLitres()).isNull();
        assertThat(reading.averageDailyLitres()).isNull();
        assertThat(reading.flowLitresPerMinute()).isEqualTo(7.6);
    }

    //missing is never off or zero
    @Test
    void should_leave_out_what_the_answer_has_not() {
        final SoftenerReading reading = SoftenerReading.of(device(null, null));

        assertThat(reading).isEqualTo(new SoftenerReading(null, null, null, null, null, null, null, null, null, null, null,
            null, null, new SoftenerReading.Alerts(null, null, null, null, null, null)));
    }

    @Test
    void should_take_is_online_of_the_device_without_the_property_and_read_numbers_and_flags_as_text() {
        final IquaDevice device = new IquaDevice("dev-1", null, null, null, null, false, Map.of(
            "total_regens", property("total_regens", "\"156\"", null, null),
            "low_salt_alert", property("low_salt_alert", "\"1\"", null, null),
            "resin_alert", property("resin_alert", "true", null, null),
            "error_code_alert", property("error_code_alert", "\"none\"", null, null),
            "hardness_grains", property("hardness_grains", "null", null, null)), null);

        final SoftenerReading reading = SoftenerReading.of(device);

        assertThat(reading.online()).isFalse();
        assertThat(reading.totalRegenerations()).isEqualTo(156);
        assertThat(reading.alerts().lowSalt()).isTrue();
        assertThat(reading.alerts().resin()).isTrue();
        assertThat(reading.alerts().error()).isNull();
        assertThat(reading.hardnessGpg()).isNull();
    }

    private static IquaDevice device(final Map<String, IquaProperty> properties, final JsonNode enriched) {
        return new IquaDevice("dev-1", null, null, null, null, null, properties, enriched);
    }

    private static IquaProperty property(final String name, final String value, final String converted,
                                         final String units) {
        return new IquaProperty(name, SoftenerSamples.JSON.readTree(value),
            converted == null ? null : SoftenerSamples.JSON.readTree(converted), units, null);
    }
}
