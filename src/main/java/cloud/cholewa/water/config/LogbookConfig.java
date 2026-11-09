package cloud.cholewa.water.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.BodyFilter;
import org.zalando.logbook.core.BodyFilters;
import org.zalando.logbook.json.JsonBodyFilters;

import java.util.Set;

//What Logbook may not write (HAS-397). The login of the iQua cloud sends the e-mail and the password of the account, its
//answer carries the access and the refresh token, and a device of the cloud comes with the account and the address of the
//house - masked by name, strings and numbers alike. Named apart from the bean of Logbook, which steps back for it
//(ConditionalOnMissingBean) - so its default filter (an access_token of JSON, a client_secret of a form) is merged in here.
//The Authorization header is masked by the default header filter of Logbook. Pinned by LogbookConfigTest
@Configuration
public class LogbookConfig {

    static final String MASK = "XXX";
    static final Set<String> MASKED = Set.of("email", "password", "access_token", "refresh_token", "first_name",
        "last_name", "phone_number", "address1", "address2", "city", "zip_postal_code");

    @Bean
    BodyFilter maskingBodyFilter() {
        return BodyFilter.merge(BodyFilters.defaultValue(),
            JsonBodyFilters.replacePrimitiveJsonProperty(MASKED::contains, MASK));
    }
}
