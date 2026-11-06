package cloud.cholewa.water.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

//The services this one calls inside the cluster, by k8s DNS. database - database-service, the device registry the water
//valve (the kind water valve, HAS-311) is read from (HAS-316); presence - presence-service, whether anybody is at home
//for the flow of the empty house (HAS-320) and the automation of the circulation (HAS-405); light - light-service, the
//motion and the lights of the bathrooms (HAS-405)
@Validated
@ConfigurationProperties("internal.service")
public record InternalServicesProperties(@NotNull URI database, @NotNull URI presence, @NotNull URI light) {
}
