package cloud.cholewa.water.softener;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.database.repository.SoftenerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

//Reads the regenerations of the softener from the iQua cloud once an hour (HAS-398) into softener_regeneration: page 1 of
//the regeneration events (PER_PAGE a page, the newest first - read on the real account 2026-11-09, which keeps 51 events
//since July while the softener counts 156; start_time / end_time in UTC, device_start_time with the offset of the softener), then the next pages until one that holds a regeneration
//already stored - so the first pass reads the whole history, every later one a single page (about 24 calls a day, counted
//in the budget of the cloud). A new event is inserted, a stored one that went on is completed once the cloud has its end.
//An event without an id or a start is skipped. Nothing is read before SoftenerReader found the softener; a failure is one
//WARN an hour per kind (FailureLog), the next pass tries again. Bounded at LIMIT, at most MAX_PAGES pages a pass
@Slf4j
@Component
public class SoftenerRegenerations {

    static final Duration LIMIT = Duration.ofMinutes(2);
    static final int PER_PAGE = 20;
    static final int MAX_PAGES = 20;
    private static final String WHAT = "regenerations of the iQua cloud";

    private final SoftenerReader softenerReader;
    private final IquaClient iquaClient;
    private final SoftenerRepository softenerRepository;
    private final Clock clock;
    private final FailureLog failureLog;

    public SoftenerRegenerations(
        final SoftenerReader softenerReader,
        final IquaClient iquaClient,
        final SoftenerRepository softenerRepository,
        final Clock clock
    ) {
        this.softenerReader = softenerReader;
        this.iquaClient = iquaClient;
        this.softenerRepository = softenerRepository;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    Mono<Void> read() {
        return ScheduledPass.bounded("softener regenerations", LIMIT, () -> Mono.defer(() ->
                Mono.justOrEmpty(softenerReader.deviceId()))
            .flatMap(deviceId -> page(deviceId, 1))
            .doOnSuccess(done -> failureLog.succeeded(WHAT))
            .onErrorResume(failure -> Mono.fromRunnable(() -> failureLog.failed(WHAT, failure))));
    }

    private Mono<Void> page(final String deviceId, final int page) {
        return iquaClient.regenerations(deviceId, page, PER_PAGE)
            .flatMap(answer -> store(answer.events())
                .filter(known -> !known
                    && answer.events().size() == PER_PAGE
                    && (long) page * PER_PAGE < answer.total()
                    && page < MAX_PAGES)
                .flatMap(next -> page(deviceId, page + 1)));
    }

    //stores the events of a page one after the other; answers whether the page held a regeneration already stored
    private Mono<Boolean> store(final List<IquaRegeneration> events) {
        final List<Event> parsed = events.stream().map(this::parsed).flatMap(Optional::stream).toList();
        return Mono.just(parsed)
            .filter(list -> !list.isEmpty())
            .flatMap(list -> softenerRepository.knownRegenerations(list.stream().map(Event::cloudId).toList()))
            .defaultIfEmpty(Map.of())
            .flatMap(known -> Flux.fromIterable(parsed)
                //an event stored as ended changes no more
                .filter(event -> !Boolean.TRUE.equals(known.get(event.cloudId())))
                .concatMap(event -> softenerRepository.recordRegeneration(event.cloudId(), event.startedAt(),
                        event.endedAt(), event.deviceStartedAt())
                    .filter(Boolean.TRUE::equals)
                    .doOnNext(changed -> log.info("A regeneration of the softener stored: started {}, ended {}",
                        event.startedAt(), event.endedAt())))
                .then(Mono.just(!known.isEmpty())));
    }

    private record Event(String cloudId, LocalDateTime startedAt, LocalDateTime endedAt, LocalDateTime deviceStartedAt) {
    }

    private Optional<Event> parsed(final IquaRegeneration event) {
        return Optional.ofNullable(event.id())
            .filter(id -> !id.isBlank())
            .flatMap(id -> houseTime(event.startTime())
                .map(start -> new Event(id, start, houseTime(event.endTime()).orElse(null),
                    deviceTime(event.deviceStartTime()).orElse(null))));
    }

    //an instant of the cloud as the time of the house; a date-time without an offset as it is
    Optional<LocalDateTime> houseTime(final String text) {
        return Optional.ofNullable(text).filter(value -> !value.isBlank()).flatMap(value -> {
            try {
                return Optional.of(OffsetDateTime.parse(value.trim()).atZoneSameInstant(clock.getZone()).toLocalDateTime());
            } catch (final DateTimeParseException noOffset) {
                return local(value);
            }
        });
    }

    //the clock of the softener as it is written, its offset left aside
    static Optional<LocalDateTime> deviceTime(final String text) {
        return Optional.ofNullable(text).filter(value -> !value.isBlank()).flatMap(value -> {
            try {
                return Optional.of(OffsetDateTime.parse(value.trim()).toLocalDateTime());
            } catch (final DateTimeParseException noOffset) {
                return local(value);
            }
        });
    }

    private static Optional<LocalDateTime> local(final String value) {
        try {
            return Optional.of(LocalDateTime.parse(value.trim()));
        } catch (final DateTimeParseException notDateTime) {
            return Optional.empty();
        }
    }
}
