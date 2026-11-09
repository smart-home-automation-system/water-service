package cloud.cholewa.water.infrastructure.error.processor;

import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import cloud.cholewa.water.infrastructure.error.ValveException;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;

//Answers a refused request of the water valve with the status and the code of its cause, its details answered; a WARN
@Slf4j
public class ValveExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        final ValveException exception = (ValveException) throwable;
        final String code = ErrorId.codeOf(exception.getError());

        log.warn("Water request refused ({}, {}): {}", exception.getHttpStatus().value(), code,
            exception.getMessage());

        return Errors.builder()
            .httpStatus(exception.getHttpStatus())
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message(exception.getError().getDescription())
                    .details(exception.getMessage())
                    .code(code)
                    .build()
            ))
            .build();
    }
}
