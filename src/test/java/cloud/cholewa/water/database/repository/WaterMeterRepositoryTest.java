package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.ConsumptionBucket;
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
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

//The SQL of the water valve (HAS-316, V3) is PostgreSQL's own, so it runs against a PostgreSQL in Docker, on the tables
//the migrations of the service create - the setup of WaterTemperatureRepositoryTest. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(WaterMeterRepository.class)
class WaterMeterRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 4, 19, 51, 20, 123456000);
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 4, 0, 0);
    private static final TimeZone ZONE_OF_THE_BUILD = TimeZone.getDefault();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WaterMeterRepository sut;

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
    void forgetTheValve() {
        databaseClient.sql("DELETE FROM water_meter_reading").then().as(StepVerifier::create).verifyComplete();
        databaseClient.sql("DELETE FROM water_valve_status").then().as(StepVerifier::create).verifyComplete();
    }

    //a row only when the counter changed; the same total moves last_checked_at of the latest row
    @Test
    void should_insert_a_reading_only_when_the_counter_changed() {
        sut.recordCounter(new BigDecimal("61.665843064"), AT).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.recordCounter(new BigDecimal("61.665843064"), AT.plusMinutes(1)).as(StepVerifier::create).expectNext(false)
            .verifyComplete();
        sut.recordCounter(new BigDecimal("61.668"), AT.plusMinutes(2)).as(StepVerifier::create).expectNext(true)
            .verifyComplete();

        readings().as(StepVerifier::create)
            .expectNext(Map.of("total_m3", new BigDecimal("61.665843064"), "measured_at", AT,
                "last_checked_at", AT.plusMinutes(1)))
            .expectNext(Map.of("total_m3", new BigDecimal("61.668000000"), "measured_at", AT.plusMinutes(2),
                "last_checked_at", AT.plusMinutes(2)))
            .verifyComplete();
    }

    //the comparison is with the latest row, not with any: a counter back at an older total is a change
    @Test
    void should_insert_a_reading_back_at_an_older_total() {
        sut.recordCounter(new BigDecimal("1.5"), AT).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.recordCounter(new BigDecimal("0"), AT.plusMinutes(1)).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.recordCounter(new BigDecimal("1.5"), AT.plusMinutes(2)).as(StepVerifier::create).expectNext(true)
            .verifyComplete();
    }

    //one current row: its measured_at moves only with a change of a value, last_checked_at with every snapshot
    @Test
    void should_keep_one_row_of_the_valve() {
        sut.recordStatus(true, 0.0, 495.0, 12.9, AT).as(StepVerifier::create).verifyComplete();
        sut.recordStatus(true, 0.0, 495.0, 12.9, AT.plusMinutes(1)).as(StepVerifier::create).verifyComplete();

        status().as(StepVerifier::create)
            .assertNext(row -> {
                assertThat(row).containsEntry("measured_at", AT);
                assertThat(row).containsEntry("last_checked_at", AT.plusMinutes(1));
            })
            .verifyComplete();

        sut.recordStatus(false, null, 495.0, 12.9, AT.plusMinutes(2)).as(StepVerifier::create).verifyComplete();

        status().as(StepVerifier::create)
            .assertNext(row -> {
                assertThat(row).containsEntry("open", false);
                assertThat(row.get("flow_rate")).isNull();
                assertThat(row).containsEntry("measured_at", AT.plusMinutes(2));
            })
            .verifyComplete();

        databaseClient.sql("SELECT count(*) AS rows FROM water_valve_status").map(row -> row.get("rows", Long.class))
            .one().as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    //the latest row is the counter as it is now and is never deleted, however old
    @Test
    void should_delete_the_old_readings_but_the_latest() {
        sut.recordCounter(new BigDecimal("1"), AT.minusDays(800)).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.recordCounter(new BigDecimal("2"), AT.minusDays(750)).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.deleteReadingsBefore(AT.minusDays(730)).as(StepVerifier::create).expectNext(1L).verifyComplete();

        sut.recordCounter(new BigDecimal("3"), AT).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.deleteReadingsBefore(AT.minusDays(730)).as(StepVerifier::create).expectNext(1L).verifyComplete();

        readings().map(row -> row.get("total_m3")).as(StepVerifier::create)
            .expectNext(new BigDecimal("3.000000000"))
            .verifyComplete();
    }

    //HAS-317: a day in hours - the row before the range gives the first change its predecessor, an hour without water but
    //watched is 0, an hour nobody watched has no point
    @Test
    void should_answer_the_water_of_every_watched_hour() {
        reading("10", DAY.minusHours(1), DAY.plusHours(6).plusMinutes(10));
        reading("10.05", DAY.plusHours(6).plusMinutes(11), DAY.plusHours(6).plusMinutes(30));
        reading("10.2", DAY.plusHours(7).plusMinutes(5), DAY.plusHours(9).plusMinutes(59));

        sut.consumption(DAY, DAY.plusDays(1), "hour").collectList().as(StepVerifier::create)
            .assertNext(buckets -> {
                assertThat(buckets).extracting(ConsumptionBucket::at)
                    .containsExactly(DAY, DAY.plusHours(1), DAY.plusHours(2), DAY.plusHours(3), DAY.plusHours(4),
                        DAY.plusHours(5), DAY.plusHours(6), DAY.plusHours(7), DAY.plusHours(8), DAY.plusHours(9));
                assertThat(buckets).extracting(ConsumptionBucket::volume)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("0.05"), new BigDecimal("0.15"), BigDecimal.ZERO, BigDecimal.ZERO);
            })
            .verifyComplete();
    }

    //a reset of the counter inside a day is a new series: the drop counts as nothing, never a negative volume
    @Test
    void should_never_answer_a_negative_volume_across_a_reset_of_the_counter() {
        reading("10", DAY.plusHours(8), DAY.plusHours(8).plusMinutes(30));
        reading("10.4", DAY.plusHours(8).plusMinutes(31), DAY.plusHours(9));
        reading("0.001", DAY.plusHours(9).plusMinutes(1), DAY.plusHours(9).plusMinutes(30));
        reading("0.051", DAY.plusHours(9).plusMinutes(31), DAY.plusHours(9).plusMinutes(59));

        sut.consumption(DAY, DAY.plusDays(1), "hour").collectList().as(StepVerifier::create)
            .assertNext(buckets -> {
                assertThat(buckets).extracting(ConsumptionBucket::at).containsExactly(DAY.plusHours(8), DAY.plusHours(9));
                assertThat(buckets).extracting(ConsumptionBucket::volume)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(new BigDecimal("0.4"), new BigDecimal("0.05"));
            })
            .verifyComplete();
    }

    @Test
    void should_leave_out_the_hours_the_counter_was_not_watched() {
        reading("1", DAY, DAY.plusMinutes(30));
        reading("1.1", DAY.plusHours(3), DAY.plusHours(3).plusMinutes(30));

        sut.consumption(DAY, DAY.plusDays(1), "hour").map(ConsumptionBucket::at).as(StepVerifier::create)
            .expectNext(DAY, DAY.plusHours(3))
            .verifyComplete();
    }

    //a year in months; the water of a gap is counted where the counter was seen again
    @Test
    void should_answer_a_year_in_months() {
        final LocalDateTime year = LocalDateTime.of(2026, 1, 1, 0, 0);
        reading("1", year.plusDays(9), LocalDateTime.of(2026, 1, 31, 23, 59));
        reading("3", LocalDateTime.of(2026, 3, 5, 12, 0), LocalDateTime.of(2026, 3, 6, 12, 0));

        sut.consumption(year, year.plusYears(1), "month").collectList().as(StepVerifier::create)
            .assertNext(buckets -> {
                assertThat(buckets).extracting(ConsumptionBucket::at).containsExactly(year, year.plusMonths(2));
                assertThat(buckets).extracting(ConsumptionBucket::volume)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(BigDecimal.ZERO, new BigDecimal("2"));
            })
            .verifyComplete();
    }

    //house time without a zone: the hour skipped in spring is a bucket of 0, the counter was watched across it (README)
    @Test
    void should_answer_the_hour_skipped_in_spring_as_a_bucket_of_nothing() {
        final LocalDateTime spring = LocalDateTime.of(2026, 3, 29, 0, 0);
        reading("5", spring.plusHours(1).plusMinutes(30), spring.plusHours(3).plusMinutes(10));

        sut.consumption(spring.plusHours(1), spring.plusHours(4), "hour").collectList().as(StepVerifier::create)
            .assertNext(buckets -> assertThat(buckets).extracting(ConsumptionBucket::at)
                .containsExactly(spring.plusHours(1), spring.plusHours(2), spring.plusHours(3)))
            .verifyComplete();
    }

    @Test
    void should_answer_no_bucket_without_readings() {
        sut.consumption(DAY, DAY.plusDays(1), "hour").as(StepVerifier::create).verifyComplete();
    }

    private void reading(final String total, final LocalDateTime measuredAt, final LocalDateTime lastCheckedAt) {
        databaseClient.sql("INSERT INTO water_meter_reading (total_m3, measured_at, last_checked_at) VALUES (:total, :at, :checked)")
            .bind("total", new BigDecimal(total))
            .bind("at", measuredAt)
            .bind("checked", lastCheckedAt)
            .then()
            .as(StepVerifier::create)
            .verifyComplete();
    }

    private Flux<Map<String, Object>> readings() {
        return databaseClient.sql("SELECT total_m3, measured_at, last_checked_at FROM water_meter_reading ORDER BY id")
            .fetch().all();
    }

    private Flux<Map<String, Object>> status() {
        return databaseClient.sql("SELECT * FROM water_valve_status").fetch().all();
    }
}
