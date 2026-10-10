package cloud.cholewa.water.mapper;

import cloud.cholewa.water.database.model.WaterTemperatureEntity;
import cloud.cholewa.water.model.CirculationStatus;
import cloud.cholewa.water.model.HotWaterStatus;
import cloud.cholewa.water.model.TemperatureReply;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WaterMapperTest {

    private final WaterMapper sut = Mappers.getMapper(WaterMapper.class);

    //the time of the reply is the time of the row, never the time of the call: the service answers
    //its last row for as long as the sensor is silent. To the second: the row has microseconds
    @Test
    void should_answer_a_reading_with_the_time_it_was_stored_with() {
        final LocalDateTime storedAt = LocalDateTime.of(2026, 10, 9, 14, 21, 10);

        assertThat(sut.toReply(new WaterTemperatureEntity(7L, storedAt.plusNanos(729_176_000), 46.81, 26.44)))
            .isEqualTo(new TemperatureReply(storedAt, new HotWaterStatus(46.81), new CirculationStatus(26.44, false)));
    }
}
