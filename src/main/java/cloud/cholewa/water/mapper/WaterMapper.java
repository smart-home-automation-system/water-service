package cloud.cholewa.water.mapper;

import cloud.cholewa.shelly.model.ShellyUniStatusResponse;
import cloud.cholewa.water.database.model.WaterTemperatureEntity;
import cloud.cholewa.water.model.TemperatureReply;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface WaterMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "updatedAt", expression = "java(java.time.LocalDateTime.now())")
    @Mapping(target = "water", expression = "java(response.getExtTemperature().get(\"0\").gettC())")
    @Mapping(target = "circulation", expression = "java(response.getExtTemperature().get(\"1\").gettC())")
    WaterTemperatureEntity toEntity(ShellyUniStatusResponse response);

    @Mapping(target = "water.temperature", source = "water")
    @Mapping(target = "circulation.temperature", source = "circulation")
    TemperatureReply toReply(WaterTemperatureEntity entity);
}
