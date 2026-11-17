package generalannotator.config;

import java.util.List;

/** Invalid or missing configuration. Carries every problem found, not just the first. */
public final class ConfigurationException extends Exception {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // List.copyOf returns a serializable list
    private final List<String> problems;

    public ConfigurationException(List<String> problems) {
        super(String.join("\n", problems));
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("problems must not be empty");
        }
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
