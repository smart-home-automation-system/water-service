package cloud.cholewa.water.circulation;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.database.repository.CirculationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.Duration;

//Every night at 03:35 house time the runs of the circulation pump started more than KEPT (365 days) ago are deleted
//(HAS-404) - ten minutes after the readings of the counter, never two statements of the pool of 2 at once. A failed delete
//is a WARN, tried again the next night; bounded at LIMIT
@Slf4j
@Component
@RequiredArgsConstructor
public class CirculationRetention {

    static final Period KEPT = Period.ofDays(365);
    static final Duration LIMIT = Duration.ofMinutes(5);

    private final CirculationRunRepository repository;
    private final Clock clock;

    @Scheduled(cron = "0 35 3 * * *")
    Mono<Void> purge() {
        return ScheduledPass.bounded("circulation retention", LIMIT, () -> Mono.defer(this::purgeOnce));
    }

    Mono<Void> purgeOnce() {
        final LocalDateTime before = LocalDateTime.now(clock).minus(KEPT);
        return repository.deleteStartedBefore(before)
            .doOnNext(deleted -> log.info("Circulation runs started before {} deleted: {}", before, deleted))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() ->
                log.warn("The circulation runs started before {} could not be deleted: {}", before, failure.toString())));
    }
}
