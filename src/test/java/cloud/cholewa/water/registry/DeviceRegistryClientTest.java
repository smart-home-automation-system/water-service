package cloud.cholewa.water.registry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.DeviceKind;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

//The read of the water valves of the device registry (HAS-316), on the shape database-service 0.21.0 answers
class DeviceRegistryClientTest {

    private final ListAppender<ILoggingEvent> clientLog = new ListAppender<>();
    private MockWebServer server;
    private DeviceRegistryClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new DeviceRegistryClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
        clientLog.start();
        ((Logger) LoggerFactory.getLogger(DeviceRegistryClient.class)).addAppender(clientLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(DeviceRegistryClient.class)).detachAppender(clientLog);
        server.close();
    }

    @Test
    void should_read_the_switched_on_water_valves() throws InterruptedException {
        server.enqueue(json("""
            [{"id":139,"name":"Water valve","room":"garage","kind":"water valve","enabled":true,"vendor":"shelly",
              "shelly":{"address":"192.0.2.15","generation":3,"deviceId":"neowatervalve-a1b2c3d4e5f6","channel":0}}]
            """));

        client.getWaterValves().as(StepVerifier::create)
            .assertNext(devices -> assertThat(devices).singleElement().satisfies(device -> {
                assertThat(device.getId()).isEqualTo(139L);
                assertThat(device.getKind()).isEqualTo(DeviceKind.WATER_VALVE);
                assertThat(device.getShelly().getDeviceId()).isEqualTo("neowatervalve-a1b2c3d4e5f6");
                assertThat(device.getShelly().getAddress()).isEqualTo("192.0.2.15");
            }))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/device/registry");
        assertThat(request.getUrl().queryParameter("kind")).isEqualTo("water valve");
        assertThat(request.getUrl().queryParameter("enabled")).isEqualTo("true");
    }

    @Test
    void should_read_no_valve_as_an_empty_list() {
        server.enqueue(json("[]"));

        client.getWaterValves().as(StepVerifier::create)
            .assertNext(devices -> assertThat(devices).isEmpty())
            .verifyComplete();
    }

    //an error is never an empty list - that would forget the valve; what the downstream said is a WARN
    @Test
    void should_fail_when_database_service_fails() {
        server.enqueue(new MockResponse.Builder().code(500).addHeader("Content-Type", "application/json")
            .body("{\"errors\":[{\"message\":\"boom\"}]}").build());

        client.getWaterValves().as(StepVerifier::create)
            .expectErrorMessage("database-service answered 500")
            .verify();

        assertThat(clientLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).startsWith("database-service answered 500 to the read of the water valves");
        });
    }

    @Test
    void should_fail_on_an_answer_without_a_body() {
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.getWaterValves().as(StepVerifier::create)
            .expectErrorMessage("database-service answered without a list")
            .verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build();
    }
}
