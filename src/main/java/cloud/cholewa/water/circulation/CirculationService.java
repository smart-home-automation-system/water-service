package cloud.cholewa.water.circulation;

import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.water.circulation.CirculationRules.Stop;
import cloud.cholewa.water.client.WaterSensorClient;
import cloud.cholewa.water.config.WaterSensorConfig;
import cloud.cholewa.water.database.model.CirculationRunEntity;
import cloud.cholewa.water.database.repository.CirculationRunRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.CirculationReply;
import cloud.cholewa.water.model.CirculationRunReply;
import cloud.cholewa.water.service.HistoryRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

//The circulation pump of the hot water (HAS-404) - relay 1 of the Shelly Uni, switched through shelly-client 2.6.0.
//A start reads the Uni first: the tank below water.circulation.tank-min is 409 TANK_COLD, a Uni not read 502
//DEVICE_FAILED; then /relay/1?turn=on&timer=<max-run> is sent once - the timer of the device flips the relay back should
//the service never stop it, and replaces its auto_off of 60 s for that switch. A run already going is answered as it is,
//no second command; two starts at once share one (starting). A command the Uni did not answer may have been carried out:
//the run is held and watched all the same, the caller gets the 502 (the rule of the valve, HAS-321). The row of a run is
//written after the command, and its failure never refuses the start. The monitor ends a run (stop): the relay switched
//off when it is still on, then the row - one end only, the first. An automatic start (HAS-405) is the same start with its
//trigger and source; the conditions of it are the automation's. The end of every run is judged by RiseWatch
@Slf4j
@Service
@RequiredArgsConstructor
public class CirculationService {

    //the timeouts of shelly-client (3 s to connect, 5 s of silence) and a bound of the whole call
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    static final Duration STORE_TIMEOUT = Duration.ofSeconds(5);
    //a read older than that tells nothing of the pump: four reads of the 15 s missed
    static final Duration STALE_AFTER = Duration.ofMinutes(1);
    static final String MANUAL = "manual";
    //what closeOrphans keeps while this pod holds no run: a time no run started at (LocalDateTime.MIN the database refuses)
    static final LocalDateTime NO_RUN = LocalDateTime.of(2000, 1, 1, 0, 0);

    private static final Pattern SOURCE = Pattern.compile("[a-z][a-z-]{0,19}");

    private final WaterSensorClient waterSensorClient;
    private final WaterSensorConfig waterSensorConfig;
    private final ShellyGen1Client shellyGen1Client;
    private final CirculationPump pump;
    private final CirculationRunRepository repository;
    private final CirculationProperties properties;
    private final RiseWatch riseWatch;
    private final Clock clock;

    private final AtomicReference<Mono<CirculationRun>> starting = new AtomicReference<>();

    //source - who asks, a short lowercase word (dashboard, bruno)
    public Mono<CirculationRunReply> start(final String source) {
        return Mono.justOrEmpty(source)
            .filter(SOURCE.asMatchPredicate())
            .switchIfEmpty(Mono.error(() -> ValveException.invalidCommand(
                "The source is a lowercase word of at most 20 letters or dashes, not " + source)))
            .flatMap(valid -> Mono.justOrEmpty(pump.run()).switchIfEmpty(Mono.defer(() -> startOnce(MANUAL, valid))))
            .map(CirculationService::replyOf);
    }

    //an automatic start (HAS-405): trigger motion, light or window, source the room or the window; a run going on is
    //answered as it is. Refusals are those of a manual start (TANK_COLD, DEVICE_FAILED) - the automation logs them
    Mono<CirculationRun> startAutomatic(final String trigger, final String source) {
        return Mono.justOrEmpty(pump.run()).switchIfEmpty(Mono.defer(() -> startOnce(trigger, source)));
    }

    //the end of the newest run stopped by the temperature or the limit; empty for none
    Mono<LocalDateTime> lastSuccessfulEnd() {
        return repository.findLastSuccessfulEnd().timeout(STORE_TIMEOUT);
    }

    //a second start while one is under way gets the same one: never two commands, never two runs
    private Mono<CirculationRun> startOnce(final String trigger, final String source) {
        final Mono<CirculationRun> fresh = Mono.defer(() -> begin(trigger, source))
            .doFinally(signal -> starting.set(null))
            .cache();
        return Optional.ofNullable(starting.compareAndExchange(null, fresh)).orElse(fresh);
    }

