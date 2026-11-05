package cloud.cholewa.water.alarm;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.database.repository.WaterAlertRepository;
import cloud.cholewa.water.registry.ValveRegistry;
import cloud.cholewa.water.valve.Reported;
import cloud.cholewa.water.valve.ValveProperties;
import cloud.cholewa.water.valve.ValveState;
import cloud.cholewa.water.valve.ValveTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The monitor of the alarms of the water valve (HAS-319): every minute the state in memory judged by AlarmRules - the
//pressure and the water temperature as far as they are fresh (water.valve.stale-after), the newest report for the
//silence - the decision held at once, the notifications sent, then the rows of water_alert stored; a row the database
//does not take is tried again with every pass. Nothing is judged while no valve is registered.
//After a start nothing is decided before the open episodes are read from the database (tried again with every pass): a
//restart neither repeats an alert nor forgets a return. A notification neither fails nor stalls the pass: each is cut
//off after NOTICE_TIMEOUT and its failure logged with its text; what it would have changed waits for the next pass. The
//device and the broker go down together, so an alert that never got through is said by the return
@Slf4j
@Component
public class AlarmMonitor {

    static final Duration LIMIT = Duration.ofSeconds(50);
    //three notices of a pass at most fit in the bound of the pass
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);

    private final ValveRegistry valveRegistry;
    private final ValveTracker valveTracker;
    private final WaterAlertRepository waterAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final AlarmProperties alarmProperties;
    private final ValveProperties valveProperties;
    private final Clock clock;
    private final AtomicReference<AlarmWatch> watch;

    public AlarmMonitor(
        final ValveRegistry valveRegistry,
        final ValveTracker valveTracker,
        final WaterAlertRepository waterAlertRepository,
        final NotificationPublisher notificationPublisher,
        final AlarmProperties alarmProperties,
        final ValveProperties valveProperties,
        final Clock clock
    ) {
        this.valveRegistry = valveRegistry;
        this.valveTracker = valveTracker;
        this.waterAlertRepository = waterAlertRepository;
        this.notificationPublisher = notificationPublisher;
        this.alarmProperties = alarmProperties;
        this.valveProperties = valveProperties;
        this.clock = clock;
        this.watch = new AtomicReference<>(AlarmWatch.notLoaded(now()));
    }

    AlarmWatch current() {
        return watch.get();
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("water alarms", LIMIT, () -> load().then(Mono.defer(this::step)));
    }

    private Mono<Void> load() {
        return Mono.fromSupplier(watch::get)
            .filter(held -> !held.loaded())
            .flatMap(held -> waterAlertRepository.findByClosedAtIsNull()
                .collectList()
                .doOnNext(rows -> watch.updateAndGet(current -> current.loaded(open(rows))))
                .then()
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The open alarms of the water valve could not be read, tried again with the next pass: {}",
                    failure.toString()))));
    }

    private static Map<Alarm, WaterAlertEntity> open(final List<WaterAlertEntity> rows) {
        final Map<Alarm, WaterAlertEntity> open = new EnumMap<>(Alarm.class);
        rows.forEach(row -> Alarm.of(row.kind()).ifPresentOrElse(alarm -> {
            log.info("The {} alarm of the water valve since {} is still open", row.kind(), row.openedAt());
            open.put(alarm, row);
        }, () -> log.warn("An open alarm of the water valve this version does not know: {}", row.kind())));
        return open;
    }

    private Mono<Void> step() {
        return Mono.fromSupplier(watch::get)
            .filter(AlarmWatch::loaded)
            .filter(held -> valveRegistry.current().isPresent())
            .flatMap(held -> {
                final LocalDateTime now = now();
                final AlarmRules.Decision decision = AlarmRules.decide(held, readings(now), now, alarmProperties);
                watch.set(decision.next());
                return Flux.fromIterable(decision.notices())
                    .concatMap(this::send)
                    .then(Mono.defer(this::store));
            });
    }

    private AlarmRules.Readings readings(final LocalDateTime now) {
        final ValveState state = valveTracker.current();
        final ValveState fresh = state.freshOnly(now, valveProperties.staleAfter());
        return new AlarmRules.Readings(valueOf(fresh.pressure()), valueOf(fresh.temperature()),
            state.newestReport().orElse(null));
    }

    private static Double valueOf(final Reported<Double> reported) {
        return Optional.ofNullable(reported).map(Reported::value).orElse(null);
    }

    //published, then what the notice changes is held - a notice that failed changes nothing and is due again
    private Mono<Void> send(final AlarmRules.Notice notice) {
        final Mono<Void> publish = switch (notice.type()) {
            case ALERT -> notificationPublisher.publishAlert(notice.text());
            case REMINDER -> notificationPublisher.publishReminder(notice.text());
            case INFO -> notificationPublisher.publishInfo(notice.text());
        };
        return publish
            .timeout(NOTICE_TIMEOUT)
            .then(Mono.fromRunnable(() -> {
                log.info("Water alarm notification published: {}", notice.text());
                watch.updateAndGet(held -> held.with(notice.alarm(), notice.row()));
            }))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The water alarm notification was not published, tried again with the next pass - {}: {}",
                failure.toString(), notice.text())));
    }

    private Mono<Void> store() {
        return Flux.fromIterable(watch.get().unsaved())
            .concatMap(unsaved -> waterAlertRepository.save(unsaved)
                .doOnNext(saved -> watch.updateAndGet(held -> held.stored(unsaved, saved)))
                .then()
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The {} alarm of the water valve since {} could not be stored, tried again with the next pass: {}",
                    unsaved.kind(), unsaved.openedAt(), failure.toString()))))
            .then();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
