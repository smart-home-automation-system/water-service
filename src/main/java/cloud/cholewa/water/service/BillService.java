package cloud.cholewa.water.service;

import cloud.cholewa.water.database.model.ConsumptionBucket;
import cloud.cholewa.water.database.model.WaterBillEntity;
import cloud.cholewa.water.database.repository.WaterBillRepository;
import cloud.cholewa.water.database.repository.WaterMeterRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.BillReply;
import cloud.cholewa.water.model.BillRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

//The bills of the waterworks (HAS-318): kept as the owner enters them, each answered with what the house measured over
//its period - the consumption of HAS-317 in days, the imported days of the Shelly cloud included. A bill is read one
//after the other with its measurement, never two statements of the pool of 2 at once
@Service
@RequiredArgsConstructor
public class BillService {

    static final String DAY = "day";
    private static final int VOLUME_SCALE = 3;
    private static final int PERCENT_SCALE = 1;
    private static final int AMOUNT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final long NEW_BILL = 0;

    private final WaterBillRepository waterBillRepository;
    private final WaterMeterRepository waterMeterRepository;
    private final Clock clock;

    //newest first
    public Flux<BillReply> all() {
        return waterBillRepository.findAllByOrderByPeriodFromDesc().concatMap(this::replyOf);
    }

    public Mono<BillReply> one(final long id) {
        return found(id).flatMap(this::replyOf);
    }

    public Mono<BillReply> create(final BillRequest bill) {
        return checked(bill, NEW_BILL)
            .then(Mono.fromSupplier(this::now))
            .map(now -> entityOf(bill).withCreatedAt(now).withUpdatedAt(now))
            .flatMap(waterBillRepository::save)
            .flatMap(this::replyOf);
    }

    //the whole bill replaced; its id and the time it was entered stay
    public Mono<BillReply> update(final long id, final BillRequest bill) {
        return found(id)
            .flatMap(stored -> checked(bill, id).thenReturn(stored))
            .map(stored -> entityOf(bill).withId(id).withCreatedAt(stored.createdAt())
                .withUpdatedAt(now()))
            .flatMap(waterBillRepository::save)
            .flatMap(this::replyOf);
    }

    public Mono<Void> delete(final long id) {
        return found(id).flatMap(waterBillRepository::delete);
    }

    private Mono<WaterBillEntity> found(final long id) {
        return waterBillRepository.findById(id).switchIfEmpty(Mono.error(() -> ValveException.notFoundBill(id)));
    }

    //the rules first, then no other bill may share a day with it
    private Mono<Void> checked(final BillRequest bill, final long self) {
        return Mono.fromRunnable(() -> BillRules.checkBill(bill, LocalDate.now(clock)))
            .then(Mono.defer(() -> waterBillRepository.findOverlapping(bill.periodFrom(), bill.periodTo(), self)))
            .flatMap(other -> Mono.<Void>error(ValveException.invalidBill("The period overlaps the bill " + other)));
    }

    private Mono<BillReply> replyOf(final WaterBillEntity bill) {
        return waterMeterRepository.consumption(bill.periodFrom().atStartOfDay(),
                bill.periodTo().plusDays(1).atStartOfDay(), DAY)
            .collectList()
            .map(days -> replyOf(bill, measuredOf(bill, days)));
    }

    //the sum of the days of the period, or nothing when a day of it was not watched
    private static Optional<BigDecimal> measuredOf(final WaterBillEntity bill, final List<ConsumptionBucket> days) {
        final long length = ChronoUnit.DAYS.between(bill.periodFrom(), bill.periodTo()) + 1;
        return Optional.of(days)
            .filter(watched -> watched.size() == length)
            .map(watched -> watched.stream().map(ConsumptionBucket::volume).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(VOLUME_SCALE, RoundingMode.HALF_UP));
    }

    private static BillReply replyOf(final WaterBillEntity bill, final Optional<BigDecimal> measured) {
        final Optional<BigDecimal> difference = measured.map(volume -> volume.subtract(bill.billedVolume())
            .setScale(VOLUME_SCALE, RoundingMode.HALF_UP));
        return new BillReply(
            bill.id(),
            bill.periodFrom(),
            bill.periodTo(),
            bill.billedVolume(),
            bill.meterReading(),
            bill.amount(),
            bill.note(),
            measured.orElse(null),
            difference.orElse(null),
            difference.filter(any -> bill.billedVolume().signum() > 0)
                .map(volume -> volume.multiply(HUNDRED).divide(bill.billedVolume(), PERCENT_SCALE, RoundingMode.HALF_UP))
                .orElse(null),
            bill.createdAt(),
            bill.updatedAt());
    }

    //the time of the house to the second, as the column answers it back after a read
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    //in the scale of the columns of V4 (BillRules allows no more decimals), so the answer of a write is what a read
    //answers later - 11 is 11.000, not 11
    private static WaterBillEntity entityOf(final BillRequest bill) {
        return WaterBillEntity.builder()
            .periodFrom(bill.periodFrom())
            .periodTo(bill.periodTo())
            .billedVolume(bill.billedVolume().setScale(VOLUME_SCALE, RoundingMode.UNNECESSARY))
            .meterReading(Optional.ofNullable(bill.meterReading())
                .map(volume -> volume.setScale(VOLUME_SCALE, RoundingMode.UNNECESSARY)).orElse(null))
            .amount(Optional.ofNullable(bill.amount())
                .map(amount -> amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY)).orElse(null))
            .note(bill.note())
            .build();
    }
}
