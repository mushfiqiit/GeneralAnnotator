package generalannotator.diagnostics;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One file-located compiler diagnostic, as reported in a single compiler run.
 *
 * <p>This is an observation, not a cross-run warning identity: no equality between diagnostics of
 * different runs or repository states is implied (see project-spec §6.1).
 *
 * @param sourcePath the source path exactly as the compiler printed it
 * @param line 1-based line number
 * @param column 1-based column derived from javac's caret marker, if the caret was present
 * @param severity diagnostic severity
 * @param checkerName the bracketed tag that prefixes the message, if any: an Error Prone check name
 *     such as {@code NullAway}, or a javac {@code -Xlint} category such as {@code deprecation}
 * @param message the complete message without the bracketed tag, including continuation lines
 *     (joined with {@code \n}) but excluding the echoed source line and caret line
 * @param sourceSnippet the echoed source line, if javac printed one
 */
public record Diagnostic(
        String sourcePath,
        int line,
        OptionalInt column,
        Severity severity,
        Optional<String> checkerName,
        String message,
        Optional<String> sourceSnippet) {

    public Diagnostic {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(checkerName, "checkerName");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(sourceSnippet, "sourceSnippet");
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1: " + line);
        }
        if (column.isPresent() && column.getAsInt() < 1) {
            throw new IllegalArgumentException("column must be >= 1: " + column);
        }
    }

    /** Whether the bracketed tag equals {@code name}. */
    public boolean isFromChecker(String name) {
        return checkerName.map(name::equals).orElse(false);
    }

    /** {@code path:line[:column]} for human-readable output. */
    public String location() {
        return sourcePath + ":" + line + (column.isPresent() ? ":" + column.getAsInt() : "");
    }
}
