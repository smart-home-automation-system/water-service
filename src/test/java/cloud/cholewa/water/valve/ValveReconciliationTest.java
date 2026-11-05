package cloud.cholewa.water.valve;

import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.NumberStatus;
import cloud.cholewa.shelly.model.ShellyComponent;
import cloud.cholewa.water.registry.ValveRegistry;
import cloud.cholewa.water.registry.WaterValve;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The read of every minute (HAS-316): what Shelly.GetComponents answered on the valve, invented values
class ValveReconciliationTest {

    private static final String ADDRESS = "192.0.2.15";

    private final ValveRegistry valveRegistry = mock(ValveRegistry.class);
    private final ShellyRpcClient shellyRpcClient = mock(ShellyRpcClient.class);
    private final ValveTracker tracker = new ValveTracker(Clock.fixed(Instant.parse("2026-11-04T18:00:00Z"),
        ZoneId.of("Europe/Warsaw")));
    private final ValveReconciliation sut = new ValveReconciliation(valveRegistry, shellyRpcClient, tracker,
        JsonMapper.builder().build());

    @BeforeEach
    void registerTheValve() {
        when(valveRegistry.current()).thenReturn(Optional.of(new WaterValve(139L, "neowatervalve-a1b2c3d4e5f6", ADDRESS)));
    }

    //the read alone keeps the state current when MQTT is silent
    @Test
    void should_take_every_reading_of_the_answer() {
        when(shellyRpcClient.dynamicComponents(ADDRESS)).thenReturn(Mono.just(List.of(
            component("boolean:200", Map.of("value", true, "source", "sys", "last_update_ts", 1791974665)),
            component("number:200", Map.of("value", 0, "source", "sys", "last_update_ts", 1793817630)),
            component("number:201", Map.of("value", 495.04377, "source", "sys", "last_update_ts", 1793818470)),
            component("number:202", Map.of("value", 12.9, "source", "sys", "last_update_ts", 1793817090)),
            component("object:200", Map.of("value", Map.of("counter", Map.of("total", 61.665843064, "minute_ts", 1793818560,
                "by_minute", List.of(0, 0, 0))), "source", "sys", "last_update_ts", 1793818663)))));

        sut.reconcile().as(StepVerifier::create).verifyComplete();

        final ValveState state = tracker.current();
        assertThat(state.open().value()).isTrue();
        assertThat(state.flowRate().value()).isZero();
        assertThat(state.pressure().value()).isEqualTo(495.04377);
        assertThat(state.temperature().value()).isEqualTo(12.9);
        assertThat(state.counterTotal().value()).isEqualTo(61.665843064);
    }

    //"{}" decodes into a model of nulls; a status of another kind is not its model; a component the house does not read
    //is left
    @Test
    void should_skip_a_component_that_is_not_a_reading() {
        when(shellyRpcClient.dynamicComponents(ADDRESS)).thenReturn(Mono.just(List.of(
            component("number:201", Map.of()),
            component("boolean:200", Map.of("value", Map.of("x", 1), "last_update_ts", 1)),
            component("number:203", Map.of("value", 1.0, "last_update_ts", 1)),
            component("text:200", Map.of("value", "x", "last_update_ts", 1)),
            component("number:x", Map.of("value", 1.0, "last_update_ts", 1)),
            component(null, Map.of("value", 1.0, "last_update_ts", 1)),
            component("number:202", Map.of("value", 12.9, "last_update_ts", 1)))));

        sut.reconcile().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current()).isEqualTo(ValveState.UNKNOWN.withTemperature(tracker.current().temperature()));
        assertThat(tracker.current().temperature().value()).isEqualTo(12.9);
    }

    //a failed read changes nothing and fails nothing - the values age until GET /valve leaves them out
    @Test
    void should_keep_the_state_when_the_valve_does_not_answer() {
        tracker.report(202, NumberStatus.builder().value(12.9).build());
        final ValveState before = tracker.current();
        when(shellyRpcClient.dynamicComponents(ADDRESS))
            .thenReturn(Mono.error(new ShellyCallException("Shelly.GetComponents on 192.0.2.15 failed")));

        sut.reconcile().as(StepVerifier::create).verifyComplete();
        sut.reconcile().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current()).isEqualTo(before);
    }

    //asked anew at every pass: the registry may have another valve by then
    @Test
    void should_read_nothing_while_no_valve_is_registered() {
        when(valveRegistry.current()).thenReturn(Optional.empty());

        final Mono<Void> pass = sut.reconcile();
        pass.as(StepVerifier::create).verifyComplete();

        verify(shellyRpcClient, never()).dynamicComponents(anyString());

        when(valveRegistry.current()).thenReturn(Optional.of(new WaterValve(139L, "neowatervalve-a1b2c3d4e5f6", ADDRESS)));
        when(shellyRpcClient.dynamicComponents(ADDRESS)).thenReturn(Mono.just(List.of()));

        pass.as(StepVerifier::create).verifyComplete();

        verify(shellyRpcClient).dynamicComponents(ADDRESS);
    }

    //a valve that takes the connection and never answers: the pass ends at its bound, below the minute between two
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(shellyRpcClient.dynamicComponents(ADDRESS)).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::reconcile)
            .expectSubscription()
            .expectNoEvent(ValveReconciliation.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    private static ShellyComponent component(final String key, final Map<String, Object> status) {
        return new ShellyComponent().key(key).status(new HashMap<>(status));
    }
}
