package cloud.cholewa.water.light;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//The read of a room of light-service (HAS-405), on the shape light-service 0.11.0 answers - the rest of it ignored; an
//error is never a room without lights or motion
class LightClientTest {

    private final ListAppender<ILoggingEvent> clientLog = new ListAppender<>();
    private MockWebServer server;
    private LightClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new LightClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
        clientLog.start();
        ((Logger) LoggerFactory.getLogger(LightClient.class)).addAppender(clientLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(LightClient.class)).detachAppender(clientLog);
        server.close();
    }

    @Test
    void should_read_the_lights_and_the_last_motion_of_a_room() throws InterruptedException {
        server.enqueue(json("""
            {"room":"bathroom up","anyOn":false,"lights":[{"id":42,"name":"Ceiling","on":false}],
             "occupancy":{"room":"bathroom up","lastMotionAt":"2026-11-06T11:08:47","motion":false,"lux":137.0,
             "sensors":[{"id":100,"kind":"motion sensor"}]}}
            """));

        client.room("bathroom up").as(StepVerifier::create)
            .assertNext(room -> {
                assertThat(room.anyOn()).isFalse();
                assertThat(room.lastMotionAt()).isEqualTo(LocalDateTime.of(2026, 11, 6, 11, 8, 47));
            })
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/light/rooms/bathroom%20up");
    }

    //a room without a motion sensor: no lastMotionAt - not known, never a motion
    @Test
    void should_read_a_room_without_a_motion() {
        server.enqueue(json("""
            {"room":"bathroom down","anyOn":true,"occupancy":{"room":"bathroom down","sensors":[]}}
            """));

        client.room("bathroom down").as(StepVerifier::create)
            .assertNext(room -> {
                assertThat(room.anyOn()).isTrue();
                assertThat(room.lastMotionAt()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_fail_when_light_service_fails() {
        server.enqueue(new MockResponse.Builder().code(404).addHeader("Content-Type", "application/json")
            .body("{\"errors\":[{\"message\":\"No lights\",\"code\":\"NOT_FOUND_LIGHT\"}]}").build());

        client.room("bathroom up").as(StepVerifier::create)
            .expectErrorMessage("light-service answered 404")
            .verify();

        assertThat(clientLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).startsWith("light-service answered 404 to the read of the room bathroom up");
        });
    }

    @Test
    void should_fail_without_a_body() {
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.room("bathroom up").as(StepVerifier::create)
            .expectErrorMessage("light-service answered without the room bathroom up")
            .verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build();
    }
}
