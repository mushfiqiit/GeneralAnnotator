package generalannotator.checker;

import generalannotator.checker.nullaway.NullAwayChecker;
import generalannotator.config.AnalysisConfig;
import generalannotator.config.ConfigurationException;
import generalannotator.exec.CommandRunner;
import java.util.List;
import java.util.Map;

/** Selects the {@link Checker} implementation for a configured checker identifier. */
public final class Checkers {

    private Checkers() {}

    /**
     * @throws ConfigurationException for unsupported identifiers or invalid checker-specific settings
     */
    public static Checker create(AnalysisConfig config, Map<String, String> env, CommandRunner runner)
            throws ConfigurationException {
        return switch (config.checkerId()) {
            case NullAwayChecker.ID -> NullAwayChecker.create(config.checkerHome(), env, runner);
            default -> throw new ConfigurationException(List.of(
                    "Unsupported checker '" + config.checkerId() + "'. Supported: " + NullAwayChecker.ID + "."));
        };
    }
}
