package cloud.cholewa.water.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

//The client of presence-service (whether anybody is at home, HAS-320) - the shape of the client of database-service: a
//clone of the injected builder (traced and logged), timeouts that keep a silent presence-service from holding the pass
//of the alarms, and a pool that closes idle and old connections (HAS-212)
@Configuration
public class PresenceServiceClientConfig {

    @Bean(destroyMethod = "dispose")
    ConnectionProvider presenceServiceConnectionProvider() {
        return ConnectionProvider.builder("presence-service")
            .maxIdleTime(DatabaseServiceClientConfig.MAX_IDLE_TIME)
            .maxLifeTime(DatabaseServiceClientConfig.MAX_LIFE_TIME)
            .build();
    }

    @Bean
    WebClient presenceServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider presenceServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        final HttpClient httpClient = HttpClient.create(presenceServiceConnectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, DatabaseServiceClientConfig.CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(DatabaseServiceClientConfig.RESPONSE_TIMEOUT);

        return webClientBuilder.clone()
            .baseUrl(services.presence().toString())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
