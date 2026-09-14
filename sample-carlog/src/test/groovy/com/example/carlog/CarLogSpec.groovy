package com.example.carlog

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

/**
 * Verifies both halves of the library's contract: what it prints to the console, and what
 * it durably records in the maintenance log.
 *
 * Each feature gets its own temp log file, so tests never see each other's history and
 * never touch the real car-maintenance-log.txt at the project root.
 */
class CarLogSpec extends Specification {

    static final LocalDate TODAY = LocalDate.now()
    static final LocalDate LAST_MONTH = TODAY.minusMonths(1)
    static final LocalDate LAST_YEAR = TODAY.minusYears(1)

    @TempDir
    Path tempDir

    Path logFile

    private ByteArrayOutputStream captured
    private PrintStream realOut

    def setup() {
        logFile = tempDir.resolve('car-maintenance-log.txt')
        captured = new ByteArrayOutputStream()
        realOut = System.out
        System.setOut(new PrintStream(captured, true, 'UTF-8'))
    }

    def cleanup() {
        System.setOut(realOut)
    }

    /** Everything printed since the last call to this method. */
    private String output() {
        def text = captured.toString('UTF-8')
        captured.reset()
        return text
    }

    private List<String> logLines() {
        return Files.exists(logFile) ? Files.readAllLines(logFile).findAll { !it.isBlank() } : []
    }

    // ------------------------------------------------------------------
    // The scenario from the class Javadoc
    // ------------------------------------------------------------------

    def "records maintenance and reports when it was last done"() {
        given:
        def newLog = new CarLog('Honda', logFile)

        when:
        newLog.recordFillTank(TODAY)
        newLog.recordRotateTires(LAST_YEAR)

        then:
        newLog.whenLastFillTank() == TODAY
        newLog.whenLastRotateTires() == LAST_YEAR
    }

    def "a car that has never had a job done reports null and a count of zero"() {
        given:
        def log = new CarLog('Honda', logFile)

        expect:
        log.whenLastChangeOil() == null
        log.timesChangeOil() == 0
        log.historyOf(MaintenanceType.CHANGE_OIL).isEmpty()
    }

    // ------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------

    def "counts how often each job has been done"() {
        given:
        def log = new CarLog('Honda', logFile)

        when:
        log.recordFillTank(LAST_YEAR)
        log.recordFillTank(LAST_MONTH)
        log.recordFillTank(TODAY)
        log.recordCleanWindshield(TODAY)

        then:
        log.timesFillTank() == 3
        log.timesCleanWindshield() == 1
        log.timesRotateTires() == 0
    }

    @Unroll
    def "#type is counted and dated independently of the other jobs"() {
        given:
        def log = new CarLog('Honda', logFile)

        when:
        log.record(type, LAST_YEAR)
        log.record(type, TODAY)

        then:
        log.timesPerformed(type) == 2
        log.whenLast(type) == TODAY

        and: 'no other job was touched'
        MaintenanceType.values().findAll { it != type }.every { log.timesPerformed(it) == 0 }

        where:
        type << MaintenanceType.values()
    }

    // ------------------------------------------------------------------
    // Backdating
    // ------------------------------------------------------------------

    def "the most recent date wins even when history is entered out of order"() {
        given:
        def log = new CarLog('Honda', logFile)

        when: 'the newest job is recorded first, then older history is backfilled'
        log.recordChangeOil(LAST_MONTH)
        log.recordChangeOil(LAST_YEAR)

        then:
        log.whenLastChangeOil() == LAST_MONTH
        log.timesChangeOil() == 2
    }

    def "history is returned in the order it was written"() {
        given:
        def log = new CarLog('Honda', logFile)

        when:
        log.recordFillTank(LAST_MONTH)
        log.recordFillTank(LAST_YEAR)

        then:
        log.historyOf(MaintenanceType.FILL_TANK) == [LAST_MONTH, LAST_YEAR]
    }

    def "recording without a date uses today"() {
        given:
        def log = new CarLog('Honda', logFile)

        when:
        log.recordReplaceWipers()

        then:
        log.whenLastReplaceWipers() == LocalDate.now()
    }

