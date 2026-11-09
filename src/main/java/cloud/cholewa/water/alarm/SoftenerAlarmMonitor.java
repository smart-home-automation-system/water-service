package cloud.cholewa.water.alarm;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.database.repository.WaterAlertRepository;
import cloud.cholewa.water.softener.SoftenerProperties;
import cloud.cholewa.water.softener.SoftenerTracker;
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
import java.util.concurrent.atomic.AtomicReference;

//The monitor of the alarms of the water softener (HAS-399): every minute the newest reading of SoftenerTracker judged by
//SoftenerAlarmRules - the flags the softener raises itself and whether the cloud hears it - the decision held at once, the
//notifications sent, then the rows of water_alert stored (the episodes of the valve are AlarmMonitor's; each monitor reads
//and writes only its own kinds). As in AlarmMonitor: nothing is decided before the open episodes are read (tried again with
//every pass), so a restart repeats nothing; a notification neither fails nor stalls the pass - each is cut off after
//NOTICE_TIMEOUT and its failure logged with its text, what it would have changed waits for the next pass; a row the
//database does not take is tried again with every pass. An alarm of the level error is an alert, one of the level warn
//(the salt, an excessive use, offline) is published at warn - the reminder of NotificationPublisher
@Slf4j
@Component
public class SoftenerAlarmMonitor {

    static final Duration LIMIT = Duration.ofSeconds(50);
    //seven notices of a pass at most would not all fit in the bound if each hung; the pass is then cut off and what was
    //not published is due with the next one
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);

    private final SoftenerTracker softenerTracker;
    private final WaterAlertRepository waterAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final SoftenerProperties softenerProperties;
    private final AlarmProperties alarmProperties;
    private final Clock clock;
    private final AtomicReference<SoftenerWatch> watch;

    public SoftenerAlarmMonitor(
        final SoftenerTracker softenerTracker,
        final WaterAlertRepository waterAlertRepository,
        final NotificationPublisher notificationPublisher,
        final SoftenerProperties softenerProperties,
        final AlarmProperties alarmProperties,
        final Clock clock
    ) {
        this.softenerTracker = softenerTracker;
        this.waterAlertRepository = waterAlertRepository;
        this.notificationPublisher = notificationPublisher;
        this.softenerProperties = softenerProperties;
        this.alarmProperties = alarmProperties;
        this.clock = clock;
        this.watch = new AtomicReference<>(SoftenerWatch.notLoaded(now()));
    }

    SoftenerWatch current() {
        return watch.get();
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("softener alarms", LIMIT, () -> load().then(Mono.defer(this::step)));
    }

    private Mono<Void> load() {
        return Mono.fromSupplier(watch::get)
            .filter(held -> !held.loaded())
            .flatMap(held -> waterAlertRepository.findByClosedAtIsNull()
                .collectList()
                .doOnNext(rows -> watch.updateAndGet(current -> current.loaded(open(rows))))
                .then()
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The open alarms of the softener could not be read, tried again with the next pass: {}",
                    failure.toString()))));
    }

    //the episodes of the softener among the open ones; those of the valve are AlarmMonitor's
    private static Map<SoftenerAlarm, WaterAlertEntity> open(final List<WaterAlertEntity> rows) {
        final Map<SoftenerAlarm, WaterAlertEntity> open = new EnumMap<>(SoftenerAlarm.class);
        rows.forEach(row -> SoftenerAlarm.of(row.kind()).ifPresent(alarm -> {
            log.info("The {} alarm of the softener since {} is still open", row.kind(), row.openedAt());
            open.put(alarm, row);
        }));
        return open;
    }

    private Mono<Void> step() {
        return Mono.fromSupplier(watch::get)
            .filter(SoftenerWatch::loaded)
            .flatMap(held -> {
                final SoftenerTracker.State state = softenerTracker.current();
                final SoftenerAlarmRules.Decision decision = SoftenerAlarmRules.decide(held,
                    new SoftenerAlarmRules.Readings(state.reading(), state.measuredAt()), now(),
                    softenerProperties.offlineAfter(), alarmProperties.reminderEvery());
                watch.set(decision.next());
                return Flux.fromIterable(decision.notices())
                    .concatMap(this::send)
                    .then(Mono.defer(this::store));
            });
    }

    //published, then what the notice changes is held - a notice that failed changes nothing and is due again
    private Mono<Void> send(final SoftenerAlarmRules.Notice notice) {
        final Mono<Void> publish = switch (notice.type()) {
            case ALERT -> notice.alarm().error()
                ? notificationPublisher.publishAlert(notice.text())
                : notificationPublisher.publishReminder(notice.text());
            case REMINDER -> notificationPublisher.publishReminder(notice.text());
            case INFO -> notificationPublisher.publishInfo(notice.text());
        };
        return publish
            .timeout(NOTICE_TIMEOUT)
            .then(Mono.fromRunnable(() -> {
                log.info("Softener alarm notification published: {}", notice.text());
                watch.updateAndGet(held -> held.with(notice.alarm(), notice.row()));
            }))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The softener alarm notification was not published, tried again with the next pass - {}: {}",
                failure.toString(), notice.text())));
    }

    private Mono<Void> store() {
        return Flux.fromIterable(watch.get().unsaved())
            .concatMap(unsaved -> waterAlertRepository.save(unsaved)
                .doOnNext(saved -> watch.updateAndGet(held -> held.stored(unsaved, saved)))
                .then()
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The {} alarm of the softener since {} could not be stored, tried again with the next pass: {}",
                    unsaved.kind(), unsaved.openedAt(), failure.toString()))))
            .then();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
