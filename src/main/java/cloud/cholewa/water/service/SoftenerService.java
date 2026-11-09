package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.SoftenerReply;
import cloud.cholewa.water.softener.SoftenerProperties;
import cloud.cholewa.water.softener.SoftenerReading;
import cloud.cholewa.water.softener.SoftenerTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

//The softener now, from memory (HAS-397): 503 SOFTENER_UNAVAILABLE until the first reading, then the last reading with how
//old it is - stale past two intervals of the read, judged on the instant (the house time goes back an hour in October).
//The days of the reading become dates on the day it was read: the last regeneration that many days before, the salt
//running out that many days after. The hardness in mg/l of CaCO3 is the grains per gallon x 17.12
@Service
@RequiredArgsConstructor
public class SoftenerService {

    static final BigDecimal MG_CACO3_PER_GPG = new BigDecimal("17.12");

    private final SoftenerTracker softenerTracker;
    private final SoftenerProperties softenerProperties;
    private final Clock clock;

    public Mono<SoftenerReply> current() {
        return Mono.fromSupplier(softenerTracker::current)
            .filter(state -> state.reading() != null)
            .map(state -> replyOf(state, clock.instant()))
            .switchIfEmpty(Mono.error(ValveException::softenerUnavailable));
    }

    SoftenerReply replyOf(final SoftenerTracker.State state, final Instant now) {
        final SoftenerReading reading = state.reading();
        final LocalDate day = state.measuredAt().toLocalDate();
        final SoftenerReading.Alerts alerts = reading.alerts();
        return new SoftenerReply(
            state.reachable(),
            reading.online(),
            state.measuredAt(),
            state.readAt().isBefore(now.minus(softenerProperties.cloud().interval().multipliedBy(2))),
            new SoftenerReply.Water(reading.usedTodayLitres(), reading.averageDailyLitres(),
                reading.treatedAvailableLitres(), reading.totalLitres(), reading.flowLitresPerMinute()),
            new SoftenerReply.Salt(reading.saltLevelPercent(), reading.saltDaysLeft(),
                Optional.ofNullable(reading.saltDaysLeft()).map(day::plusDays).orElse(null)),
            new SoftenerReply.Regeneration(reading.daysSinceRegeneration(),
                Optional.ofNullable(reading.daysSinceRegeneration()).map(day::minusDays).orElse(null),
                reading.totalRegenerations()),
            reading.hardnessGpg(),
            Optional.ofNullable(reading.hardnessGpg())
                .map(gpg -> BigDecimal.valueOf(gpg).multiply(MG_CACO3_PER_GPG).setScale(1, RoundingMode.HALF_UP)
                    .doubleValue())
                .orElse(null),
            reading.daysInOperation(),
            reading.signalDbm(),
            new SoftenerReply.Alerts(alerts.lowSalt(), alerts.leak(), alerts.excessiveWaterUse(), alerts.shutoffValve(),
                alerts.resin(), alerts.error()));
    }
}
