package cloud.cholewa.water.circulation;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.client.WaterSensorClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

//Reads the Shelly Uni for the circulation pump (HAS-404): every IDLE_EVERY (15 s), every pass of 5 s while a run goes
//on, and judges the run after every read (CirculationRules) - the 3-minute read of the temperatures (WaterSensorCron)
//stays as it is. fixedDelay: a pass waits for the one before, bounded at LIMIT - a read cut off at READ_TIMEOUT and two
//commands of COMMAND_TIMEOUT fit. A silent Uni is one WARN when it starts and an INFO on the return, DEBUG between; "now"
//is read inside the chain
@Slf4j
@Component
@RequiredArgsConstructor
public class CirculationMonitor {

    static final Duration IDLE_EVERY = Duration.ofSeconds(15);
    //a pass that comes a little early still reads: the delay is counted from the end of the pass before
    static final Duration EARLY_BY = Duration.ofSeconds(1);
    static final Duration LIMIT = Duration.ofSeconds(45);

    private final WaterSensorClient waterSensorClient;
    private final CirculationPump pump;
    private final CirculationService circulationService;
    private final CirculationProperties properties;
    private final Clock clock;

    private final AtomicReference<LocalDateTime> lastAsked = new AtomicReference<>();
    private final AtomicBoolean silent = new AtomicBoolean(false);
    private final AtomicBoolean orphansClosed = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT15S")
    Mono<Void> watch() {
        return ScheduledPass.bounded("circulation pump", LIMIT, () -> Mono.defer(this::pass));
    }

    //the runs a pod before this one left open are ended at the first pass - once it worked
    Mono<Void> pass() {
        return Mono.fromSupplier(orphansClosed::get)
            .filter(closed -> !closed)
            .flatMap(open -> circulationService.closeOrphans())
            .doOnNext(orphansClosed::set)
            .then(Mono.fromSupplier(() -> LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)))
            .filter(this::due)
            .flatMap(this::read);
    }

    private boolean due(final LocalDateTime now) {
        final LocalDateTime before = lastAsked.get();
        return pump.run().isPresent() || before == null || !now.isBefore(before.plus(IDLE_EVERY).minus(EARLY_BY));
    }

    private Mono<Void> read(final LocalDateTime askedAt) {
        lastAsked.set(askedAt);
        return waterSensorClient.status()
            .timeout(CirculationService.READ_TIMEOUT)
            .map(status -> UniReading.of(status, askedAt))
            .doOnNext(reading -> {
                pump.read(reading);
                heard();
            })
            .onErrorResume(failure -> unheard(askedAt, failure).then(Mono.empty()))
            .flatMap(this::judge);
    }

    private Mono<Void> judge(final UniReading reading) {
        return Mono.justOrEmpty(pump.run())
            .flatMap(run -> Mono.justOrEmpty(CirculationRules.afterRead(run, reading, properties))
                .flatMap(stop -> circulationService.stop(run, stop, reading.returnTemperature(), reading.readAt())));
    }

    private Mono<Void> unheard(final LocalDateTime askedAt, final Throwable failure) {
        logSilence(failure);
        return Mono.justOrEmpty(pump.run())
            .flatMap(run -> Mono.justOrEmpty(CirculationRules.unheard(run, askedAt, properties))
                .flatMap(stop -> circulationService.stop(run, stop, null,
                    CirculationRules.unheardEnd(run, askedAt, properties))));
    }

    private void heard() {
        if (silent.compareAndSet(true, false)) {
            log.info("The Shelly Uni of the hot water answers the circulation monitor again");
        }
    }

    private void logSilence(final Throwable failure) {
        if (silent.compareAndSet(false, true)) {
            log.warn("The Shelly Uni of the hot water does not answer the circulation monitor: {}", failure.toString());
        } else {
            log.debug("The Shelly Uni of the hot water still does not answer: {}", failure.toString());
        }
    }
}