    private Mono<CirculationRun> begin(final String trigger, final String source) {
        return Mono.fromSupplier(this::now)
            .flatMap(askedAt -> waterSensorClient.status()
                .timeout(READ_TIMEOUT)
                .map(status -> UniReading.of(status, askedAt))
                .onErrorMap(failure -> {
                    log.warn("The Shelly Uni of the hot water was not read before a start of the circulation by {}: {}",
                        source, failure.toString());
                    return ValveException.pumpUnread();
                }))
            .doOnNext(pump::read)
            .filter(reading -> reading.tank() != null)
            .switchIfEmpty(Mono.error(ValveException::pumpUnread))
            .flatMap(reading -> Mono.just(reading)
                .filter(warm -> warm.tank() >= properties.tankMin())
                .switchIfEmpty(Mono.error(() -> ValveException.tankCold(reading.tank(), properties.tankMin()))))
            .flatMap(reading -> send(reading, trigger, source));
    }

    private Mono<CirculationRun> send(final UniReading reading, final String trigger, final String source) {
        final int timer = (int) properties.maxRun().toSeconds();
        return Mono.fromSupplier(() -> new CirculationRun(null, now(), trigger, source, reading.returnTemperature(), true))
            .flatMap(run -> shellyGen1Client.relay(waterSensorConfig.address(), UniReading.PUMP_RELAY, true, timer)
                .timeout(COMMAND_TIMEOUT)
                .doOnSubscribe(subscription -> log.info("The circulation pump is started ({}, {}) for {} s at most, the "
                    + "tank at {} C, the return at {} C", trigger, source, timer, reading.tank(), reading.returnTemperature()))
                .then(Mono.defer(() -> started(run)))
                .onErrorResume(failure -> failed(run, failure)));
    }

    //refused: nothing was done. Not answered: it may have been carried out, so it is held and watched like a started one -
    //the monitor ends it when the relay is found off
    private Mono<CirculationRun> failed(final CirculationRun run, final Throwable failure) {
        final Integer status = failure instanceof ShellyCallException call ? call.httpStatus() : null;
        log.warn("The circulation pump did not take the start by {}: {}", run.source(), failure.toString());
        return Mono.just(run)
            .filter(unanswered -> status == null)
            .map(CirculationRun::unanswered)
            .flatMap(this::started)
            .then(Mono.error(ValveException.pumpFailed(status)));
    }

