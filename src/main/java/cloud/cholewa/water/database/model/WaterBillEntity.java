package cloud.cholewa.water.database.model;

import lombok.Builder;
import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

//A bill of the waterworks (HAS-318, V4): a period of whole days, both ends included
@Builder
@With
@Table("water_bill")
public record WaterBillEntity(
    @Id
    Long id,
    LocalDate periodFrom,
    LocalDate periodTo,
    @Column("billed_volume_m3")
    BigDecimal billedVolume,
    @Column("meter_reading_m3")
    BigDecimal meterReading,
    BigDecimal amount,
    String note,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
