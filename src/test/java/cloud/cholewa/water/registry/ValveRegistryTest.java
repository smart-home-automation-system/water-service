package cloud.cholewa.water.registry;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.shelly.model.NumberStatus;
import cloud.cholewa.water.valve.ValveTracker;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//The water valve of the device registry (HAS-316)
class ValveRegistryTest {

    private final DeviceRegistryClient client = mock(DeviceRegistryClient.class);
    private final ValveTracker tracker = new ValveTracker(Clock.fixed(Instant.parse("2026-11-04T18:00:00Z"),
        ZoneId.of("Europe/Warsaw")));
    private final ValveRegistry sut = new ValveRegistry(client, tracker);

    @Test
    void should_know_nothing_before_the_first_read() {
        assertThat(sut.isKnown()).isFalse();
        assertThat(sut.current()).isEmpty();
    }

    @Test
    void should_read_the_valve_of_the_registry() {
        when(client.getWaterValves()).thenReturn(Mono.just(List.of(valve(139L, "neowatervalve-a1b2c3d4e5f6"))));

        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.isKnown()).isTrue();
        assertThat(sut.current()).contains(new WaterValve(139L, "neowatervalve-a1b2c3d4e5f6", "192.0.2.15"));
    }

    //none registered is nothing watched - and known: GET /valve answers NOT_FOUND_VALVE, not REGISTRY_UNAVAILABLE
    @Test
    void should_know_that_there_is_no_valve() {
        when(client.getWaterValves()).thenReturn(Mono.just(List.of()));

        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.isKnown()).isTrue();
        assertThat(sut.current()).isEmpty();
    }

    @Test
    void should_keep_the_valve_when_a_read_fails() {
        when(client.getWaterValves()).thenReturn(Mono.just(List.of(valve(139L, "neowatervalve-a1b2c3d4e5f6"))));
        sut.refresh().as(StepVerifier::create).verifyComplete();

        when(client.getWaterValves()).thenReturn(Mono.error(new RegistryCallException("database-service answered 500")));
        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.current()).map(WaterValve::id).contains(139L);
    }

    //a valve without the binding it is read by is skipped; of more valves the one with the lowest id is read
    @Test
    void should_read_the_first_valve_it_can() {
        final SmartDevice withoutAddress = valve(100L, "neowatervalve-000000000001");
        withoutAddress.getShelly().setAddress(" ");
        final SmartDevice withoutBinding = SmartDevice.builder().id(101L).kind(DeviceKind.WATER_VALVE).build();
        when(client.getWaterValves()).thenReturn(Mono.just(List.of(valve(150L, "neowatervalve-000000000003"),
            withoutAddress, withoutBinding, valve(140L, "neowatervalve-000000000002"))));

        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.current()).map(WaterValve::id).contains(140L);
    }

    //what was known is of the valve before: another one starts from nothing, the same one keeps it
    @Test
    void should_forget_the_state_of_a_valve_that_is_replaced() {
        when(client.getWaterValves()).thenReturn(Mono.just(List.of(valve(139L, "neowatervalve-a1b2c3d4e5f6"))));
        sut.refresh().as(StepVerifier::create).verifyComplete();
        tracker.report(202, NumberStatus.builder().value(12.9).build());

        sut.refresh().as(StepVerifier::create).verifyComplete();
        assertThat(tracker.current().temperature()).isNotNull();

        when(client.getWaterValves()).thenReturn(Mono.just(List.of(valve(139L, "neowatervalve-ffffffffffff"))));
        sut.refresh().as(StepVerifier::create).verifyComplete();
        assertThat(tracker.current().temperature()).isNull();
    }

    //a database-service that never answers: the pass ends at its bound, the valve before kept
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(client.getWaterValves()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::refresh)
            .expectSubscription()
            .expectNoEvent(ValveRegistry.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();

        assertThat(sut.isKnown()).isFalse();
    }

    private static SmartDevice valve(final long id, final String deviceId) {
        return SmartDevice.builder().id(id).name("Water valve").kind(DeviceKind.WATER_VALVE).enabled(true)
            .shelly(ShellyBinding.builder().address("192.0.2.15").generation(3).deviceId(deviceId).channel(0).build())
            .build();
    }
}
