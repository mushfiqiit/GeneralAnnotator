package generalannotator.checker.nullaway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The Error Prone processor path resolved from a local NullAway checkout by {@code
 * processor-path.init.gradle}.
 *
 * @param nullawayVersion the checkout's project version
 * @param nullawayJar the jar built from the checkout
 * @param errorProne Error Prone jars (the checkout's {@code errorproneExtended} configuration)
 * @param runtime NullAway's runtime dependencies
 */
public record ProcessorPath(
        String nullawayVersion, Path nullawayJar, List<Path> errorProne, List<Path> runtime) {

    public ProcessorPath {
        errorProne = List.copyOf(errorProne);
        runtime = List.copyOf(runtime);
    }

    /** Processor path entries in the order NullAway's own {@code buildWithNullAway} uses. */
    public List<Path> entries() {
        LinkedHashSet<Path> ordered = new LinkedHashSet<>(errorProne);
        ordered.add(nullawayJar);
        ordered.addAll(runtime);
        return List.copyOf(ordered);
    }

    /** The {@code error_prone_core} jar's file name, if present. */
    public Optional<String> errorProneCoreJar() {
        return errorProne.stream()
                .map(p -> p.getFileName().toString())
                .filter(n -> n.startsWith("error_prone_core"))
                .findFirst();
    }

    /**
     * Parses the init script's output and checks that it really selects the local checkout.
     *
     * @throws IllegalArgumentException describing every problem
     */
    public static ProcessorPath parse(String text, Path checkout) throws IOException {
        String version = null;
        Path jar = null;
        List<Path> errorProne = new ArrayList<>();
        List<Path> runtime = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                problems.add("unexpected line: " + line);
                continue;
            }
            String kind = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (kind) {
                case "nullaway-version" -> version = value;
                case "nullaway-jar" -> jar = Path.of(value);
                case "errorprone" -> errorProne.add(Path.of(value));
                case "runtime" -> runtime.add(Path.of(value));
                default -> problems.add("unexpected entry kind: " + kind);
            }
        }
        if (version == null) {
            problems.add("NullAway version missing");
        }
        if (jar == null) {
            problems.add("NullAway jar missing");
        } else if (!Files.isRegularFile(jar)) {
            problems.add("NullAway jar does not exist: " + jar);
        } else if (!jar.toRealPath().startsWith(checkout.toRealPath())) {
            problems.add("NullAway jar " + jar + " is not inside the configured checkout " + checkout);
        }
        if (errorProne.isEmpty()) {
            problems.add("no Error Prone jars resolved");
        }
        for (Path p : errorProne) {
            if (!Files.exists(p)) {
                problems.add("Error Prone jar does not exist: " + p);
            }
        }
        for (Path p : runtime) {
            if (!Files.exists(p)) {
                problems.add("NullAway runtime dependency does not exist: " + p);
            }
        }
        ProcessorPath result = new ProcessorPath(version == null ? "" : version, jar == null ? Path.of("") : jar,
                errorProne, runtime);
        if (!errorProne.isEmpty() && result.errorProneCoreJar().isEmpty()) {
            problems.add("error_prone_core is not among the resolved Error Prone jars");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return result;
    }
}
