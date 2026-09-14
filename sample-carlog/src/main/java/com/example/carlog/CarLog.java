package com.example.carlog;

import com.example.carlog.reporting.ReportFormatter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.apache.commons.lang3.StringUtils;

/**
 * Records and queries maintenance performed on a single named car.
 *
 * <p>Every record is appended to a plain-text log shared by all cars, so a {@code CarLog}
 * created today sees maintenance recorded by a {@code CarLog} created last week:
 *
 * <pre>{@code
 * CarLog newLog = new CarLog("Honda");
 * newLog.recordFillTank(LocalDate.of(2026, 8, 17));
 * newLog.recordRotateTires(LocalDate.of(2025, 3, 1));
 * assert newLog.whenLastFillTank().equals(LocalDate.of(2026, 8, 17));
 * }</pre>
 *
 * <p>The actual maintenance is simulated with console output. Accepting an explicit date
 * on every {@code record*} method makes it possible to backfill history and to write
 * deterministic tests.
 *
 * <p>This class is not thread safe and does not lock the log file. Concurrent writers from
 * separate processes will interleave.
 */
public class CarLog {

    /** System property that overrides the default log location. */
    public static final String LOG_FILE_PROPERTY = "carlog.file";

    /** Log file used when {@link #LOG_FILE_PROPERTY} is not set, resolved against the working directory. */
    public static final String DEFAULT_LOG_FILE = "car-maintenance-log.txt";

    private static final String FIELD_SEPARATOR = "|";

    private final String carName;
    private final Path logFile;

    /** Creates a log for {@code carName} backed by the default maintenance log file. */
    public CarLog(String carName) {
        this(carName, defaultLogFile());
    }

    /** Creates a log for {@code carName} backed by an explicit log file. Useful in tests. */
    public CarLog(String carName, Path logFile) {
        if (StringUtils.isBlank(carName)) {
            throw new IllegalArgumentException("Car name must not be blank");
        }
        if (carName.contains(FIELD_SEPARATOR)) {
            throw new IllegalArgumentException("Car name must not contain '" + FIELD_SEPARATOR + "': " + carName);
        }
        this.carName = carName.trim();
        this.logFile = logFile;
        System.out.println(ReportFormatter.banner(this.carName));
    }

    /** The name this log was created with. */
    public String carName() {
        return carName;
    }

