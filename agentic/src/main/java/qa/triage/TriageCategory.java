package qa.triage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Exactly these four wire values (see agentic/AGENT-DESIGN.md §2) — no fifth
 * "unknown" bucket. An unclassifiable failure still gets one of these four, with
 * {@code TriageResult.escalateToHuman} carrying the "don't trust this automatically"
 * signal instead of a made-up category.
 */
public enum TriageCategory {
    FLAKY_TEST("flaky_test"),
    PRODUCT_BUG("product_bug"),
    ENVIRONMENT_ISSUE("environment_issue"),
    TEST_GAP("test_gap");

    private final String wireValue;

    TriageCategory(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static TriageCategory fromWireValue(String value) {
        for (TriageCategory category : values()) {
            if (category.wireValue.equals(value)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown TriageCategory: " + value);
    }
}
