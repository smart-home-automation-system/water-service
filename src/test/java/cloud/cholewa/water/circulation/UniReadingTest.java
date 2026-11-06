package cloud.cholewa.water.circulation;

import cloud.cholewa.shelly.model.ExternalTemperatureValue;
import cloud.cholewa.shelly.model.Relay;
import cloud.cholewa.shelly.model.ShellyUniStatusResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//The read of the Uni (HAS-404): relay 1 is the pump, sensor 0 the tank, sensor 1 the return; what is not sent is null
class UniReadingTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 19, 0);

    @Test
    void should_read_the_pump_the_tank_and_the_return() {
        final ShellyUniStatusResponse status = new ShellyUniStatusResponse()
            .relays(List.of(new Relay().ison(false), new Relay().ison(true)))
            .extTemperature(Map.of("0", new ExternalTemperatureValue().tC(46.5), "1", new ExternalTemperatureValue().tC(27.25)));

        assertThat(UniReading.of(status, AT)).isEqualTo(new UniReading(true, 46.5, 27.25, AT));
    }

    //one relay, no state, no sensors: nothing known - never off, never 0
    @Test
    void should_leave_out_what_the_uni_did_not_send() {
        assertThat(UniReading.of(new ShellyUniStatusResponse().relays(List.of(new Relay().ison(true))), AT))
            .isEqualTo(new UniReading(null, null, null, AT));
        assertThat(UniReading.of(new ShellyUniStatusResponse().relays(List.of(new Relay(), new Relay()))
                .extTemperature(Map.of("1", new ExternalTemperatureValue())), AT))
            .isEqualTo(new UniReading(null, null, null, AT));
        assertThat(UniReading.of(new ShellyUniStatusResponse().relays(null).extTemperature(null), AT))
            .isEqualTo(new UniReading(null, null, null, AT));
    }
}