    def "maintenance cannot be recorded in the future"() {
        given:
        def log = new CarLog('Honda', logFile)

        when:
        log.recordFillTank(TODAY.plusDays(1))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith('Cannot record maintenance in the future')

        and: 'nothing was written'
        logLines().isEmpty()
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    def "one line is appended to the log per recorded job"() {
        given:
        def log = new CarLog('Honda', logFile)

        when:
        log.recordFillTank(TODAY)
        log.recordRotateTires(LAST_YEAR)

        then:
        logLines() == [
                "Honda|FILL_TANK|${TODAY}".toString(),
                "Honda|ROTATE_TIRES|${LAST_YEAR}".toString()
        ]
    }

    def "history survives a new CarLog instance"() {
        given:
        new CarLog('Honda', logFile).recordChangeOil(LAST_YEAR)

        when: 'a completely separate instance is created over the same log'
        def reopened = new CarLog('Honda', logFile)

        then:
        reopened.whenLastChangeOil() == LAST_YEAR
        reopened.timesChangeOil() == 1
    }

    def "cars do not see each other's maintenance"() {
        given:
        def honda = new CarLog('Honda', logFile)
        def subaru = new CarLog('Subaru', logFile)

        when:
        honda.recordFillTank(TODAY)
        subaru.recordFillTank(LAST_YEAR)
        subaru.recordFillTank(LAST_MONTH)

        then:
        honda.timesFillTank() == 1
        honda.whenLastFillTank() == TODAY

        and:
        subaru.timesFillTank() == 2
        subaru.whenLastFillTank() == LAST_MONTH
    }

    def "every car in the log is discoverable"() {
        given:
        new CarLog('Subaru', logFile).recordFillTank(TODAY)
        new CarLog('Honda', logFile).recordRotateTires(TODAY)
        new CarLog('Honda', logFile).recordCleanWindshield(TODAY)

        expect: 'sorted, and each car listed once'
        CarLog.maintainedCars(logFile) == ['Honda', 'Subaru']
    }

    def "an absent log means no cars are tracked"() {
        expect:
        CarLog.maintainedCars(tempDir.resolve('nope.txt')) == []
    }

    // ------------------------------------------------------------------
    // Console output
    // ------------------------------------------------------------------

    def "creating a log prints a banner for the car"() {
        when:
        new CarLog('Honda', logFile)

        then:
        def banner = output().trim()
        banner.length() == 60
        banner.contains(' HONDA ')
        banner.startsWith('==')
        banner.endsWith('==')
    }

    def "recording a job prints an aligned, dotted report line"() {
        given:
        def log = new CarLog('Honda', logFile)
        output()

        when:
        log.recordFillTank(LAST_YEAR)

        then: 'exact format, padded to 60 columns'
        output().trim() == "[Honda] Fill the gas tank ${'.' * 13} ${LAST_YEAR} (1 total)"
    }

    def "the printed running total goes up with each repeat"() {
        given:
        def log = new CarLog('Honda', logFile)
        output()

        when:
        log.recordCleanWindshield(LAST_YEAR)
        log.recordCleanWindshield(LAST_MONTH)
        log.recordCleanWindshield(TODAY)

        then:
        def lines = output().readLines()
        lines.size() == 3
        lines[0].endsWith('(1 total)')
        lines[1].endsWith('(2 total)')
        lines[2].endsWith('(3 total)')
        lines.every { it.startsWith('[Honda] Clean the windshield') && it.length() == 60 }
    }

    @Unroll
    def "recording #type prints its description '#type.description'"() {
        given:
        def log = new CarLog('Honda', logFile)
        output()

        when:
        log.record(type, LAST_YEAR)

        then:
        def line = output().trim()
        line.startsWith("[Honda] ${type.description.capitalize()}")
        line.contains(LAST_YEAR.toString())

        where:
        type << MaintenanceType.values()
    }

    def "querying prints the last date, or 'never'"() {
        given:
        def log = new CarLog('Honda', logFile)
        log.recordRotateTires(LAST_MONTH)
        output()

        when:
        log.whenLastRotateTires()

        then:
        output().trim() == "[Honda] Rotate the tires: ${LAST_MONTH}"

        when:
        log.whenLastChangeOil()

        then:
        output().trim() == '[Honda] Change the oil: never'
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Unroll
    def "a car name of #name is rejected"() {
        when:
        new CarLog(name, logFile)

        then:
        thrown(IllegalArgumentException)

        where:
        name << [null, '', '   ', 'Hon|da']
    }

    def "a car name is trimmed"() {
        expect:
        new CarLog('  Honda  ', logFile).carName() == 'Honda'
    }

    def "a corrupt log line is reported rather than silently skipped"() {
        given:
        Files.writeString(logFile, 'this is not a record\n')
        def log = new CarLog('Honda', logFile)

        when:
        log.timesFillTank()

        then:
        def e = thrown(IllegalStateException)
        e.message.startsWith('Malformed maintenance log entry')
    }
}
