package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.CirculationRunEntity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

//The runs of the circulation pump (HAS-404, V8) against a PostgreSQL in Docker on the tables of the migrations, the setup
//of WaterBillRepositoryTest: the CHECKs of V8, one end only, the runs of a range newest first, the newest by id, the
//retention. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class CirculationRunRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 19, 0);
    private static final TimeZone ZONE_OF_THE_BUILD = TimeZone.getDefault();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private CirculationRunRepository runs;

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
    void forgetEverything() {
        databaseClient.sql("DELETE FROM circulation_run").then().as(StepVerifier::create).verifyComplete();
    }

    private static CirculationRunEntity started(final LocalDateTime at) {
        return new CirculationRunEntity(null, at, null, "manual", "dashboard", null, new BigDecimal("24.50"), null);
    }

    @Test
    void should_end_a_run_once() {
        final Long id = runs.save(started(AT)).map(CirculationRunEntity::id).block();

        runs.end(id, AT.plusSeconds(70), "temperature", new BigDecimal("35.25")).as(StepVerifier::create)
            .expectNext(1).verifyComplete();
        runs.end(id, AT.plusSeconds(90), "limit", null).as(StepVerifier::create)
            .expectNext(0).verifyComplete();

        runs.findById(id).as(StepVerifier::create)
            .assertNext(row -> {
                assertThat(row.endedAt()).isEqualTo(AT.plusSeconds(70));
                assertThat(row.stoppedBy()).isEqualTo("temperature");
                assertThat(row.returnAtStart()).isEqualByComparingTo("24.5");
                assertThat(row.returnAtEnd()).isEqualByComparingTo("35.25");
            })
            .verifyComplete();
    }

    //[from, to): the start of the range in, its end out, newest first; the newest of the table by id
    @Test
    void should_answer_the_runs_of_a_range_and_the_newest() {
        runs.save(started(AT.minusDays(1))).then(runs.save(started(AT.minusHours(3)))).then(runs.save(started(AT)))
            .as(StepVerifier::create).expectNextCount(1).verifyComplete();

        runs.findStartedIn(AT.minusDays(1), AT).map(CirculationRunEntity::startedAt).as(StepVerifier::create)
            .expectNext(AT.minusHours(3), AT.minusDays(1))
            .verifyComplete();
        runs.findFirstByOrderByIdDesc().map(CirculationRunEntity::startedAt).as(StepVerifier::create)
            .expectNext(AT)
            .verifyComplete();
    }

    @Test
    void should_refuse_a_trigger_or_an_end_v8_does_not_know() {
        runs.save(new CirculationRunEntity(null, AT, null, "flow", "dashboard", null, null, null)).as(StepVerifier::create)
            .expectError()
            .verify();
        final Long id = runs.save(started(AT)).map(CirculationRunEntity::id).block();
        runs.end(id, AT.plusSeconds(10), "stopped", null).as(StepVerifier::create)
            .expectError()
            .verify();
    }

    //the runs of an earlier pod end as failure at the end of their timer; the one held now and an ended one stay as they are
    @Test
    void should_end_the_runs_of_an_earlier_pod_at_their_timer() {
        final Long orphan = runs.save(started(AT.minusHours(1))).map(CirculationRunEntity::id).block();
        final Long held = runs.save(started(AT)).map(CirculationRunEntity::id).block();
        final Long ended = runs.save(started(AT.minusHours(2))).map(CirculationRunEntity::id).block();
        runs.end(ended, AT.minusHours(2).plusSeconds(40), "temperature", null).as(StepVerifier::create)
            .expectNext(1).verifyComplete();

        runs.endOrphans(AT, 180L).as(StepVerifier::create).expectNext(1).verifyComplete();

        runs.findById(orphan).as(StepVerifier::create)
            .assertNext(row -> {
                assertThat(row.endedAt()).isEqualTo(AT.minusHours(1).plusSeconds(180));
                assertThat(row.stoppedBy()).isEqualTo("failure");
            })
            .verifyComplete();
        runs.findById(held).as(StepVerifier::create)
            .assertNext(row -> assertThat(row.endedAt()).isNull())
            .verifyComplete();
        runs.findById(ended).map(CirculationRunEntity::stoppedBy).as(StepVerifier::create)
            .expectNext("temperature").verifyComplete();
    }

    @Test
    void should_delete_the_runs_started_before_a_time() {
        runs.save(started(AT.minusDays(366))).then(runs.save(started(AT.minusDays(364)))).as(StepVerifier::create)
            .expectNextCount(1).verifyComplete();

        runs.deleteStartedBefore(AT.minusDays(365)).as(StepVerifier::create).expectNext(1).verifyComplete();
        runs.count().as(StepVerifier::create).expectNext(1L).verifyComplete();
    }
}
