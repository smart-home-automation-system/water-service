package cloud.cholewa.water.softener;

import lombok.Getter;

//A failed call to the iQua cloud (HAS-397): a fixed message with the HTTP status at most - never the body, which the JSON
//logs would store (a cloud may quote a token or the account back). status - the HTTP status the cloud answered, null when
//it did not answer or the answer was not the model
@Getter
public class IquaException extends RuntimeException {

    private final Integer status;

    public IquaException(final String message) {
        this(message, null);
    }

    public IquaException(final String message, final Integer status) {
        super(message, null, false, false);
        this.status = status;
    }
}
