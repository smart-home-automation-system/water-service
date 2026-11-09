package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//The answer of a command of the regeneration (HAS-400, 202): command - what the house told the softener; status, message -
//what the iQua cloud answered, as it words it (left out when it did not); sentAt - on the clock of the house
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SoftenerCommandReply(String command, String status, String message, LocalDateTime sentAt) {
}
