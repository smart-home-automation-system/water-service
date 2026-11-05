package cloud.cholewa.water.service;

import cloud.cholewa.shelly.model.BooleanStatus;
import cloud.cholewa.shelly.model.NumberStatus;
import cloud.cholewa.shelly.model.ObjectStatus;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import cloud.cholewa.water.model.CounterReply;
import cloud.cholewa.water.model.ValveReply;
import cloud.cholewa.water.registry.ValveRegistry;
import cloud.cholewa.water.registry.WaterValve;
import cloud.cholewa.water.valve.ValveProperties;
import cloud.cholewa.water.valve.ValveTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//GET /valve (HAS-316): the units of the house, the rounding, and what is left out
class ValveServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant REPORTED = Instant.parse("2026-11-04T18:51:20.654321Z");
    private static final LocalDateTime REPORTED_AT = LocalDateTime.of(2026, 11, 4, 19, 51, 20);

    private final ValveRegistry valveRegistry = mock(ValveRegistry.class);
    private final ValveTracker tracker = new ValveTracker(Clock.fixed(REPORTED, HOUSE));

    @BeforeEach
    void registerTheValve() {
        when(valveRegistry.isKnown()).thenReturn(true);
        when(valveRegistry.current()).thenReturn(Optional.of(new WaterValve(139L, "neowatervalve-a1b2c3d4e5f6", "192.0.2.15")));
    }

    @Test
    void should_answer_the_valve_in_the_units_of_the_house() {
        tracker.report(200, BooleanStatus.builder().value(true).build());
        tracker.report(200, NumberStatus.builder().value(0.012).build());
        tracker.report(201, NumberStatus.builder().value(495.04377).build());
        tracker.report(202, NumberStatus.builder().value(12.94).build());
        tracker.report(200, ObjectStatus.builder().value(Map.of("counter", Map.of("total", 61.66584306))).build());

        service(REPORTED.plusSeconds(60)).current().as(StepVerifier::create)
            .expectNext(new ValveReply("open", 12.0, 4.95, 12.9, new CounterReply(61.666, REPORTED_AT), REPORTED_AT))
            .verifyComplete();
    }

    @Test
    void should_answer_a_closed_valve_without_a_flow() {
        tracker.report(200, BooleanStatus.builder().value(false).build());
        tracker.report(200, NumberStatus.builder().value(0.0).build());

        service(REPORTED).current().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.state()).isEqualTo("closed");
                assertThat(reply.flowRate()).isZero();
            })
            .verifyComplete();
    }

    //a value not reported for five minutes is left out - missing is never off; with nothing fresh the answer is empty
    @Test
    void should_leave_out_what_is_stale_or_not_known() {
        tracker.report(201, NumberStatus.builder().build());
        tracker.report(202, NumberStatus.builder().value(12.9).build());

        service(REPORTED.plusSeconds(300)).current().as(StepVerifier::create)
            .expectNext(new ValveReply(null, null, null, 12.9, null, REPORTED_AT))
            .verifyComplete();
        service(REPORTED.plusSeconds(301)).current().as(StepVerifier::create)
            .expectNext(new ValveReply(null, null, null, null, null, null))
            .verifyComplete();
    }

    @Test
    void should_refuse_while_the_registry_is_not_known() {
        when(valveRegistry.isKnown()).thenReturn(false);

        service(REPORTED).current().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(ValveException.class,
                refusal -> assertThat(refusal.getError()).isEqualTo(WaterError.REGISTRY_UNAVAILABLE)))
            .verify();
    }

    @Test
    void should_refuse_while_no_valve_is_registered() {
        when(valveRegistry.current()).thenReturn(Optional.empty());

        service(REPORTED).current().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(ValveException.class,
                refusal -> assertThat(refusal.getError()).isEqualTo(WaterError.NOT_FOUND_VALVE)))
            .verify();
    }

    private ValveService service(final Instant now) {
        return new ValveService(valveRegistry, tracker, new ValveProperties(Duration.ofMinutes(5)), Clock.fixed(now, HOUSE));
    }
}
