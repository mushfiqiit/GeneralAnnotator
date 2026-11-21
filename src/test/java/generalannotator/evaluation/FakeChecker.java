package generalannotator.evaluation;

import generalannotator.checker.AnalysisRequest;
import generalannotator.checker.Checker;
import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerRun;
import generalannotator.checker.Coverage;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * A deterministic checker for evaluation tests: a rule turns the text of {@code src/p/A.java} in the
 * analyzed copy into an outcome. It records every request and never runs a process.
 */
public final class FakeChecker implements Checker {

    /** Turns the analyzed source into findings or a failure. */
    @FunctionalInterface
    public interface Rule {
        CheckerOutcome analyze(Source source);
    }

    /** The analyzed file. */
    public record Source(Path root, Path file, String text) {

        List<String> lines() {
            return text.lines().toList();
        }

        /** A NullAway-like finding at the first line containing {@code marker}, column of the marker. */
        public Diagnostic at(String marker, String message) {
            List<String> lines = lines();
            for (int i = 0; i < lines.size(); i++) {
                int col = lines.get(i).indexOf(marker);
                if (col >= 0) {
                    return new Diagnostic(file.toString(), i + 1, OptionalInt.of(col + 1), Severity.ERROR,
                            Optional.of("NullAway"), message + "\n(see http://t.uber.com/nullaway )",
                            Optional.of(lines.get(i)));
                }
            }
            throw new IllegalStateException("no line contains " + marker);
        }

        /** Findings at every line containing {@code marker}. */
        public List<Diagnostic> all(String marker, String message) {
            List<Diagnostic> found = new ArrayList<>();
            List<String> lines = lines();
            for (int i = 0; i < lines.size(); i++) {
                int col = lines.get(i).indexOf(marker);
                if (col >= 0) {
                    found.add(new Diagnostic(file.toString(), i + 1, OptionalInt.of(col + 1), Severity.ERROR,
                            Optional.of("NullAway"), message, Optional.of(lines.get(i))));
                }
            }
            return found;
        }

        /** Whether the first line containing {@code marker} carries an annotation. */
        public boolean annotated(String marker) {
            return lines().stream().filter(l -> l.contains(marker)).findFirst().orElse("").contains("@");
        }
    }

    private final Rule rule;
    public final List<AnalysisRequest> requests = new ArrayList<>();
    /** Run details by call number (1 = first call), for tool-identity tests. */
    public java.util.function.IntFunction<Map<String, Object>> details = n -> Map.of();

    public FakeChecker(Rule rule) {
        this.rule = rule;
    }

    /** Fields x and y are reported until annotated; annotating x makes {@code return x} a new finding. */
    public static CheckerOutcome nullness(Source s) {
        List<Diagnostic> findings = new ArrayList<>();
        if (!s.annotated("Object x;")) {
            findings.add(s.at("x;", "@NonNull field 'x' not initialized"));
        } else {
            findings.add(s.at("return x", "returning @Nullable expression from method with @NonNull return type"));
        }
        if (!s.annotated("Object y;")) {
            findings.add(s.at("y;", "@NonNull field 'y' not initialized"));
        }
        return new CheckerOutcome.CompletedAnalysis(findings, List.of());
    }

    public int calls() {
        return requests.size();
    }

    @Override
    public String id() {
        return "nullaway";
    }

    @Override
    public CheckerRun analyze(AnalysisRequest request) {
        requests.add(request);
        Path file = request.workingCopyRoot().resolve("src/p/A.java");
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CheckerOutcome outcome = rule.analyze(new Source(request.workingCopyRoot(), file, text));
        return new CheckerRun("nullaway", List.of(), outcome,
                new Coverage(request.sourceRoots().stream().map(Path::toString).toList(), 1, new TreeMap<>(),
                        List.of()), details.apply(requests.size()));
    }
}
