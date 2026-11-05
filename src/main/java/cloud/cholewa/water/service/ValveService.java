package cloud.cholewa.water.service;

import cloud.cholewa.water.command.ValveCommands;
import cloud.cholewa.water.model.CounterReply;
import cloud.cholewa.water.model.ValveReply;
import cloud.cholewa.water.registry.ValveRegistry;
import cloud.cholewa.water.valve.Reported;
import cloud.cholewa.water.valve.ValveProperties;
import cloud.cholewa.water.valve.ValveState;
import cloud.cholewa.water.valve.ValveTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

//The water valve now, from memory (HAS-316): REGISTRY_UNAVAILABLE until the registry was read once, NOT_FOUND_VALVE while
//it has no valve, otherwise the fresh values in the units of the house - l/min, bar, m3. A value not reported within
//water.valve.stale-after is left out; with nothing fresh the answer is {} - no reading. The conversions are a decimal
//shift and a rounding of BigDecimal, not a multiplication of doubles (0.012 * 1000 is 12.000000000000002). Whether the
//last command was confirmed comes with it (HAS-321)
@Service
@RequiredArgsConstructor
public class ValveService {

    private static final int LITRES_PER_M3 = 3;
    private static final int KPA_PER_BAR = 2;

    private final ValveRegistry valveRegistry;
    private final ValveTracker valveTracker;
    private final ValveCommands valveCommands;
    private final ValveProperties valveProperties;
    private final Clock clock;

    public Mono<ValveReply> current() {
        return ValveRegistry.known(valveRegistry)
            .map(valve -> replyOf(valveTracker.current().freshOnly(LocalDateTime.now(clock), valveProperties.staleAfter()),
                valveCommands.lastConfirmed()));
    }

    static ValveReply replyOf(final ValveState state, final Boolean lastCommandConfirmed) {
        return new ValveReply(
            state.open() == null ? null : stateOf(state.open().value()),
            state.flowRate() == null ? null : shifted(state.flowRate().value(), LITRES_PER_M3, 1),
            state.pressure() == null ? null : shifted(state.pressure().value(), -KPA_PER_BAR, 2),
            state.temperature() == null ? null : shifted(state.temperature().value(), 0, 1),
            state.counterTotal() == null ? null : counterOf(state.counterTotal()),
            state.newestReport().map(ValveService::toSecond).orElse(null),
            lastCommandConfirmed);
    }

    private static String stateOf(final boolean open) {
        return open ? "open" : "closed";
    }

    private static CounterReply counterOf(final Reported<Double> total) {
        return new CounterReply(shifted(total.value(), 0, 3), toSecond(total.at()));
    }

    private static Double shifted(final double value, final int places, final int scale) {
        return BigDecimal.valueOf(value).movePointRight(places).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static LocalDateTime toSecond(final LocalDateTime at) {
        return at.truncatedTo(ChronoUnit.SECONDS);
    }
}
