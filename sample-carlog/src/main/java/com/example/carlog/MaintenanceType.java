package com.example.carlog;

/** The kinds of maintenance a {@link CarLog} can record. */
public enum MaintenanceType {

    FILL_TANK("fill the gas tank"),
    ROTATE_TIRES("rotate the tires"),
    CLEAN_WINDSHIELD("clean the windshield"),
    CHANGE_OIL("change the oil"),
    REPLACE_WIPERS("replace the wiper blades");

    private final String description;

    MaintenanceType(String description) {
        this.description = description;
    }

    /** Human readable description, lower case and in the infinitive, e.g. "rotate the tires". */
    public String description() {
        return description;
    }
}
