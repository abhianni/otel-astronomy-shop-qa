package qa;

import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * JUnit 5 has no built-in xfail. This mirrors Playwright's test.fail(): the wrapped
 * assertion documents the DESIRED (currently broken) behavior. If it fails as expected,
 * the bug is still present and the test passes vacuously. If it unexpectedly passes,
 * the bug was fixed and this forces a visible failure so the xfail wrapper gets removed.
 *
 * <p>Only AssertionError is swallowed — any other exception (e.g. a connection error
 * from the stack not being up) is rethrown, so environment problems can't be mistaken
 * for the documented bug.
 */
final class Xfail {

    private Xfail() {
    }

    static void expectFailure(String reason, Executable assertion) {
        try {
            assertion.execute();
        } catch (AssertionError expected) {
            return;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        fail("Known bug (" + reason + ") no longer reproduces — promote this assertion out of Xfail.");
    }
}
