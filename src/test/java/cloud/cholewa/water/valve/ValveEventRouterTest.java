package cloud.cholewa.water.valve;

import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.water.registry.ValveRegistry;
import cloud.cholewa.water.registry.WaterValve;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//The messages of the valve out of every device event of the queue (HAS-316), on the routing keys the broker showed for
//the valve (devices.md of deployment-tools) with an invented device id
class ValveEventRouterTest {

    private static final String VALVE = "neowatervalve-a1b2c3d4e5f6";

    private final ValveRegistry valveRegistry = mock(ValveRegistry.class);
    private final ValveTracker tracker = new ValveTracker(Clock.fixed(Instant.parse("2026-11-04T18:00:00Z"),
        ZoneId.of("Europe/Warsaw")));
    private final ValveEventRouter sut = new ValveEventRouter(valveRegistry,
        new ShellyMqttDecoder(JsonMapper.builder().build()), tracker);

    @BeforeEach
    void registerTheValve() {
        when(valveRegistry.current()).thenReturn(Optional.of(new WaterValve(139L, VALVE, "192.0.2.15")));
    }

    @Test
    void should_take_the_readings_of_the_valve() {
        route(VALVE + ".status.number:201", "{\"value\":490.90691,\"source\":\"sys\",\"last_update_ts\":1793818170}");
        route(VALVE + ".status.boolean:200", "{\"value\":true,\"source\":\"sys\",\"last_update_ts\":1791974665}");
        route(VALVE + ".status.object:200", "{\"value\":{\"counter\":{\"total\":61.665843064,\"minute_ts\":1793818140,"
            + "\"by_minute\":[0,0,0]}},\"source\":\"sys\",\"last_update_ts\":1793818242}");

        assertThat(tracker.current().pressure().value()).isEqualTo(490.90691);
        assertThat(tracker.current().open().value()).isTrue();
        assertThat(tracker.current().counterTotal().value()).isEqualTo(61.665843064);

        route(VALVE + ".online", "false");

        assertThat(tracker.current().online()).isFalse();
        assertThat(tracker.current().pressure()).isNull();
    }

    //the device id of a topic is in the case of the device; the registry may hold another
    @Test
    void should_find_the_valve_whatever_the_case_of_its_id() {
        route(VALVE.toUpperCase() + ".status.number:202", "{\"value\":12.9,\"last_update_ts\":1}");

        assertThat(tracker.current().temperature().value()).isEqualTo(12.9);
    }

    //the body is never read: one that is no JSON would otherwise be a WARN
    @ParameterizedTest
    @ValueSource(strings = {"shellyplus2pm-a1b2c3d4e5f6.status.number:200", "shellyplus2pm-a1b2c3d4e5f6.online",
        VALVE + ".status.sys", VALVE + ".status.service:0", VALVE + ".status.number:203", VALVE + ".status.wifi",
        "eaton.blinds.45", ""})
    void should_leave_what_is_not_a_reading_of_the_valve(final String key) {
        route(key, "not json at all");

        assertThat(tracker.current()).isEqualTo(ValveState.UNKNOWN);
    }

    @Test
    void should_leave_everything_while_no_valve_is_registered() {
        when(valveRegistry.current()).thenReturn(Optional.empty());

        route(VALVE + ".status.number:201", "{\"value\":490.9,\"last_update_ts\":1}");

        assertThat(tracker.current()).isEqualTo(ValveState.UNKNOWN);
    }

    //a WARN, never an error out of the listener - an error would be redelivered at once
    @ParameterizedTest
    @ValueSource(strings = {"not json", "{}", "{\"value\":true}", "[]"})
    void should_survive_a_body_that_is_not_the_status_of_its_topic(final String body) {
        sut.route(VALVE + ".status.number:201", body.getBytes(StandardCharsets.UTF_8))
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(tracker.current()).isEqualTo(ValveState.UNKNOWN);
    }

    @Test
    void should_complete_for_a_message_without_a_routing_key() {
        sut.route(null, new byte[0]).as(StepVerifier::create).verifyComplete();
    }

    private void route(final String key, final String body) {
        sut.route(key, body.getBytes(StandardCharsets.UTF_8)).as(StepVerifier::create).verifyComplete();
    }
}
