package generalannotator.context;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One piece of usage evidence.
 *
 * @param kind what the site is, for example {@code METHOD_CALL}, {@code PARAMETER_USE}, {@code READ}
 * @param location source location, or null for declarations that exist only in class files
 * @param sortOffset character offset used only for deterministic ordering (-1 without location)
 * @param enclosingMember label of the member containing the site
 * @param expression the relevant expression as written (whitespace collapsed)
 * @param snippet the relevant statement, or null
 * @param conditions enclosing conditions, nearest first
 * @param omittedConditions conditions beyond the limit
 * @param localDefinitions definitions of local variables used by the relevant expression
 * @param details JSON-compatible kind-specific details (ordered)
 * @param observations syntactic observations
 */
public record Evidence(
        String kind,
        SourceRef location,
        long sortOffset,
        String enclosingMember,
        String expression,
        Snippet snippet,
        List<Condition> conditions,
        int omittedConditions,
        List<LocalDefinition> localDefinitions,
        Map<String, Object> details,
        Set<Observation> observations) {

    public Evidence {
        Objects.requireNonNull(kind, "kind");
        conditions = List.copyOf(conditions);
        localDefinitions = List.copyOf(localDefinitions);
        details = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
        observations = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(observations));
    }

    /** {@code file:line:column} of a source position, with the file relative to the run snapshot. */
    public record SourceRef(String file, int line, int column) {
        @Override
        public String toString() {
            return file + ":" + line + ":" + column;
        }
    }

    /** Source lines {@code startLine..endLine} (dedented); {@code omittedLines} lines were cut. */
    public record Snippet(int startLine, int endLine, List<String> lines, int omittedLines) {
        public Snippet {
            lines = List.copyOf(lines);
        }
    }

    /**
     * An enclosing condition: {@code kind} says where the site sits, for example {@code IF_THEN},
     * {@code IF_ELSE}, {@code LOOP_BODY}, {@code TERNARY_TRUE}, {@code AND_RIGHT}, {@code CASE}.
     */
    public record Condition(String kind, String text, int line) {}

    /** A declaration or earlier assignment of a local variable used by the relevant expression. */
    public record LocalDefinition(String variable, String kind, int line, String text) {}
}
