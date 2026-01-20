package cloud.cholewa.water.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class AppConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    ConnectionProvider waterConnectionProvider() {
        return ConnectionProvider.builder("waterConnectionProvider")
            .maxConnections(50)
            .build();
    }

    @Bean
    HttpClient waterHttpClient(final ConnectionProvider waterConnectionProvider) {
        return HttpClient.create(waterConnectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
    }

    @Bean
    WebClient webClient(final WebClient.Builder webClientBuilder, final HttpClient waterHttpClient) {
        return webClientBuilder
            .clientConnector(new ReactorClientHttpConnector(waterHttpClient))
            .build();
    }
}
