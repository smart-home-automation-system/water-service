package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import cloud.cholewa.water.model.SoftenerReply;
import cloud.cholewa.water.softener.SoftenerProperties;
import cloud.cholewa.water.softener.SoftenerReading;
import cloud.cholewa.water.softener.SoftenerTracker;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//The softener now (HAS-397): 503 before the first reading, then the reading with its age; the days become dates on the
//day of the reading, the hardness also in mg/l of CaCO3
class SoftenerServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime READ = LocalDateTime.of(2026, 11, 9, 10, 0);
    private static final Instant READ_AT = READ.atZone(HOUSE).toInstant();
    private static final SoftenerReading READING = new SoftenerReading(true, 312.4, 410.0, 1890.5, 152003.2, 0.0, 45.0,
        38, 3, 412, 14.0, 950, -58, new SoftenerReading.Alerts(false, false, false, false, false, false));

    private final SoftenerTracker tracker = mock(SoftenerTracker.class);
    private final SoftenerProperties properties = new SoftenerProperties(null, new SoftenerProperties.Cloud(
        URI.create("http://localhost:1"), "owner@example.com", "s3cr3t", Duration.ofMinutes(5)), Duration.ofMinutes(30));

    private SoftenerService serviceAt(final LocalDateTime now) {
        return new SoftenerService(tracker, properties, Clock.fixed(now.atZone(HOUSE).toInstant(), HOUSE));
    }

    @Test
    void should_be_unavailable_before_the_first_reading() {
        when(tracker.current()).thenReturn(new SoftenerTracker.State(null, null, null, true, 1));

        serviceAt(READ).current().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(ValveException.class,
                refused -> assertThat(refused.getError()).isEqualTo(WaterError.SOFTENER_UNAVAILABLE)))
            .verify();
    }

    @Test
    void should_answer_the_reading_with_its_dates() {
        when(tracker.current()).thenReturn(new SoftenerTracker.State(READING, READ, READ_AT, true, 0));

        serviceAt(READ.plusMinutes(3)).current().as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply).isEqualTo(new SoftenerReply(true, true, READ, false,
                new SoftenerReply.Water(312.4, 410.0, 1890.5, 152003.2, 0.0),
                new SoftenerReply.Salt(45.0, 38, LocalDate.of(2026, 12, 17)),
                new SoftenerReply.Regeneration(3, LocalDate.of(2026, 11, 6), 412),
                14.0, 239.7, 950, -58,
                new SoftenerReply.Alerts(false, false, false, false, false, false))))
            .verifyComplete();
    }

    @Test
    void should_be_stale_past_two_intervals() {
        final SoftenerTracker.State state = new SoftenerTracker.State(READING, READ, READ_AT, true, 0);

        assertThat(serviceAt(READ).replyOf(state, READ_AT.plusSeconds(600)).stale()).isFalse();
        assertThat(serviceAt(READ).replyOf(state, READ_AT.plusSeconds(601)).stale()).isTrue();
    }

    //the house time goes back an hour on the last Sunday of October: a reading of the first 02:58 is old at the second 02:05
    @Test
    void should_judge_the_age_on_the_instant_across_the_change_of_time() {
        final LocalDateTime firstHour = LocalDateTime.of(2026, 10, 25, 2, 58);
        final Instant readAt = firstHour.atZone(HOUSE).withEarlierOffsetAtOverlap().toInstant();
        final SoftenerTracker.State state = new SoftenerTracker.State(READING, firstHour, readAt, true, 0);

        assertThat(serviceAt(READ).replyOf(state, readAt.plusSeconds(67 * 60)).stale()).isTrue();
    }

    //a value the cloud left out is left out, no date is made of a missing day
    @Test
    void should_leave_out_what_the_reading_has_not() {
        final SoftenerReading empty = new SoftenerReading(null, null, null, null, null, null, null, null, null, null, null,
            null, null, new SoftenerReading.Alerts(null, null, null, null, null, null));
        when(tracker.current()).thenReturn(new SoftenerTracker.State(empty, READ, READ_AT, true, 2));

        serviceAt(READ).current().as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.reachable()).isFalse();
                assertThat(reply.salt()).isEqualTo(new SoftenerReply.Salt(null, null, null));
                assertThat(reply.regeneration()).isEqualTo(new SoftenerReply.Regeneration(null, null, null));
                assertThat(reply.hardnessMgCaco3()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_convert_the_hardness_without_double_noise() {
        final SoftenerReading reading = new SoftenerReading(null, null, null, null, null, null, null, null, null, null,
            0.1, null, null, new SoftenerReading.Alerts(null, null, null, null, null, null));

        assertThat(serviceAt(READ).replyOf(new SoftenerTracker.State(reading, READ, READ_AT, true, 0), READ_AT).hardnessMgCaco3())
            .isEqualTo(1.7);
    }
}
