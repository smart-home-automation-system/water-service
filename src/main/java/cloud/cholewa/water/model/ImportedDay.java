package cloud.cholewa.water.model;

import java.math.BigDecimal;
import java.time.LocalDate;

//The water of one day from before the readings of the house, imported from the Shelly cloud (HAS-318): m3, 3 decimals
public record ImportedDay(LocalDate day, BigDecimal volume) {
}
