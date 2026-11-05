package cloud.cholewa.water.service;

import cloud.cholewa.water.database.model.ConsumptionBucket;
import cloud.cholewa.water.database.repository.WaterMeterRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.ConsumptionReply;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

//The consumption of water by day, week, month and year (HAS-317), from the readings of the counter (water_meter_reading).
//The range and the one before it are read one after the other - each a statement of the pool of 2, never both at once.
//Today is read at every call, by the clock of the house
@Service
@RequiredArgsConstructor
public class ConsumptionService {

    static final String UNIT = "m3";
    private static final int VOLUME_SCALE = 3;
    static final int FIRST_YEAR = 2000;
    static final int LAST_YEAR = 2999;

    private final WaterMeterRepository waterMeterRepository;
    private final Clock clock;

    //range: day, week, month or year; at: a day of the range as YYYY-MM-DD, today when left out
    public Mono<ConsumptionReply> consumption(final String range, final String at) {
        return Mono.fromCallable(() -> periodOf(ConsumptionRange.of(range), dayOf(at)))
            .flatMap(period -> waterMeterRepository.consumption(period.from(), period.to(), period.range().getUnit())
                .collectList()
                .zipWhen(buckets -> waterMeterRepository.consumption(period.previousFrom(), period.from(),
                    period.range().getUnit()).collectList())
                .map(both -> replyOf(period, both.getT1(), both.getT2())));
    }

    private LocalDate dayOf(final String at) {
        return Optional.ofNullable(at)
            .map(ConsumptionService::parsed)
            .orElseGet(() -> LocalDate.now(clock));
    }

    //a day of the years the house can have readings of: year 0 or 9999 would put the range or the one before it outside
    //what the driver writes, a 500 instead of a refusal
    private static LocalDate parsed(final String at) {
        final LocalDate day;
        try {
            day = LocalDate.parse(at);
        } catch (DateTimeParseException exception) {
            throw ValveException.invalidRange("The date must be YYYY-MM-DD, not '%s'".formatted(at));
        }
        if (day.getYear() < FIRST_YEAR || day.getYear() > LAST_YEAR) {
            throw ValveException.invalidRange("The date must be within %d - %d, not '%s'".formatted(FIRST_YEAR, LAST_YEAR, at));
        }
        return day;
    }

    private static Period periodOf(final ConsumptionRange range, final LocalDate at) {
        final LocalDateTime from = range.start(at);
        return new Period(range, from, range.end(from), range.previous(from));
    }

    private static ConsumptionReply replyOf(final Period period, final List<ConsumptionBucket> buckets,
                                            final List<ConsumptionBucket> previous) {
        return new ConsumptionReply(
            period.range().value(),
            period.from(),
            period.to(),
            UNIT,
            totalOf(buckets),
            totalOf(previous),
            buckets.stream().map(bucket -> new ConsumptionReply.Bucket(bucket.at(), rounded(bucket.volume()))).toList());
    }

    //the sum of the buckets before they are rounded; none - the counter was not watched - is not known, not 0
    private static Double totalOf(final List<ConsumptionBucket> buckets) {
        return buckets.stream()
            .map(ConsumptionBucket::volume)
            .reduce(BigDecimal::add)
            .map(ConsumptionService::rounded)
            .orElse(null);
    }

    private static double rounded(final BigDecimal volume) {
        return volume.setScale(VOLUME_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    private record Period(ConsumptionRange range, LocalDateTime from, LocalDateTime to, LocalDateTime previousFrom) {
    }
}
