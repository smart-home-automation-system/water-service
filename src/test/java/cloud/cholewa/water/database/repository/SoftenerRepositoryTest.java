package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.alarm.SoftenerAlarm;
import cloud.cholewa.water.model.SoftenerHistoryReply;
import cloud.cholewa.water.softener.SoftenerMetric;
import cloud.cholewa.water.softener.SoftenerReading;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

//The SQL of the softener (HAS-398, V9) is PostgreSQL's own, so it runs against a PostgreSQL in Docker, on the tables the
//migrations of the service create - the setup of WaterMeterRepositoryTest. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(SoftenerRepository.class)
class SoftenerRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 9, 10, 20);
    private static final TimeZone ZONE_OF_THE_BUILD = TimeZone.getDefault();
    private static final SoftenerReading.Alerts QUIET = new SoftenerReading.Alerts(false, false, false, false, false, false);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private SoftenerRepository sut;

    @Autowired
    private DatabaseClient databaseClient;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void liveOnTheClockOfTheHouseAndMigrate() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"));
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @AfterAll
    static void giveTheZoneBack() {
        TimeZone.setDefault(ZONE_OF_THE_BUILD);
    }

    @BeforeEach
    void forgetTheSoftener() {
        databaseClient.sql("DELETE FROM softener_reading").then().as(StepVerifier::create).verifyComplete();
        databaseClient.sql("DELETE FROM softener_regeneration").then().as(StepVerifier::create).verifyComplete();
    }

    private static SoftenerReading reading(final Double total, final Double usedToday, final Double salt) {
        return new SoftenerReading(true, usedToday, 503.0, 2218.0, total, 0.0, salt, 73, 0, 156, 12.0, 917, -70, QUIET);
    }

    private List<Map<String, Object>> readings() {
        return databaseClient.sql("SELECT total_litres, used_today_litres, salt_level_percent, measured_at, last_checked_at "
                + "FROM softener_reading ORDER BY id")
            .fetch().all().collectList().block();
    }

    //a row only on a change; the same values move last_checked_at of the latest row - the status-table rule
    @Test
    void should_insert_a_reading_only_when_a_value_changed() {
        sut.recordReading(reading(425408.0, 102.0, 25.0), AT).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.recordReading(reading(425408.0, 102.0, 25.0), AT.plusMinutes(5)).as(StepVerifier::create).expectNext(false)
            .verifyComplete();
        sut.recordReading(reading(425424.0, 117.0, 25.0), AT.plusMinutes(10)).as(StepVerifier::create).expectNext(true)
            .verifyComplete();

        assertThat(readings()).containsExactly(
            Map.of("total_litres", new BigDecimal("425408.0"), "used_today_litres", new BigDecimal("102.0"),
                "salt_level_percent", new BigDecimal("25.0"), "measured_at", AT, "last_checked_at", AT.plusMinutes(5)),
            Map.of("total_litres", new BigDecimal("425424.0"), "used_today_litres", new BigDecimal("117.0"),
                "salt_level_percent", new BigDecimal("25.0"), "measured_at", AT.plusMinutes(10),
                "last_checked_at", AT.plusMinutes(10)));
    }

    //a value the cloud left out is NULL, and NULL is the same as NULL - no row a pass for a missing value
    @Test
    void should_treat_a_missing_value_as_the_same_missing_value() {
        sut.recordReading(reading(425408.0, null, null), AT).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.recordReading(reading(425408.0, null, null), AT.plusMinutes(5)).as(StepVerifier::create).expectNext(false)
            .verifyComplete();
        sut.recordReading(reading(425408.0, null, 25.0), AT.plusMinutes(10)).as(StepVerifier::create).expectNext(true)
            .verifyComplete();
    }

    //a flag that changes is a change too
    @Test
    void should_insert_a_reading_when_a_flag_changed() {
        sut.recordReading(reading(425408.0, 102.0, 25.0), AT).as(StepVerifier::create).expectNext(true).verifyComplete();
        final SoftenerReading lowSalt = new SoftenerReading(true, 102.0, 503.0, 2218.0, 425408.0, 0.0, 25.0, 73, 0, 156,
            12.0, 917, -70, new SoftenerReading.Alerts(true, false, false, false, false, false));

        sut.recordReading(lowSalt, AT.plusMinutes(5)).as(StepVerifier::create).expectNext(true).verifyComplete();
    }

    @Test
    void should_answer_the_history_of_a_metric_in_buckets() {
        sut.recordReading(reading(1000.0, 10.0, 26.0), AT).block();
        sut.recordReading(reading(1010.0, 20.0, 25.0), AT.plusMinutes(10)).block();
        sut.recordReading(reading(1030.0, 40.0, 24.0), AT.plusHours(3)).block();

        sut.history(SoftenerMetric.SALT_LEVEL, AT.minusHours(1), AT.plusHours(5), 7200).collectList()
            .as(StepVerifier::create)
            .assertNext(points -> assertThat(points).containsExactly(
                new SoftenerHistoryReply.Point(LocalDateTime.of(2026, 11, 9, 10, 0), new BigDecimal("25.50"),
                    new BigDecimal("25.0"), new BigDecimal("26.0")),
                new SoftenerHistoryReply.Point(LocalDateTime.of(2026, 11, 9, 12, 0), new BigDecimal("24.00"),
                    new BigDecimal("24.0"), new BigDecimal("24.0"))))
            .verifyComplete();
    }

    //the litres per day from the deltas of the counter: the row before the range counts, a drop is a reset, a day not
    //watched has no entry, a day watched without water is 0
    @Test
    void should_answer_the_litres_through_the_softener_per_day() {
        final LocalDateTime day1 = LocalDateTime.of(2026, 11, 1, 0, 0);
        sut.recordReading(reading(1000.0, 0.0, 25.0), day1.minusHours(2)).block();
        sut.recordReading(reading(1100.0, 100.0, 25.0), day1.plusHours(8)).block();
        sut.recordReading(reading(1150.0, 150.0, 25.0), day1.plusHours(20)).block();
        //the next day: a reset of the counter, then water
        sut.recordReading(reading(10.0, 10.0, 25.0), day1.plusDays(1).plusHours(8)).block();
        sut.recordReading(reading(40.0, 40.0, 25.0), day1.plusDays(1).plusHours(9)).block();
        //the third day watched without water: the same values checked again after it began
        sut.recordReading(reading(40.0, 40.0, 25.0), day1.plusDays(2).plusHours(12)).block();

        sut.days(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5)).collectList()
            .as(StepVerifier::create)
            .assertNext(days -> assertThat(days).containsExactly(
                new SoftenerRepository.SoftenerDay(LocalDate.of(2026, 11, 1), new BigDecimal("150.0")),
                new SoftenerRepository.SoftenerDay(LocalDate.of(2026, 11, 2), new BigDecimal("30.0")),
                new SoftenerRepository.SoftenerDay(LocalDate.of(2026, 11, 3), new BigDecimal("0"))))
            .verifyComplete();
    }

    @Test
    void should_delete_the_readings_before_a_time_but_never_the_latest() {
        sut.recordReading(reading(1000.0, 10.0, 25.0), AT.minusDays(800)).block();
        sut.recordReading(reading(1010.0, 20.0, 25.0), AT.minusDays(799)).block();

        sut.deleteReadingsBefore(AT.minusDays(730)).as(StepVerifier::create).expectNext(1L).verifyComplete();

        assertThat(readings()).hasSize(1);
    }

    //a new regeneration inserted, a stored one that goes on completed later, an ended one never changed
    @Test
    void should_store_a_regeneration_and_complete_it_later() {
        final LocalDateTime start = LocalDateTime.of(2026, 11, 9, 2, 0);

        sut.recordRegeneration("r1", start, null, start).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.knownRegenerations(List.of("r1", "r2")).as(StepVerifier::create)
            .expectNext(Map.of("r1", false)).verifyComplete();
        sut.recordRegeneration("r1", start, null, start).as(StepVerifier::create).expectNext(false).verifyComplete();
        sut.recordRegeneration("r1", start, start.plusHours(2), start).as(StepVerifier::create).expectNext(true)
            .verifyComplete();
        sut.recordRegeneration("r1", start, start.plusHours(3), start).as(StepVerifier::create).expectNext(false)
            .verifyComplete();

        sut.knownRegenerations(List.of("r1")).as(StepVerifier::create).expectNext(Map.of("r1", true)).verifyComplete();
        sut.regenerations(start.minusDays(1), start.plusDays(1)).collectList().as(StepVerifier::create)
            .expectNext(List.of(new SoftenerRepository.StoredRegeneration(start, start.plusHours(2), start)))
            .verifyComplete();
    }

    @Test
    void should_list_the_regenerations_newest_first_and_delete_the_old_ones() {
        final LocalDateTime start = LocalDateTime.of(2026, 11, 9, 2, 0);
        sut.recordRegeneration("old", start.minusDays(800), start.minusDays(800).plusHours(2), null).block();
        sut.recordRegeneration("r1", start.minusDays(3), start.minusDays(3).plusHours(2), null).block();
        sut.recordRegeneration("r2", start, null, null).block();

        sut.regenerations(start.minusDays(10), start.plusDays(1)).map(SoftenerRepository.StoredRegeneration::startedAt)
            .collectList().as(StepVerifier::create)
            .expectNext(List.of(start, start.minusDays(3)))
            .verifyComplete();
        sut.deleteRegenerationsBefore(start.minusDays(730)).as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    //HAS-399, V10: every alarm of the softener is a kind water_alert takes - the CHECK and the 20 characters of the column
    @Test
    void should_store_an_episode_of_every_alarm_of_the_softener() {
        for (final SoftenerAlarm alarm : SoftenerAlarm.values()) {
            databaseClient.sql("INSERT INTO water_alert (kind, opened_at) VALUES (:kind, :at)")
                .bind("kind", alarm.wireName())
                .bind("at", AT)
                .then()
                .as(StepVerifier::create)
                .verifyComplete();
        }
        databaseClient.sql("SELECT count(*) AS stored FROM water_alert WHERE kind LIKE '%salt' OR kind LIKE 'softener%'"
                + " OR kind = 'excessive use'")
            .map(row -> row.get("stored", Long.class))
            .one()
            .as(StepVerifier::create)
            .expectNext((long) SoftenerAlarm.values().length)
            .verifyComplete();
        databaseClient.sql("DELETE FROM water_alert").then().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_refuse_a_kind_water_alert_does_not_know() {
        databaseClient.sql("INSERT INTO water_alert (kind, opened_at) VALUES ('softener party', :at)")
            .bind("at", AT)
            .then()
            .as(StepVerifier::create)
            .verifyError();
    }
}
