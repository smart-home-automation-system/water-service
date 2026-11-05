package cloud.cholewa.water.valve;

import cloud.cholewa.commons.scheduling.ScheduledPass;
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
//(HAS-316), never the latest row. One statement: a row a change of the counter, a few hundred a day at most. "Now" is read
//inside the chain. A failed delete is a WARN, tried again the next night; bounded at LIMIT
@Slf4j
@Component
@RequiredArgsConstructor
public class MeterRetention {

    static final Duration LIMIT = Duration.ofMinutes(5);

    private final WaterMeterRepository waterMeterRepository;
    private final MeterRetentionProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 25 3 * * *")
    Mono<Void> purge() {
        return ScheduledPass.bounded("water meter retention", LIMIT, () -> Mono.defer(this::purgeOnce));
    }

    private Mono<Void> purgeOnce() {
        final LocalDateTime before = LocalDateTime.now(clock).minus(properties.retention());
        return waterMeterRepository.deleteReadingsBefore(before)
            .doOnNext(deleted -> log.info("Water meter readings measured before {} deleted: {}", before, deleted))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() ->
                log.warn("The water meter readings measured before {} could not be deleted: {}", before, failure.toString())));
    }
}
