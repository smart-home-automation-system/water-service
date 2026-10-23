package cloud.cholewa.water.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

//Off under the test profile (HAS-307): a @SpringBootTest context ran the reading ten seconds after its
//start, against the real Shelly Uni of the house - it had @EnableScheduling on the application class
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
