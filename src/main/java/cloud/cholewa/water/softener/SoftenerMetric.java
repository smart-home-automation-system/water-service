package cloud.cholewa.water.softener;

import java.util.Arrays;
import java.util.Optional;

//The metrics of the history of the softener (HAS-398): the name of the API and the column of softener_reading it is read
//from - the only source of a column name in the SQL of the history, never the request
public enum SoftenerMetric {

    SALT_LEVEL("saltLevel", "salt_level_percent"),
    WATER_USED_TODAY("waterUsedToday", "used_today_litres"),
    TOTAL_WATER("totalWater", "total_litres"),
    TREATED_WATER_AVAILABLE("treatedWaterAvailable", "treated_available_litres"),
    FLOW("flow", "flow_litres_per_minute"),
    HARDNESS("hardness", "hardness_gpg");

    private final String apiName;
    private final String column;

    SoftenerMetric(final String apiName, final String column) {
        this.apiName = apiName;
        this.column = column;
    }

    public String apiName() {
        return apiName;
    }

    public String column() {
        return column;
    }

    //exactly as the API names it, case included
    public static Optional<SoftenerMetric> ofApiName(final String name) {
        return Arrays.stream(values()).filter(metric -> metric.apiName.equals(name)).findFirst();
    }
}
