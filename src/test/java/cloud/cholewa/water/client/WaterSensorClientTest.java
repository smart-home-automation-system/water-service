package cloud.cholewa.water.client;

import cloud.cholewa.water.config.WaterSensorConfig;
import cloud.cholewa.water.infrastructure.error.WaterException;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class WaterSensorClientTest {

    private MockWebServer mockWebServer;
    private WaterSensorClient sut;

    @BeforeEach
    @SneakyThrows
    void setUp() {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WaterSensorConfig config = new WaterSensorConfig(mockWebServer.getHostName(), mockWebServer.getPort());
        sut = new WaterSensorClient(config, WebClient.create());
    }

    @Test
    void should_return_temperature_values() {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(HttpStatus.OK.value())
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .setBody("""
                {
                    "ext_temperature": {
                        "0": {
                            "tC": 41.75
                        },
                        "1": {
                            "tC": 15.94
                        }
                    }
                }
                """)
        );

        sut.getTemperatures()
            .as(StepVerifier::create)
            .assertNext(temperature -> {
                assertThat(temperature.getExtTemperature()).isNotNull();
                assertThat(temperature.getExtTemperature().get("0").gettC()).isEqualTo(41.75);
                assertThat(temperature.getExtTemperature().get("1").gettC()).isEqualTo(15.94);
            })
            .verifyComplete();
    }

    @Test
    void should_return_error_status() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()));

        sut.getTemperatures()
            .as(StepVerifier::create)
            .verifyErrorSatisfies(throwable -> assertThat(throwable).isInstanceOf(WaterException.class));
    }
}
