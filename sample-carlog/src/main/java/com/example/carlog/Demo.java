package com.example.carlog;

import java.time.LocalDate;

/**
 * A short scripted maintenance session, so {@code ./gradlew listMaintainedCars} and
 * {@code ./gradlew resetMaintenanceLog} have real data to work with.
 *
 * <p>Run it with {@code ./gradlew recordDemoMaintenance}.
 */
public final class Demo {

    private Demo() {
    }

    public static void main(String[] args) {
        LocalDate today = LocalDate.now();

        CarLog honda = new CarLog("Honda");
        honda.recordFillTank(today);
        honda.recordFillTank(today.minusWeeks(2));
        honda.recordRotateTires(today.minusMonths(7));
        honda.recordCleanWindshield(today);

        CarLog subaru = new CarLog("Subaru");
        subaru.recordChangeOil(today.minusMonths(4));
        subaru.recordReplaceWipers(today.minusYears(2));
        subaru.recordFillTank(today.minusDays(3));

        // Each query prints its own answer, including "never" for jobs not yet done.
        System.out.println();
        honda.whenLastFillTank();
        subaru.whenLastReplaceWipers();
        subaru.whenLastRotateTires();

        System.out.println();
        System.out.println("Cars on file: " + CarLog.maintainedCars());
    }
}
