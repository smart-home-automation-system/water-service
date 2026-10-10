package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.WaterTemperatureBucket;
import cloud.cholewa.water.database.model.WaterTemperatureEntity;
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
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

//The one test that runs SQL: the history query is PostgreSQL's own, so it runs against a
//PostgreSQL in Docker (Testcontainers), on the table the migrations of the service create.
//The slice builds a plain connection from spring.r2dbc.*, not the pooled one of cholewa-commons,
//which is why the container needs no SSL here. Without a Docker the test fails, it is not skipped.
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class WaterTemperatureRepositoryTest {

    private static final long FIVE_MINUTES = 300;
    private static final long HALF_AN_HOUR = 1800;
    private static final long TWO_HOURS = 7200;

    private static final LocalDateTime MIDNIGHT = LocalDateTime.of(2026, 10, 8, 0, 0);
    private static final LocalDateTime NEXT_MIDNIGHT = MIDNIGHT.plusDays(1);

    private static final TimeZone ZONE_OF_THE_BUILD = TimeZone.getDefault();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WaterTemperatureRepository sut;

    @Autowired
    private DatabaseClient databaseClient;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    //The zone: the image runs on the zone of the house and a build server on UTC, where a
    //date-time converted on its way to the database would come back unharmed - set before the
    //context opens its first connection. The migrations: the test profile switches Flyway off
    //and the slice would not run it anyway
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
    void forgetTheReadings() {
        sut.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_average_both_temperatures_of_a_bucket_and_answer_the_buckets_oldest_first() {
        save(MIDNIGHT.plusMinutes(5), 40.0, 20.0);
        save(MIDNIGHT.plusMinutes(1), 46.0, 26.0);
        save(MIDNIGHT.plusMinutes(4).plusSeconds(59), 48.0, 30.0);

        sut.findHistory(MIDNIGHT, NEXT_MIDNIGHT, FIVE_MINUTES).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(MIDNIGHT, 47.0, 28.0))
            .expectNext(new WaterTemperatureBucket(MIDNIGHT.plusMinutes(5), 40.0, 20.0))
            .verifyComplete();
    }

    @Test
    void should_answer_no_row_for_a_bucket_without_a_reading() {
        save(MIDNIGHT.plusMinutes(1), 46.0, 26.0);
        save(MIDNIGHT.plusMinutes(16), 44.0, 25.0);

        sut.findHistory(MIDNIGHT, NEXT_MIDNIGHT, FIVE_MINUTES).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(MIDNIGHT, 46.0, 26.0))
            .expectNext(new WaterTemperatureBucket(MIDNIGHT.plusMinutes(15), 44.0, 25.0))
            .verifyComplete();
    }

    @Test
    void should_answer_nothing_when_there_is_no_reading_in_the_range() {
        save(MIDNIGHT.minusDays(3), 46.0, 26.0);

        sut.findHistory(MIDNIGHT, NEXT_MIDNIGHT, FIVE_MINUTES).as(StepVerifier::create)
            .verifyComplete();
    }

    @Test
    void should_include_the_start_of_the_range_and_not_its_end() {
        save(MIDNIGHT.minusSeconds(1), 10.0, 10.0);
        save(MIDNIGHT, 46.0, 26.0);
        save(NEXT_MIDNIGHT.minusSeconds(1), 44.0, 25.0);
        save(NEXT_MIDNIGHT, 90.0, 90.0);

        sut.findHistory(MIDNIGHT, NEXT_MIDNIGHT, FIVE_MINUTES).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(MIDNIGHT, 46.0, 26.0))
            .expectNext(new WaterTemperatureBucket(NEXT_MIDNIGHT.minusMinutes(5), 44.0, 25.0))
            .verifyComplete();
    }

    @Test
    void should_start_a_bucket_of_half_an_hour_on_the_clock_of_the_house() {
        save(MIDNIGHT.plusHours(22).plusMinutes(29), 46.0, 26.0);
        save(MIDNIGHT.plusHours(22).plusMinutes(47), 44.0, 25.0);

        sut.findHistory(MIDNIGHT, NEXT_MIDNIGHT, HALF_AN_HOUR).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(MIDNIGHT.plusHours(22), 46.0, 26.0))
            .expectNext(new WaterTemperatureBucket(MIDNIGHT.plusHours(22).plusMinutes(30), 44.0, 25.0))
            .verifyComplete();
    }

    @Test
    void should_keep_the_buckets_of_two_hours_on_the_clock_of_the_house_on_every_day_of_a_long_range() {
        save(MIDNIGHT.plusHours(1).plusMinutes(59), 46.0, 26.0);
        save(MIDNIGHT.plusDays(6).plusHours(23).plusMinutes(47), 44.0, 25.0);

        sut.findHistory(MIDNIGHT, MIDNIGHT.plusDays(31), TWO_HOURS).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(MIDNIGHT, 46.0, 26.0))
            .expectNext(new WaterTemperatureBucket(MIDNIGHT.plusDays(6).plusHours(22), 44.0, 25.0))
            .verifyComplete();
    }

    //the buckets are aligned to the clock, not to the range: the first one starts before "from"
    //and averages only what lies within the range
    @Test
    void should_start_the_first_bucket_before_the_range_when_the_range_starts_inside_it() {
        save(MIDNIGHT.plusMinutes(1), 10.0, 10.0);
        save(MIDNIGHT.plusMinutes(4), 46.0, 26.0);

        sut.findHistory(MIDNIGHT.plusMinutes(2), NEXT_MIDNIGHT, FIVE_MINUTES).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(MIDNIGHT, 46.0, 26.0))
            .verifyComplete();
    }

    @Test
    void should_average_into_the_last_bucket_only_what_lies_before_the_end_of_the_range() {
        save(MIDNIGHT.plusMinutes(1), 46.0, 26.0);
        save(MIDNIGHT.plusMinutes(4), 90.0, 90.0);

        sut.findHistory(MIDNIGHT.minusHours(1), MIDNIGHT.plusMinutes(2), FIVE_MINUTES).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(MIDNIGHT, 46.0, 26.0))
            .verifyComplete();
    }

    //The poll as it runs: a row every 3 minutes over 2 days, from 00:01. Every bucket of 5 minutes
    //has a reading - which is what the width was chosen for - so the line of a working sensor has
    //no gap: 576 points, from the bucket the range starts inside of to the last one of the second day
    @Test
    void should_answer_a_point_for_every_bucket_of_a_sensor_polled_every_three_minutes() {
        final LocalDateTime from = MIDNIGHT.plusMinutes(1);

        Flux.range(0, 960)
            .map(index -> new WaterTemperatureEntity(null, from.plusMinutes(3L * index), 46.0, 26.0))
            .as(sut::saveAll)
            .as(StepVerifier::create)
            .expectNextCount(960)
            .verifyComplete();

        sut.findHistory(from, from.plusDays(2), FIVE_MINUTES).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(MIDNIGHT, 46.0, 26.0))
            .expectNextCount(574)
            .expectNext(new WaterTemperatureBucket(MIDNIGHT.plusDays(2).minusMinutes(5), 46.0, 26.0))
            .verifyComplete();
    }

    //Wall-clock times, stored and asked for as they are: 02:31 of the night the summer time
    //begins is an hour the clock of the house skips, and a conversion would move it to 03:31
    @Test
    void should_not_move_a_reading_of_the_hour_the_clock_skips_when_the_summer_time_begins() {
        final LocalDateTime night = LocalDateTime.of(2026, 3, 29, 0, 0);
        save(night.plusHours(2).plusMinutes(31), 46.0, 26.0);

        sut.findHistory(night, night.plusDays(1), FIVE_MINUTES).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(night.plusHours(2).plusMinutes(30), 46.0, 26.0))
            .verifyComplete();
    }

    //the hour that happens twice has one set of buckets: both passes are averaged into them
    @Test
    void should_average_the_hour_the_clock_repeats_into_the_same_buckets_when_the_summer_time_ends() {
        final LocalDateTime night = LocalDateTime.of(2026, 10, 25, 0, 0);
        save(night.plusHours(2).plusMinutes(10), 46.0, 26.0);
        save(night.plusHours(2).plusMinutes(20), 48.0, 28.0);
        save(night.plusHours(3).plusMinutes(1), 44.0, 25.0);

        sut.findHistory(night, night.plusDays(1), HALF_AN_HOUR).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(night.plusHours(2), 47.0, 27.0))
            .expectNext(new WaterTemperatureBucket(night.plusHours(3), 44.0, 25.0))
            .verifyComplete();
    }

    @Test
    void should_round_the_averages_to_two_decimals() {
        save(MIDNIGHT.plusMinutes(1), 46.0, 26.01);
        save(MIDNIGHT.plusMinutes(2), 46.01, 26.0);
        save(MIDNIGHT.plusMinutes(3), 46.01, 26.0);

        sut.findHistory(MIDNIGHT, NEXT_MIDNIGHT, FIVE_MINUTES).as(StepVerifier::create)
            .expectNext(new WaterTemperatureBucket(MIDNIGHT, 46.01, 26.0))
            .verifyComplete();
    }

    //status/temperature answers this row, and its time is the measuredAt of the answer
    @Test
    void should_find_the_newest_reading_with_the_time_it_was_stored_with() {
        save(MIDNIGHT.plusMinutes(6), 46.81, 26.44);
        save(MIDNIGHT.plusMinutes(3), 40.0, 20.0);

        sut.findFirstByOrderByUpdatedAtDesc().as(StepVerifier::create)
            .assertNext(newest -> {
                assertThat(newest.updatedAt()).isEqualTo(MIDNIGHT.plusMinutes(6));
                assertThat(newest.water()).isEqualTo(46.81);
                assertThat(newest.circulation()).isEqualTo(26.44);
            })
            .verifyComplete();
    }

    //V2: without it both reads of the service scan the whole table
    @Test
    void should_have_an_index_on_the_time_of_the_reading() {
        databaseClient.sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'temperature'")
            .map(row -> row.get("indexdef", String.class))
            .all()
            .collectList()
            .as(StepVerifier::create)
            .assertNext(indexes -> assertThat(indexes).anyMatch(index -> index.endsWith("(updated_at)")))
            .verifyComplete();
    }

    private void save(final LocalDateTime at, final double water, final double circulation) {
        sut.save(new WaterTemperatureEntity(null, at, water, circulation)).as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();
    }
}