    /** The file this log reads from and appends to. */
    public Path logFile() {
        return logFile;
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    public void recordFillTank(LocalDate on) {
        record(MaintenanceType.FILL_TANK, on);
    }

    public void recordFillTank() {
        recordFillTank(LocalDate.now());
    }

    public void recordRotateTires(LocalDate on) {
        record(MaintenanceType.ROTATE_TIRES, on);
    }

    public void recordRotateTires() {
        recordRotateTires(LocalDate.now());
    }

    public void recordCleanWindshield(LocalDate on) {
        record(MaintenanceType.CLEAN_WINDSHIELD, on);
    }

    public void recordCleanWindshield() {
        recordCleanWindshield(LocalDate.now());
    }

    public void recordChangeOil(LocalDate on) {
        record(MaintenanceType.CHANGE_OIL, on);
    }

    public void recordChangeOil() {
        recordChangeOil(LocalDate.now());
    }

    public void recordReplaceWipers(LocalDate on) {
        record(MaintenanceType.REPLACE_WIPERS, on);
    }

    public void recordReplaceWipers() {
        recordReplaceWipers(LocalDate.now());
    }

    /** Records {@code type} as having been performed on {@code on}, and reports it to the console. */
    public void record(MaintenanceType type, LocalDate on) {
        if (type == null) {
            throw new IllegalArgumentException("Maintenance type must not be null");
        }
        if (on == null) {
            throw new IllegalArgumentException("Maintenance date must not be null");
        }
        if (on.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Cannot record maintenance in the future: " + on);
        }

        append(carName + FIELD_SEPARATOR + type.name() + FIELD_SEPARATOR + on);
        System.out.println(ReportFormatter.action(carName, type.description(), on, timesPerformed(type)));
    }

    // ------------------------------------------------------------------
    // Querying
    // ------------------------------------------------------------------

    public LocalDate whenLastFillTank() {
        return whenLast(MaintenanceType.FILL_TANK);
    }

    public LocalDate whenLastRotateTires() {
        return whenLast(MaintenanceType.ROTATE_TIRES);
    }

    public LocalDate whenLastCleanWindshield() {
        return whenLast(MaintenanceType.CLEAN_WINDSHIELD);
    }

    public LocalDate whenLastChangeOil() {
        return whenLast(MaintenanceType.CHANGE_OIL);
    }

    public LocalDate whenLastReplaceWipers() {
        return whenLast(MaintenanceType.REPLACE_WIPERS);
    }

    /**
     * The most recent date {@code type} was performed on this car.
     *
     * @return the date, or {@code null} if this car has never had that maintenance done
     */
    public LocalDate whenLast(MaintenanceType type) {
        LocalDate last = datesFor(type).stream().max(Comparator.naturalOrder()).orElse(null);
        System.out.println(ReportFormatter.lastPerformed(carName, type.description(), last));
        return last;
    }

    public int timesFillTank() {
        return timesPerformed(MaintenanceType.FILL_TANK);
    }

    public int timesRotateTires() {
        return timesPerformed(MaintenanceType.ROTATE_TIRES);
    }

    public int timesCleanWindshield() {
        return timesPerformed(MaintenanceType.CLEAN_WINDSHIELD);
    }

    public int timesChangeOil() {
        return timesPerformed(MaintenanceType.CHANGE_OIL);
    }

    public int timesReplaceWipers() {
        return timesPerformed(MaintenanceType.REPLACE_WIPERS);
    }

    /** How many times {@code type} has been recorded for this car. */
    public int timesPerformed(MaintenanceType type) {
        return datesFor(type).size();
    }

    /** Every date {@code type} was recorded for this car, in the order it was written to the log. */
    public List<LocalDate> historyOf(MaintenanceType type) {
        return datesFor(type);
    }

    /**
     * Every car mentioned in the given log file, sorted alphabetically.
     *
     * @return an empty list if the log does not exist
     */
    public static List<String> maintainedCars(Path logFile) {
        TreeSet<String> cars = new TreeSet<>();
        for (String[] fields : readRecords(logFile)) {
            cars.add(fields[0]);
        }
        return new ArrayList<>(cars);
    }

    /** Every car mentioned in the default log file, sorted alphabetically. */
    public static List<String> maintainedCars() {
        return maintainedCars(defaultLogFile());
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private List<LocalDate> datesFor(MaintenanceType type) {
        List<LocalDate> dates = new ArrayList<>();
        for (String[] fields : readRecords(logFile)) {
            if (fields[0].equals(carName) && fields[1].equals(type.name())) {
                dates.add(LocalDate.parse(fields[2]));
            }
        }
        return dates;
    }

    private void append(String line) {
        try {
            Path parent = logFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    logFile,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write to maintenance log " + logFile, e);
        }
    }

    private static List<String[]> readRecords(Path logFile) {
        if (!Files.exists(logFile)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read maintenance log " + logFile, e);
        }

        List<String[]> records = new ArrayList<>();
        for (String line : lines) {
            if (StringUtils.isBlank(line)) {
                continue;
            }
            String[] fields = StringUtils.split(line.trim(), FIELD_SEPARATOR);
            if (fields.length != 3) {
                throw new IllegalStateException("Malformed maintenance log entry in " + logFile + ": " + line);
            }
            records.add(fields);
        }
        return records;
    }

    private static Path defaultLogFile() {
        return Paths.get(System.getProperty(LOG_FILE_PROPERTY, DEFAULT_LOG_FILE));
    }
}
