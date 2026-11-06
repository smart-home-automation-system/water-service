package cloud.cholewa.water.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

//The client of light-service (the motion and the lights of the bathrooms, HAS-405) - the shape of the client of
//presence-service: a clone of the injected builder (traced and logged), timeouts that keep a silent light-service from
//holding the pass of the automation, and a pool that closes idle and old connections (HAS-212)
@Configuration
public class LightServiceClientConfig {

    @Bean(destroyMethod = "dispose")
    ConnectionProvider lightServiceConnectionProvider() {
        return ConnectionProvider.builder("light-service")
            .maxIdleTime(DatabaseServiceClientConfig.MAX_IDLE_TIME)
            .maxLifeTime(DatabaseServiceClientConfig.MAX_LIFE_TIME)
            .build();
    }

    @Bean
    WebClient lightServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider lightServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        final HttpClient httpClient = HttpClient.create(lightServiceConnectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, DatabaseServiceClientConfig.CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(DatabaseServiceClientConfig.RESPONSE_TIMEOUT);

        return webClientBuilder.clone()
            .baseUrl(services.light().toString())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
