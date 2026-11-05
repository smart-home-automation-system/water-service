package cloud.cholewa.water.database.repository;

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

    private Flux<Map<String, Object>> readings() {
        return databaseClient.sql("SELECT total_m3, measured_at, last_checked_at FROM water_meter_reading ORDER BY id")
            .fetch().all();
    }

    private Flux<Map<String, Object>> status() {
        return databaseClient.sql("SELECT * FROM water_valve_status").fetch().all();
    }
}
