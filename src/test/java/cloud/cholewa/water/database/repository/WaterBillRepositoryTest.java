package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.database.model.ConsumptionBucket;
import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.database.model.WaterBillEntity;
import cloud.cholewa.water.model.ImportedDay;
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
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

//The SQL of the bills and of the imported consumption (HAS-318, V4) and the alarms (HAS-319, V5, V6) - PostgreSQL's own, against a PostgreSQL in Docker on
//the tables of the migrations, the setup of WaterMeterRepositoryTest. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import({ConsumptionImportRepository.class, WaterMeterRepository.class})
class WaterBillRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final TimeZone ZONE_OF_THE_BUILD = TimeZone.getDefault();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WaterBillRepository bills;

    @Autowired
    private ConsumptionImportRepository imports;

    @Autowired
    private WaterAlertRepository alerts;

    @Autowired
    private WaterMeterRepository meter;

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
        for (final String table : List.of("water_bill", "water_consumption_import", "water_meter_reading", "water_alert")) {
            databaseClient.sql("DELETE FROM " + table).then().as(StepVerifier::create).verifyComplete();
        }
    }

    @Test
    void should_keep_a_bill_and_answer_them_newest_first() {
        final WaterBillEntity september = bill("2026-09-29", "2026-10-20");
        bills.save(bill("2026-08-01", "2026-09-28")).then(bills.save(september)).as(StepVerifier::create)
            .assertNext(saved -> assertThat(saved.id()).isNotNull())
            .verifyComplete();

        bills.findAllByOrderByPeriodFromDesc().map(WaterBillEntity::periodFrom).as(StepVerifier::create)
            .expectNext(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 8, 1))
            .verifyComplete();
        bills.findAllByOrderByPeriodFromDesc().take(1).as(StepVerifier::create)
            .assertNext(stored -> {
                assertThat(stored.billedVolume()).isEqualByComparingTo("11");
                assertThat(stored.amount()).isEqualByComparingTo("98.76");
                assertThat(stored.createdAt()).isEqualTo(AT);
            })
            .verifyComplete();
    }

    //a shared day overlaps, both ends included; the bill itself is never its own overlap
    @Test
    void should_find_a_bill_that_shares_a_day() {
        final Long id = bills.save(bill("2026-09-29", "2026-10-20")).map(WaterBillEntity::id).block();

        bills.findOverlapping(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 3), 0).as(StepVerifier::create)
            .expectNext(id).verifyComplete();
        bills.findOverlapping(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 29), 0).as(StepVerifier::create)
            .expectNext(id).verifyComplete();
        bills.findOverlapping(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 11, 3), 0).as(StepVerifier::create)
            .verifyComplete();
        bills.findOverlapping(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 20), id).as(StepVerifier::create)
            .verifyComplete();
    }

    //the import replaced whole: sent again, it fixes what was sent before
    @Test
    void should_replace_the_whole_import() {
        imports.replaceAll(List.of(day("2026-10-01", "0.5"), day("2026-10-02", "0.6"))).as(StepVerifier::create)
            .verifyComplete();
        imports.replaceAll(List.of(day("2026-10-02", "0.7"))).as(StepVerifier::create).verifyComplete();

        imports.findAll().as(StepVerifier::create)
            .assertNext(only -> {
                assertThat(only.day()).isEqualTo(LocalDate.of(2026, 10, 2));
                assertThat(only.volume()).isEqualByComparingTo("0.7");
            })
            .verifyComplete();

        imports.replaceAll(List.of()).as(StepVerifier::create).verifyComplete();
        imports.findAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_answer_the_first_day_of_the_readings() {
        imports.firstReadingDay().as(StepVerifier::create).verifyComplete();

        reading("61.8", LocalDateTime.of(2026, 11, 5, 8, 37), LocalDateTime.of(2026, 11, 5, 9, 0));
        reading("61.9", LocalDateTime.of(2026, 11, 5, 9, 1), LocalDateTime.of(2026, 11, 5, 10, 0));

        imports.firstReadingDay().as(StepVerifier::create).expectNext(LocalDate.of(2026, 11, 5)).verifyComplete();
    }

    //an imported day is a watched day: its own bucket of a week or a month, its share of a month of a year, none in hours
    @Test
    void should_count_the_imported_days_with_the_readings() {
        imports.replaceAll(List.of(day("2026-11-03", "0.4"), day("2026-11-04", "0.5"))).as(StepVerifier::create)
            .verifyComplete();
        reading("61.8", LocalDateTime.of(2026, 11, 5, 0, 0), LocalDateTime.of(2026, 11, 5, 9, 0));
        reading("61.9", LocalDateTime.of(2026, 11, 5, 9, 1), LocalDateTime.of(2026, 11, 5, 10, 0));

        meter.consumption(LocalDateTime.of(2026, 11, 2, 0, 0), LocalDateTime.of(2026, 11, 9, 0, 0), "day").collectList()
            .as(StepVerifier::create)
            .assertNext(days -> {
                assertThat(days).extracting(ConsumptionBucket::at).containsExactly(LocalDateTime.of(2026, 11, 3, 0, 0),
                    LocalDateTime.of(2026, 11, 4, 0, 0), LocalDateTime.of(2026, 11, 5, 0, 0));
                assertThat(days).extracting(ConsumptionBucket::volume).usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(new BigDecimal("0.4"), new BigDecimal("0.5"), new BigDecimal("0.1"));
            })
            .verifyComplete();

        meter.consumption(LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0), "month").collectList()
            .as(StepVerifier::create)
            .assertNext(months -> {
                assertThat(months).extracting(ConsumptionBucket::at).containsExactly(LocalDateTime.of(2026, 11, 1, 0, 0));
                assertThat(months.getFirst().volume()).isEqualByComparingTo("1.0");
            })
            .verifyComplete();

        meter.consumption(LocalDateTime.of(2026, 11, 4, 0, 0), LocalDateTime.of(2026, 11, 5, 0, 0), "hour")
            .as(StepVerifier::create)
            .verifyComplete();
    }

    //the first day of the readings is watched only from its first row: its imported value wins in a day and a month,
    //never counted twice; its hours stay what was measured
    @Test
    void should_take_the_imported_value_of_the_first_day_of_the_readings() {
        imports.replaceAll(List.of(day("2026-11-05", "0.35"))).as(StepVerifier::create).verifyComplete();
        reading("61.8", LocalDateTime.of(2026, 11, 5, 8, 37), LocalDateTime.of(2026, 11, 5, 9, 0));
        reading("61.9", LocalDateTime.of(2026, 11, 5, 9, 1), LocalDateTime.of(2026, 11, 5, 10, 0));
        final LocalDateTime day = LocalDateTime.of(2026, 11, 5, 0, 0);

        meter.consumption(day, day.plusDays(1), "day").as(StepVerifier::create)
            .assertNext(bucket -> assertThat(bucket.volume()).isEqualByComparingTo("0.35"))
            .verifyComplete();
        meter.consumption(day.withDayOfMonth(1), day.withDayOfMonth(1).plusMonths(1), "month").as(StepVerifier::create)
            .assertNext(bucket -> assertThat(bucket.volume()).isEqualByComparingTo("0.35"))
            .verifyComplete();
        meter.consumption(day, day.plusDays(1), "hour").map(ConsumptionBucket::volume).collectList()
            .as(StepVerifier::create)
            .assertNext(hours -> assertThat(hours.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.1"))
            .verifyComplete();
    }

    //the episodes of the alarms (HAS-319, V5): at most one open a kind - a second is refused by the partial unique index
    @Test
    void should_keep_one_open_alarm_a_kind() {
        alerts.save(new WaterAlertEntity(null, "no mains", AT, null, null)).as(StepVerifier::create)
            .assertNext(saved -> assertThat(saved.id()).isNotNull())
            .verifyComplete();
        alerts.save(new WaterAlertEntity(null, "frost", AT, AT, null)).as(StepVerifier::create)
            .expectNextCount(1).verifyComplete();
        alerts.save(new WaterAlertEntity(null, "no mains", AT.plusHours(1), null, null)).as(StepVerifier::create)
            .expectError().verify();

        alerts.findByClosedAtIsNull().map(WaterAlertEntity::kind).collectList().as(StepVerifier::create)
            .assertNext(kinds -> assertThat(kinds).containsExactlyInAnyOrder("no mains", "frost"))
            .verifyComplete();
    }


    //the kinds of the flow alarms (HAS-320, V6) are taken, a kind no version knows is refused
    @Test
    void should_take_the_kinds_of_the_flow_alarms() {
        for (final String kind : new String[] {"high flow", "continuous flow", "empty house flow"}) {
            alerts.save(new WaterAlertEntity(null, kind, AT, null, null)).as(StepVerifier::create)
                .expectNextCount(1).verifyComplete();
        }
        alerts.save(new WaterAlertEntity(null, "leak", AT, null, null)).as(StepVerifier::create)
            .expectError().verify();
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

    private static WaterBillEntity bill(final String from, final String to) {
        return new WaterBillEntity(null, LocalDate.parse(from), LocalDate.parse(to), new BigDecimal("11"), null,
            new BigDecimal("98.76"), null, AT, AT);
    }

    private static ImportedDay day(final String day, final String volume) {
        return new ImportedDay(LocalDate.parse(day), new BigDecimal(volume));
    }
}
