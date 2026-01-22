package cloud.cholewa.water.service;

import cloud.cholewa.home.model.SystemActiveReply;
import cloud.cholewa.shelly.model.ExternalTemperatureValue;
import cloud.cholewa.shelly.model.ShellyUniStatusResponse;
import cloud.cholewa.water.client.WaterSensorClient;
import cloud.cholewa.water.database.model.WaterTemperatureEntity;
import cloud.cholewa.water.database.repository.WaterTemperatureRepository;
import cloud.cholewa.water.infrastructure.error.WaterException;
import cloud.cholewa.water.mapper.WaterMapper;
import cloud.cholewa.water.model.CirculationStatus;
import cloud.cholewa.water.model.HotWaterStatus;
import cloud.cholewa.water.model.TemperatureReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaterServiceTest {

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private WaterSensorClient waterSensorClient;
    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private WaterMapper mapper;
    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private WaterTemperatureRepository repository;

    @InjectMocks
    private WaterService sut;

    @Test
    void should_return_false_when_water_heating_is_initially_disabled() {

        sut.queryWaterSystemActive()
            .as(StepVerifier::create)
            .assertNext(reply ->
                assertThat(reply).isInstanceOf(SystemActiveReply.class)
                    .satisfies(r -> assertThat(r.getActive()).isFalse())
            )
            .verifyComplete();

        verifyNoInteractions(waterSensorClient, mapper, repository);
    }

    @Test
    void should_return_true_when_water_heating_is_enabled() {
        ReflectionTestUtils.setField(sut, "waterHeatingEnabled", true);

        sut.queryWaterSystemActive()
            .as(StepVerifier::create)
            .assertNext(reply ->
                assertThat(reply).isInstanceOf(SystemActiveReply.class)
                    .satisfies(r -> assertThat(r.getActive()).isTrue())
            )
            .verifyComplete();

        verifyNoInteractions(waterSensorClient, mapper, repository);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("temperatures")
    void should_save_temperature_entity_when_sensor_data_received(
        final String name,
        final boolean initialHeatingStatus,
        final double waterTemperature,
        final double circulationTemperature,
        final boolean expectedHeatingStatus
    ) {
        ReflectionTestUtils.setField(sut, "waterHeatingEnabled", initialHeatingStatus);

        when(waterSensorClient.getTemperatures())
            .thenReturn(Mono.just(ShellyUniStatusResponse.builder()
                .extTemperature(Map.ofEntries(
                        Map.entry("0", ExternalTemperatureValue.builder().tC(waterTemperature).build()),
                        Map.entry("1", ExternalTemperatureValue.builder().tC(circulationTemperature).build())
                    )
                )
                .build()));

        when(mapper.toEntity(any()))
            .thenReturn(new WaterTemperatureEntity(1L, LocalDateTime.now(), waterTemperature, circulationTemperature));

        when(repository.save(any()))
            .thenReturn(Mono.just(new WaterTemperatureEntity(
                1L,
                LocalDateTime.now(),
                waterTemperature,
                circulationTemperature
            )));

        sut.handleWaterUpdate()
            .as(StepVerifier::create)
            .verifyComplete();

        boolean enabled = (boolean) ReflectionTestUtils.getField(sut, "waterHeatingEnabled");

        verify(waterSensorClient, times(1)).getTemperatures();
        verify(mapper, times(1)).toEntity(any());
        verify(repository, times(1)).save(any());
        verifyNoMoreInteractions(waterSensorClient, mapper, repository);

        assertThat(enabled).isEqualTo(expectedHeatingStatus);
    }

    private static Stream<Arguments> temperatures() {
        return Stream.of(
            Arguments.of("should enable water heating when temperature is below 38", false, 37.9, 10, true),
            Arguments.of("should disable water heating when temperature is above 42", true, 42.1, 10, false),
            Arguments.of(
                "should not change water heating when temperature is between 38 - 42 when on",
                true,
                38,
                10,
                true
            ),
            Arguments.of(
                "should not change water heating when temperature is between 38 - 42 when off",
                false,
                42,
                10,
                false
            )
        );
    }

    @Test
    void should_handle_error_when_sensor_client_fails() {
        when(waterSensorClient.getTemperatures()).thenReturn(Mono.error(new WaterException("Error fetching temperatures")));

        sut.handleWaterUpdate()
            .as(StepVerifier::create)
            .verifyComplete();

        verify(waterSensorClient, times(1)).getTemperatures();
        verifyNoMoreInteractions(waterSensorClient, mapper, repository);
    }

    @Test
    void should_handle_error_when_repository_save_fails() {
        when(waterSensorClient.getTemperatures())
            .thenReturn(Mono.just(ShellyUniStatusResponse.builder()
                .extTemperature(Map.ofEntries(
                        Map.entry("0", ExternalTemperatureValue.builder().tC(22.0).build()),
                        Map.entry("1", ExternalTemperatureValue.builder().tC(11.0).build())
                    )
                )
                .build()));

        when(mapper.toEntity(any()))
            .thenReturn(new WaterTemperatureEntity(1L, LocalDateTime.now(), 22, 11));

        when(repository.save(any()))
            .thenReturn(Mono.error(new SQLException("Error saving temperature")));

        sut.handleWaterUpdate()
            .as(StepVerifier::create)
            .verifyComplete();

        verify(waterSensorClient, times(1)).getTemperatures();
        verify(mapper, times(1)).toEntity(any());
        verify(repository, times(1)).save(any());
        verifyNoMoreInteractions(waterSensorClient, mapper, repository);
    }

    @Test
    void should_return_empty_mono_when_no_temperature_data_in_database() {
        when(repository.findFirstByOrderByUpdatedAtDesc()).thenReturn(Mono.empty());

        sut.queryWaterSystemTemperature()
            .as(StepVerifier::create)
            .verifyComplete();

        verify(repository, times(1)).findFirstByOrderByUpdatedAtDesc();
        verifyNoMoreInteractions(waterSensorClient, mapper, repository);
    }

    @Test
    void should_correctly_map_entity_to_reply() {
        when(mapper.toReply(any()))
            .thenReturn(TemperatureReply.builder()
                .water(new HotWaterStatus(22.0))
                .circulation(new CirculationStatus(11.0, false))
                .build());

        when(repository.findFirstByOrderByUpdatedAtDesc())
            .thenReturn(Mono.just(new WaterTemperatureEntity(1L, LocalDateTime.now(), 22, 11)));

        sut.queryWaterSystemTemperature()
            .as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply).isInstanceOf(TemperatureReply.class))
            .verifyComplete();

        verify(mapper, times(1)).toReply(any());
        verify(repository, times(1)).findFirstByOrderByUpdatedAtDesc();
        verifyNoMoreInteractions(waterSensorClient, mapper, repository);
    }
}
