package cloud.cholewa.water.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

//The client of database-service (the device registry, HAS-316). Built from a clone of the injected builder, so the calls
//are traced and logged by Logbook (cholewa-commons >= 1.10.0) and the builder the Shelly clients get is left as it was; the
//timeouts keep a silent database-service from holding a scheduled pass until its bound. The shape of the client of
//energy-service
@Configuration
public class DatabaseServiceClientConfig {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;

    //Without a limit an idle connection stays in the pool for ever: after a restart of database-service, or once
    //conntrack has forgotten it, the next call is written onto a dead socket (the lesson of the gateway, HAS-212)
    static final Duration MAX_IDLE_TIME = Duration.ofMinutes(2);
    static final Duration MAX_LIFE_TIME = Duration.ofMinutes(30);

    //Spring infers only close() and shutdown() as destroy methods, so the pool is named explicitly
    @Bean(destroyMethod = "dispose")
    ConnectionProvider databaseServiceConnectionProvider() {
        return ConnectionProvider.builder("database-service")
            .maxIdleTime(MAX_IDLE_TIME)
            .maxLifeTime(MAX_LIFE_TIME)
            .build();
    }

    @Bean
    WebClient databaseServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider databaseServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        final HttpClient httpClient = HttpClient.create(databaseServiceConnectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);

        return webClientBuilder.clone()
            .baseUrl(services.database().toString())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
