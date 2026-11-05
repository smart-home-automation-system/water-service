package cloud.cholewa.water.valve;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.database.repository.WaterMeterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

//Stores what is known of the water valve a minute after the start and every minute after (HAS-316): the counter into
//water_meter_reading - a row when its total changed, otherwise the latest row checked again - and the valve into the one
//row of water_valve_status. Only fresh values (ValveProperties.staleAfter): a valve gone silent stores nothing, so the
//tables keep the time it was last heard. Two statements one after the other, each with its own error handling - a failed
//one is a WARN and leaves the other; bounded below the minute between two passes. "Now" is read inside the chain
@Slf4j
@Component
@RequiredArgsConstructor
public class ValveSnapshot {

    static final Duration LIMIT = Duration.ofSeconds(50);
    static final int TOTAL_SCALE = 9;

    private final ValveTracker valveTracker;
    private final ValveProperties valveProperties;
    private final WaterMeterRepository waterMeterRepository;
    private final Clock clock;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> snapshot() {
        return ScheduledPass.bounded("water valve snapshot", LIMIT, () -> Mono.fromSupplier(this::fresh)
            .flatMap(state -> counter(state).then(Mono.defer(() -> status(state)))));
    }

    private ValveState fresh() {
        return valveTracker.current().freshOnly(LocalDateTime.now(clock), valveProperties.staleAfter());
    }

    private Mono<Void> counter(final ValveState state) {
        return Mono.justOrEmpty(state.counterTotal())
            .flatMap(total -> waterMeterRepository.recordCounter(totalOf(total.value()), total.at())
                .filter(Boolean.TRUE::equals)
                .doOnNext(inserted -> log.debug("The counter of the water valve is at {} m3", total.value())))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() ->
                log.warn("The counter of the water valve could not be stored: {}", failure.toString())));
    }

    private Mono<Void> status(final ValveState state) {
        return Mono.justOrEmpty(state.newestReport())
            .filter(at -> state.hasStatus())
            .flatMap(at -> waterMeterRepository.recordStatus(valueOf(state.open()), valueOf(state.flowRate()),
                valueOf(state.pressure()), valueOf(state.temperature()), at))
            .onErrorResume(failure -> Mono.fromRunnable(() ->
                log.warn("The status of the water valve could not be stored: {}", failure.toString())));
    }

    //to the nine decimals of total_m3 (V3): the valve reports eleven (61.72186713199), and a total the column rounds
    //would never equal the stored one - a row every minute
    static BigDecimal totalOf(final double totalM3) {
        return BigDecimal.valueOf(totalM3).setScale(TOTAL_SCALE, RoundingMode.HALF_UP);
    }

    private static <T> T valueOf(final Reported<T> reported) {
        return reported == null ? null : reported.value();
    }
}
