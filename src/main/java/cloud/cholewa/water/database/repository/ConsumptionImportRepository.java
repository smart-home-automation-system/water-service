package cloud.cholewa.water.database.repository;

import cloud.cholewa.water.model.ImportedDay;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

//The daily consumption imported from the Shelly cloud (HAS-318, V4) and the first day of the readings of the house it must
//stay before. PostgreSQL only - ConsumptionImportRepositoryTest runs it against a PostgreSQL in Docker
@Repository
@RequiredArgsConstructor
public class ConsumptionImportRepository {

    private final DatabaseClient databaseClient;

    public Flux<ImportedDay> findAll() {
        return databaseClient.sql("SELECT day, volume_m3 FROM water_consumption_import ORDER BY day")
            .map(row -> new ImportedDay(row.get("day", LocalDate.class), row.get("volume_m3", BigDecimal.class)))
            .all();
    }

    //the day of the oldest reading of the counter; empty while there is none
    public Mono<LocalDate> firstReadingDay() {
        return databaseClient.sql("SELECT min(measured_at) AS first FROM water_meter_reading")
            .map(row -> Optional.ofNullable(row.get("first", LocalDateTime.class)))
            .one()
            .flatMap(Mono::justOrEmpty)
            .map(LocalDateTime::toLocalDate);
    }

    //the whole import replaced by these days, in one transaction: sent again, it fixes what was sent before
    @Transactional
    public Mono<Void> replaceAll(final List<ImportedDay> days) {
        return databaseClient.sql("DELETE FROM water_consumption_import").then()
            .then(Mono.just(days).filter(list -> !list.isEmpty()).flatMap(this::insert));
    }

    private Mono<Void> insert(final List<ImportedDay> days) {
        final String values = IntStream.range(0, days.size())
            .mapToObj(index -> "(:day%d, :volume%d)".formatted(index, index))
            .collect(Collectors.joining(", "));
        DatabaseClient.GenericExecuteSpec spec =
            databaseClient.sql("INSERT INTO water_consumption_import (day, volume_m3) VALUES " + values);
        for (int index = 0; index < days.size(); index++) {
            spec = spec.bind("day" + index, days.get(index).day()).bind("volume" + index, days.get(index).volume());
        }
        return spec.then();
    }
}
