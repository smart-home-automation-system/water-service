package cloud.cholewa.water.service;

import cloud.cholewa.water.database.repository.SoftenerRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.SoftenerDayReply;
import cloud.cholewa.water.model.SoftenerHistoryReply;
import cloud.cholewa.water.model.SoftenerRegenerationReply;
import cloud.cholewa.water.softener.SoftenerMetric;
import cloud.cholewa.water.softener.SoftenerRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.BiFunction;

//What the house keeps of the softener (HAS-398): the history of one metric, the litres through it per day and its
//regenerations, read from softener_reading and softener_regeneration. The parameters come as text and are checked by
//SoftenerRange - INVALID_RANGE, UNKNOWN_METRIC with their reasons. Each read is one statement, the whole of it cut off
//after QUERY_TIMEOUT (R2DBC has no statement timeout, and a hung read would hold one of the two connections of the pool),
//and the reads go one at a time (OneAtATime) - two ranges of a year at once would leave no connection for the rest. The days and the
//regenerations default to the last DEFAULT_DAYS days up to today, both bounds included; "today" is the day of the house,
//read at every call
@Service
@RequiredArgsConstructor
public class SoftenerHistoryService {

    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_DAYS = 30;

    private final SoftenerRepository softenerRepository;
    private final Clock clock;
    private final OneAtATime oneAtATime = new OneAtATime();

    public Mono<SoftenerHistoryReply> history(final String metricName, final String fromText, final String toText) {
        return Mono.fromSupplier(() -> {
                final SoftenerMetric metric = SoftenerMetric.ofApiName(metricName)
                    .orElseThrow(() -> ValveException.unknownMetric(metricName));
                final LocalDateTime from = SoftenerRange.dateTime("from", fromText);
                final LocalDateTime to = SoftenerRange.dateTime("to", toText);
                SoftenerRange.check(from, to);
                return new Query(metric, from, to, SoftenerRange.bucket(from, to).toSeconds());
            })
            .flatMap(query -> oneAtATime.run(softenerRepository.history(query.metric(), query.from(), query.to(),
                        query.bucketSeconds())
                    .collectList()
                    .timeout(QUERY_TIMEOUT))
                .map(points -> new SoftenerHistoryReply(query.metric().apiName(), query.from(), query.to(),
                    query.bucketSeconds(), points)));
    }

    private record Query(SoftenerMetric metric, LocalDateTime from, LocalDateTime to, long bucketSeconds) {
    }

    //the days from "from" to "to", both included, oldest first
    public Flux<SoftenerDayReply> days(final String fromText, final String toText) {
        return inDays(fromText, toText, (from, to) -> softenerRepository.days(from, to.plusDays(1))
            .map(day -> new SoftenerDayReply(day.day(), day.litres())));
    }

    //the regenerations started from "from" to "to", both days included, newest first
    public Flux<SoftenerRegenerationReply> regenerations(final String fromText, final String toText) {
        return inDays(fromText, toText, (from, to) -> softenerRepository.regenerations(from.atStartOfDay(),
                to.plusDays(1).atStartOfDay())
            .map(stored -> new SoftenerRegenerationReply(stored.startedAt(), stored.endedAt(), stored.deviceStartedAt(),
                stored.endedAt() == null)));
    }

    //the range of days asked for, checked, read by "read" - both days included
    private <T> Flux<T> inDays(final String fromText, final String toText,
                             final BiFunction<LocalDate, LocalDate, Flux<T>> read) {
        return Mono.fromSupplier(() -> {
                final LocalDate today = LocalDate.now(clock);
                final LocalDate to = SoftenerRange.day("to", toText, today);
                final LocalDate from = SoftenerRange.day("from", fromText, to.minusDays(DEFAULT_DAYS - 1L));
                SoftenerRange.check(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
                return new Days(from, to);
            })
            .flatMap(range -> oneAtATime.run(read.apply(range.from(), range.to()).collectList().timeout(QUERY_TIMEOUT)))
            .flatMapIterable(list -> list);
    }

    private record Days(LocalDate from, LocalDate to) {
    }
}
