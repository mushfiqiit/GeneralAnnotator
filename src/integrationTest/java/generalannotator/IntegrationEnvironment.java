package generalannotator;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locations needed by real-tool integration tests. */
final class IntegrationEnvironment {

    private IntegrationEnvironment() {}

    /** The local NullAway checkout from GA_CHECKER_HOME; fails (not skips) when it is missing. */
    static Path nullAwayCheckout() {
        String home = System.getenv("GA_CHECKER_HOME");
        if (home == null || home.isBlank() || !Files.isDirectory(Path.of(home))) {
            fail("Integration tests need GA_CHECKER_HOME to name a local NullAway checkout; got: " + home);
        }
        return Path.of(home);
    }

    /** JDK for javac + Error Prone: GA_CHECKER_JAVA_HOME, else the JDK running the tests. */
    static Path checkerJavaHome() {
        String home = System.getenv("GA_CHECKER_JAVA_HOME");
        return Path.of(home != null && !home.isBlank() ? home : System.getProperty("java.home"));
    }

    static Path fixture(String name) {
        return Path.of(System.getProperty("ga.fixtures")).resolve(name);
    }
}
