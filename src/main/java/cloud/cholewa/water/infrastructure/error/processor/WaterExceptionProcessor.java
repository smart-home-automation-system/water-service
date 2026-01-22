package cloud.cholewa.water.infrastructure.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import cloud.cholewa.water.infrastructure.error.WaterException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.Collections;

@Slf4j
public class WaterExceptionProcessor implements ExceptionProcessor {
    @Override
    public Errors apply(final Throwable throwable) {
        Exception waterException = (WaterException) throwable;

        log.error("Water handling exception: {}", waterException.getLocalizedMessage());
        return Errors.builder()
            .httpStatus(HttpStatus.BAD_REQUEST)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message("Water handling exception")
                    .details(waterException.getLocalizedMessage())
                    .build()
            ))
            .build();
    }
}
