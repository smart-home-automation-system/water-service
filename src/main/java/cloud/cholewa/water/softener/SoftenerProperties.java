package cloud.cholewa.water.softener;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The water softener from the iQua cloud (HAS-397).
//serialNumber - picks one softener of several on the account; left out, the first one of the account is read
//cloud.baseUrl - the API of the iQua application (https://api.myiquaapp.com/v1)
//cloud.email, cloud.password - the iQua account (secret water, keys iqua-email / iqua-password, no default)
//cloud.interval - the read of the softener (PT5M; 1 - 60 min, a bare number is minutes - the cloud blocks an account above
//about 1000 calls a day)
//offlineAfter - no reading of the softener online for this long is the offline alarm (PT30M; 5 min - 24 h, a bare number
//is minutes - HAS-399; the softener drops off Wi-Fi for minutes on its own). Pinned by SoftenerPropertiesTest
@Validated
@ConfigurationProperties(prefix = "water.softener")
public record SoftenerProperties(
    String serialNumber,
    @NotNull @Valid Cloud cloud,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 24) Duration offlineAfter
) {

    public record Cloud(
        @NotNull URI baseUrl,
        @NotBlank String email,
        @NotBlank String password,
        @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 1) Duration interval
    ) {

        //the password never reaches a log through the record
        @Override
        public String toString() {
            return "Cloud[baseUrl=" + baseUrl + ", interval=" + interval + "]";
        }
    }
}
