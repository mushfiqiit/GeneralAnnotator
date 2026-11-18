package generalannotator.graph;

import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.report.RunReport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Builds the initial warning graph from the first successful checker run: one root node (distance
 * 0) per finding, and no edges.
 *
 * <p>Only a {@link CompletedAnalysis} is accepted, so an execution failure can never be turned into
 * a graph that looks like "no warnings".
 *
 * <p>IDs are {@code W0001}, {@code W0002}, ... assigned after sorting findings by target-relative
 * path (or reported path), line, column (missing first), severity, checker name, message and source
 * snippet. The order therefore does not depend on the order javac printed the diagnostics in.
 * Identical duplicate findings keep their relative output order and get distinct IDs.
 */
public final class InitialWarningGraph {

    private InitialWarningGraph() {}

    /** Format of warning node IDs; numbers beyond 9999 simply get more digits. */
    public static String warningId(int ordinal) {
        return String.format("W%04d", ordinal);
    }

    /**
     * @param runId the run identifier
     * @param checkerId the checker identifier
     * @param analysis the completed analysis
     * @param workingCopyRoot root of the isolated copy, for mapping paths to the target (may be null)
     */
    public static WarningGraph build(String runId, String checkerId, CompletedAnalysis analysis, Path workingCopyRoot) {
        record Located(Diagnostic diagnostic, Optional<String> targetPath) {
            String sortPath() {
                return targetPath.orElse(diagnostic.sourcePath());
            }
        }
        List<Located> located = new ArrayList<>();
        for (Diagnostic d : analysis.findings()) {
            located.add(new Located(d, RunReport.targetRelativePath(d.sourcePath(), workingCopyRoot)));
        }
        located.sort(Comparator.comparing(Located::sortPath)
                .thenComparingInt(l -> l.diagnostic().line())
                .thenComparingInt(l -> l.diagnostic().column().orElse(0))
                .thenComparing(l -> l.diagnostic().severity())
                .thenComparing(l -> l.diagnostic().checkerName().orElse(""))
                .thenComparing(l -> l.diagnostic().message())
                .thenComparing(l -> l.diagnostic().sourceSnippet().orElse("")));

        List<GraphNode> nodes = new ArrayList<>();
        for (int i = 0; i < located.size(); i++) {
            Located l = located.get(i);
            nodes.add(new WarningNode(warningId(i + 1), l.diagnostic(), l.targetPath(), true, 0));
        }
        return new WarningGraph(runId, checkerId, nodes, List.of());
    }
}
