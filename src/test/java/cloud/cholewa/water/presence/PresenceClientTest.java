package cloud.cholewa.water.presence;

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

//The read of where the members are (HAS-320), on the shape presence-service 0.6.1 answers; an error is never an empty
//household, which would read as a house nobody is in
class PresenceClientTest {

    private final ListAppender<ILoggingEvent> clientLog = new ListAppender<>();
    private MockWebServer server;
    private PresenceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new PresenceClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
        clientLog.start();
        ((Logger) LoggerFactory.getLogger(PresenceClient.class)).addAppender(clientLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(PresenceClient.class)).detachAppender(clientLog);
        server.close();
    }

    @Test
    void should_read_the_residents() throws InterruptedException {
        server.enqueue(json("""
            [{"name":"Alex","present":false,"since":"2026-11-05T08:10:00","lastCheckedAt":"2026-11-05T09:59:00"}]
            """));

        client.residents().as(StepVerifier::create)
            .assertNext(residents -> assertThat(residents).containsExactly(new ResidentPresence("Alex", false,
                LocalDateTime.of(2026, 11, 5, 8, 10), LocalDateTime.of(2026, 11, 5, 9, 59))))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/presence/residents/presence");
    }

    @Test
    void should_fail_when_presence_service_fails() {
        server.enqueue(new MockResponse.Builder().code(503).addHeader("Content-Type", "application/json")
            .body("{\"errors\":[{\"message\":\"boom\"}]}").build());

        client.residents().as(StepVerifier::create)
            .expectErrorMessage("presence-service answered 503")
            .verify();

        assertThat(clientLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).startsWith("presence-service answered 503 to the read of the residents");
        });
    }

    @Test
    void should_fail_without_a_body() {
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.residents().as(StepVerifier::create)
            .expectErrorMessage("presence-service answered without a list")
            .verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build();
    }
}
