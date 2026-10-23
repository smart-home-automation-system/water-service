package cloud.cholewa.water.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-307: the Shelly Uni that takes the connection and never answers - the poll fails after the response
//timeout instead of hanging until the bound of the scheduled pass
class AppConfigTest {

    private final AppConfig appConfig = new AppConfig();
    private final List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());
    private ConnectionProvider connectionProvider;
    private ServerSocket silentDevice;

    @BeforeEach
    void setUp() throws IOException {
        connectionProvider = appConfig.waterConnectionProvider();
        //takes every connection and never writes a byte
        silentDevice = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!silentDevice.isClosed()) {
                try {
                    accepted.add(silentDevice.accept());
                } catch (IOException ignored) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        silentDevice.close();
        for (final Socket socket : List.copyOf(accepted)) {
            socket.close();
        }
        connectionProvider.dispose();
    }

    @Test
    void should_give_the_client_of_the_shelly_a_response_timeout() {
        final HttpClient httpClient = appConfig.waterHttpClient(connectionProvider);

        assertThat(httpClient.configuration().responseTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void should_end_a_call_the_device_never_answers() {
        final WebClient webClient = appConfig.shellyWebClient(WebClient.builder(), appConfig.waterHttpClient(connectionProvider));

        final Duration took = webClient.get()
            .uri("http://localhost:" + silentDevice.getLocalPort() + "/status")
            .retrieve()
            .toBodilessEntity()
            .as(StepVerifier::create)
            .expectError()
            .verify(Duration.ofSeconds(15));

        assertThat(took).isLessThan(Duration.ofSeconds(10));
    }
}
