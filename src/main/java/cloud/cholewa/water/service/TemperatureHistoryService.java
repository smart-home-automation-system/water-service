package cloud.cholewa.water.service;

import cloud.cholewa.water.database.repository.WaterTemperatureRepository;
import cloud.cholewa.water.model.TemperatureHistoryReply;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TemperatureHistoryService {

    private final WaterTemperatureRepository repository;

    /**
     * The stored temperatures of the hot water and the circulation from {@code from} up to, not
     * including, {@code to}, as averages of buckets whose width follows from the length of the
     * range. A range without a reading is answered with no points.
     */
    public Mono<TemperatureHistoryReply> queryHistory(final LocalDateTime from, final LocalDateTime to) {
        return Mono.defer(() -> HistoryRange.violation(from, to)
            .<Mono<TemperatureHistoryReply>>map(Mono::error)
            .orElseGet(() -> {
                final long bucketSeconds = HistoryRange.bucket(from, to).toSeconds();

                return repository.findHistory(from, to, bucketSeconds)
                    .map(bucket -> new TemperatureHistoryReply.Point(
                        bucket.at(), bucket.water(), bucket.circulation()))
                    .collectList()
                    .map(points -> new TemperatureHistoryReply(from, to, bucketSeconds, points));
            }));
    }
}
