package cloud.cholewa.water.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
public class AppConfig {

    //the longest silence while the answer of the Shelly Uni is read (HAS-307, the value of boiler-service): a
    //sensor that takes the connection and never answers fails the poll after it, instead of holding the pass
    //until its bound. netty's timeout - between reads, not for the whole call
    static final Duration SHELLY_RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    ConnectionProvider waterConnectionProvider() {
        return ConnectionProvider.builder("waterConnectionProvider")
            .maxConnections(50)
            .build();
    }

    @Bean
    HttpClient waterHttpClient(final ConnectionProvider waterConnectionProvider) {
        return HttpClient.create(waterConnectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .responseTimeout(SHELLY_RESPONSE_TIMEOUT);
    }

    @Bean
    WebClient shellyWebClient(final WebClient.Builder webClientBuilder, final HttpClient waterHttpClient) {
        return webClientBuilder
            .clientConnector(new ReactorClientHttpConnector(waterHttpClient))
            .build();
    }
}
