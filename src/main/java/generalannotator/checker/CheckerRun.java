package generalannotator.checker;

import generalannotator.exec.RecordedCommand;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a checker run produced.
 *
 * @param checkerId the checker identifier
 * @param commands external commands run by the checker, in order
 * @param outcome completed analysis or execution failure
 * @param coverage what was analyzed and known limitations
 * @param details checker-specific, JSON-compatible details for the report (strings, numbers,
 *     booleans, lists and maps of those)
 */
public record CheckerRun(
        String checkerId,
        List<RecordedCommand> commands,
        CheckerOutcome outcome,
        Coverage coverage,
        Map<String, Object> details) {

    public CheckerRun {
        Objects.requireNonNull(checkerId, "checkerId");
        commands = List.copyOf(commands);
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(coverage, "coverage");
        details = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