    private Mono<CirculationRun> started(final CirculationRun run) {
        pump.started(run);
        return repository.save(new CirculationRunEntity(null, run.startedAt(), null, run.trigger(), run.source(), null,
                decimal(run.returnAtStart()), null))
            .timeout(STORE_TIMEOUT)
            .map(saved -> run.withId(saved.id()))
            .doOnNext(pump::stored)
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("The run of the circulation pump started by {} could not be stored: {}", run.source(),
                    failure.toString());
                return run;
            }));
    }

    //Ends a run: the relay switched off first when the rules say it is still on - a failure of that leaves the timer of
    //the device to stop it, and the run ends as failure. returnNow - the return at the end, null when not read
    //A run ended already sends nothing and stores nothing. endedAt - when the pump stopped as far as is known: the read
    //that judged it, or the timer of the device for a Uni not heard
    Mono<Void> stop(final CirculationRun run, final Stop stop, final Double returnNow, final LocalDateTime endedAt) {
        return Mono.just(stop)
            .filter(going -> pump.isCurrent(run))
            .flatMap(going -> Mono.just(going)
                .filter(Stop::switchOff)
                .flatMap(this::switchOff)
                .defaultIfEmpty(going.reason()))
            .flatMap(reason -> end(run, reason, returnNow, endedAt));
    }

    private Mono<String> switchOff(final Stop off) {
        return shellyGen1Client.relay(waterSensorConfig.address(), UniReading.PUMP_RELAY, false)
            .timeout(COMMAND_TIMEOUT)
            .thenReturn(off.reason())
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("The circulation pump was not switched off ({}): {} - the timer of the device stops it",
                    off.reason(), failure.toString());
                return CirculationRules.FAILURE;
            }));
    }

    private Mono<Void> end(final CirculationRun run, final String reason, final Double returnNow,
                           final LocalDateTime endedAt) {
        return Mono.fromSupplier(() -> pump.ended(run))
            .filter(Boolean::booleanValue)
            .doOnNext(ended -> log.info("The run of the circulation pump started at {} ({}, {}) ended: {}, the return at {} C",
                run.startedAt(), run.trigger(), run.source(), reason, returnNow))
            .flatMap(ended -> store(run, reason, returnNow, endedAt)
                .then(riseWatch.ended(reason, run.returnAtStart(), returnNow)))
            .then();
    }

    private Mono<Void> store(final CirculationRun run, final String reason, final Double returnNow,
                             final LocalDateTime endedAt) {
        return Mono.justOrEmpty(run.id())
            .flatMap(id -> repository.end(id, endedAt, reason, decimal(returnNow))
                .timeout(STORE_TIMEOUT)
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The end of the run {} of the circulation pump could not be stored: {}", id, failure.toString()))))
            .then();
    }

    //Runs of an earlier pod that never ended - it stopped before they did (a deploy, a crash): ended as failure at the end
    //of their timer, which stopped the pump at the latest. The run this pod holds is left alone; a failure is a WARN, and
    //the monitor tries again with its next pass
    Mono<Boolean> closeOrphans() {
        final LocalDateTime keep = pump.run().map(CirculationRun::startedAt).orElse(NO_RUN);
        return repository.endOrphans(keep, properties.maxRun().toSeconds())
            .timeout(STORE_TIMEOUT)
            .doOnNext(ended -> log.info("Circulation runs of an earlier pod ended as failure: {}", ended))
            .thenReturn(true)
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("The circulation runs of an earlier pod could not be ended: {}", failure.toString());
                return false;
            }));
    }

    //the pump as last read, and the newest run of the table - a failed read of the table leaves lastRun out
    public Mono<CirculationReply> current() {
        return repository.findFirstByOrderByIdDesc()
            .timeout(STORE_TIMEOUT)
            .map(CirculationService::replyOf)
            .map(Optional::of)
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("The newest run of the circulation pump could not be read: {}", failure.toString());
                return Optional.empty();
            }))
            .defaultIfEmpty(Optional.empty())
            .map(lastRun -> replyOf(now(), lastRun.orElse(null)));
    }

    //the runs started in [from, to), newest first; at most 31 days - the rules of the temperature history
    public Mono<List<CirculationRunReply>> runs(final LocalDateTime from, final LocalDateTime to) {
        return Mono.justOrEmpty(HistoryRange.violation(from, to))
            .flatMap(Mono::<List<CirculationRunReply>>error)
            .switchIfEmpty(Mono.defer(() -> repository.findStartedIn(from, to)
                .map(CirculationService::replyOf)
                .collectList()));
    }

    private CirculationReply replyOf(final LocalDateTime now, final CirculationRunReply lastRun) {
        final Optional<UniReading> fresh = pump.reading().filter(reading -> !reading.readAt().isBefore(now.minus(STALE_AFTER)));
        final Boolean running = fresh.map(UniReading::pumpOn).orElse(null);
        final Optional<CirculationRun> run = pump.run();
        return new CirculationReply(
            running,
            Optional.ofNullable(running).filter(Boolean::booleanValue).flatMap(on -> pump.onSince()).map(CirculationService::second)
                .orElse(null),
            run.map(CirculationRun::trigger).orElse(null),
            run.map(CirculationRun::source).orElse(null),
            fresh.map(UniReading::returnTemperature).orElse(null),
            fresh.map(UniReading::tank).orElse(null),
            fresh.map(UniReading::readAt).map(CirculationService::second).orElse(null),
            lastRun
        );
    }

    private static CirculationRunReply replyOf(final CirculationRun run) {
        return new CirculationRunReply(second(run.startedAt()), null, run.trigger(), run.source(), null,
            decimal(run.returnAtStart()), null);
    }

    private static CirculationRunReply replyOf(final CirculationRunEntity row) {
        return new CirculationRunReply(second(row.startedAt()), Optional.ofNullable(row.endedAt())
            .map(CirculationService::second).orElse(null), row.trigger(), row.source(), row.stoppedBy(),
            row.returnAtStart(), row.returnAtEnd());
    }

    private static BigDecimal decimal(final Double value) {
        return Optional.ofNullable(value).map(BigDecimal::valueOf).map(exact -> exact.setScale(2, RoundingMode.HALF_UP))
            .orElse(null);
    }

    private static LocalDateTime second(final LocalDateTime at) {
        return at.truncatedTo(ChronoUnit.SECONDS);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
