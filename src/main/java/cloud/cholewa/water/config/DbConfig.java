package cloud.cholewa.water.config;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@RequiredArgsConstructor
@EnableR2dbcRepositories
@EnableConfigurationProperties(DatabaseProperties.class)
public class DbConfig {

    private final DatabaseProperties databaseProperties;

    @Bean
    ConnectionFactory connectionFactory() {
        return ConnectionFactories.get(ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, databaseProperties.host())
            .option(ConnectionFactoryOptions.PORT, databaseProperties.port())
            .option(ConnectionFactoryOptions.DATABASE, databaseProperties.name())
            .option(ConnectionFactoryOptions.USER, databaseProperties.username())
            .option(ConnectionFactoryOptions.PASSWORD, databaseProperties.password())
            .option(Option.valueOf("sslMode"), "REQUIRE")
            .build()
        );
    }
}
