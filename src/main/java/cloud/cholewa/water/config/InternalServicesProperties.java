package cloud.cholewa.water.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

//The services this one calls inside the cluster, by k8s DNS (HAS-316). database - database-service, the device registry
//the water valve (the kind water valve, HAS-311) is read from
@Validated
@ConfigurationProperties("internal.service")
public record InternalServicesProperties(@NotNull URI database) {
}
