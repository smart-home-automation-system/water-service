package cloud.cholewa.water.alarm;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.database.repository.WaterAlertRepository;
import cloud.cholewa.water.presence.HouseOccupancy;
import cloud.cholewa.water.presence.PresenceClient;
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
import java.util.stream.Stream;

//The monitor of the alarms of the water valve (HAS-319) and of its flow (HAS-320): every minute the state in memory
//judged by AlarmRules - the pressure and the water temperature as far as they are fresh (water.valve.stale-after), the
//newest report for the silence - and by FlowRules - the runs of FlowTrack and whether the house is empty, read from
//presence-service in the pass (a failed read is unknown, never empty). The decision is held at once, the notifications
//sent, then the rows of water_alert stored; a row the database does not take is tried again with every pass. Nothing is
//judged while no valve is registered.
//While the rate of the flow is not fresh - the valve publishes it on a change only, and the read of every minute
//failed - the litres of the last whole minute of the counter are the flow, handed to FlowTrack at the pass.
//After a start nothing is decided before the open episodes are read from the database (tried again with every pass): a
//restart neither repeats an alert nor forgets a return. A notification neither fails nor stalls the pass: each is cut
//off after NOTICE_TIMEOUT and its failure logged with its text; what it would have changed waits for the next pass. The
//device and the broker go down together, so an alert that never got through is said by the return
@Slf4j
@Component
public class AlarmMonitor {

    static final Duration LIMIT = Duration.ofSeconds(50);
    //a confirm the broker gives later than this would be published again with the next pass. Six notices of a pass at
    //most - one of each alarm - would not all fit in the bound of the pass if each hung; the pass is then cut off and
    //what was not published is due with the next one
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);
    static final Duration PRESENCE_TIMEOUT = Duration.ofSeconds(10);

    private final ValveRegistry valveRegistry;
    private final ValveTracker valveTracker;
    private final FlowTrack flowTrack;
    private final PresenceClient presenceClient;
    private final WaterAlertRepository waterAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final AlarmProperties alarmProperties;
    private final FlowProperties flowProperties;
    private final ValveProperties valveProperties;
    private final Clock clock;
    private final AtomicReference<AlarmWatch> watch;
    private final AtomicReference<House> house = new AtomicReference<>(House.UNKNOWN);

    public AlarmMonitor(
        final ValveRegistry valveRegistry,
        final ValveTracker valveTracker,
        final FlowTrack flowTrack,
        final PresenceClient presenceClient,
        final WaterAlertRepository waterAlertRepository,
        final NotificationPublisher notificationPublisher,
        final AlarmProperties alarmProperties,
        final FlowProperties flowProperties,
        final ValveProperties valveProperties,
        final Clock clock
    ) {
        this.valveRegistry = valveRegistry;
        this.valveTracker = valveTracker;
        this.flowTrack = flowTrack;
        this.presenceClient = presenceClient;
        this.waterAlertRepository = waterAlertRepository;
        this.notificationPublisher = notificationPublisher;
        this.alarmProperties = alarmProperties;
        this.flowProperties = flowProperties;
        this.valveProperties = valveProperties;
        this.clock = clock;
        this.watch = new AtomicReference<>(AlarmWatch.notLoaded(now()));
    }

    AlarmWatch current() {
        return watch.get();
    }

    House house() {
        return house.get();
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
            .flatMap(held -> occupancy().flatMap(read -> {
                final LocalDateTime now = now();
                final House before = house.get();
                final House known = logged(before, before.read(read, now));
                house.set(known);
                final ValveState state = valveTracker.current();
                final ValveState fresh = state.freshOnly(now, valveProperties.staleAfter());
                final AlarmRules.Decision valve = AlarmRules.decide(held, readings(state, fresh), now, alarmProperties);
                final AlarmRules.Decision flow = flow(valve, fresh, known, now);
                watch.set(flow.next());
                return Flux.fromStream(Stream.concat(valve.notices().stream(), flow.notices().stream()))
                    .concatMap(this::send)
                    .then(Mono.defer(this::store));
            }));
    }

    //whether anybody is at home now; presence-service that cannot be read is not known - never empty
    private Mono<HouseOccupancy> occupancy() {
        return presenceClient.residents()
            .timeout(PRESENCE_TIMEOUT)
            .map(residents -> HouseOccupancy.judge(residents, now()))
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.debug("presence-service was not read: {}", failure.toString());
                return HouseOccupancy.UNKNOWN;
            }));
    }

    private static House logged(final House before, final House after) {
        if (after.occupancy() != before.occupancy()) {
            if (after.occupancy() == HouseOccupancy.UNKNOWN) {
                log.warn("Whether anybody is at home is not known now (was {}): no flow alarm of the empty house",
                    before.occupancy().value());
            } else {
                log.info("The house is {} (was {})", after.occupancy().value(), before.occupancy().value());
            }
        }
        return after;
    }

    private static AlarmRules.Readings readings(final ValveState state, final ValveState fresh) {
        return new AlarmRules.Readings(valueOf(fresh.pressure()), valueOf(fresh.temperature()),
            state.newestReport().orElse(null));
    }

    //the flow alarms after the alarms of the valve: a defect in them is logged and decides nothing, it never takes the
    //alarms of the mains, the silence and frost with it
    private AlarmRules.Decision flow(final AlarmRules.Decision valve, final ValveState fresh, final House known,
                                     final LocalDateTime now) {
        try {
            return FlowRules.decide(valve.next(), flowReadings(fresh, known, now), now, flowProperties, alarmProperties);
        } catch (final RuntimeException failure) {
            log.error("The flow alarms of the water valve were not judged in this pass", failure);
            return new AlarmRules.Decision(valve.next(), List.of());
        }
    }

    //the flow now: the rate while it is fresh, the litres of the last minute of the counter while only that is - handed
    //to the track, the rate reports it no longer - or not known
    private FlowRules.Readings flowReadings(final ValveState fresh, final House known, final LocalDateTime now) {
        final Double rate = valueOf(fresh.flowRate());
        final Double lastMinute = valueOf(fresh.lastMinute());
        if (rate == null && lastMinute != null) {
            flowTrack.flow(lastMinute, now);
        }
        //boxed on purpose: a double in one branch would unbox the null of the other
        final Double litres = rate != null ? Double.valueOf(ValveTracker.litres(rate)) : lastMinute;
        return new FlowRules.Readings(flowTrack.current(), litres != null, litres, known);
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
