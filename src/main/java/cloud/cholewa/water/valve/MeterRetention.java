package cloud.cholewa.water.valve;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.database.repository.SoftenerRepository;
import cloud.cholewa.water.database.repository.WaterMeterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

//Every night at 03:25 house time the readings of the counter older than water.meter.retention (730 days) are deleted
//(HAS-316), never the latest row; since HAS-398 the readings of the softener (never the latest) and its regenerations
//started before the same bound too - one statement a table, one after the other, each with its own WARN. One statement: a row a change of the counter, a few hundred a day at most. "Now" is read
//inside the chain. A failed delete is a WARN, tried again the next night; bounded at LIMIT
@Slf4j
@Component
@RequiredArgsConstructor
public class MeterRetention {

    static final Duration LIMIT = Duration.ofMinutes(5);

    private final WaterMeterRepository waterMeterRepository;
    private final SoftenerRepository softenerRepository;
    private final MeterRetentionProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 25 3 * * *")
    Mono<Void> purge() {
        return ScheduledPass.bounded("water meter retention", LIMIT, () -> Mono.defer(this::purgeOnce));
    }

    private Mono<Void> purgeOnce() {
        final LocalDateTime before = LocalDateTime.now(clock).minus(properties.retention());
        return purge("water meter readings", waterMeterRepository.deleteReadingsBefore(before), before)
            .then(Mono.defer(() -> purge("softener readings", softenerRepository.deleteReadingsBefore(before), before)))
            .then(Mono.defer(() -> purge("softener regenerations",
                softenerRepository.deleteRegenerationsBefore(before), before)));
    }

    private static Mono<Void> purge(final String what, final Mono<Long> delete, final LocalDateTime before) {
        return delete
            .doOnNext(deleted -> log.info("{} before {} deleted: {}", what, before, deleted))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() ->
                log.warn("The {} before {} could not be deleted: {}", what, before, failure.toString())));
    }
}
