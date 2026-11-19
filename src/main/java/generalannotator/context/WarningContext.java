package generalannotator.context;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Extracted usage context for one warning.
 *
 * @param warningId the warning node ID (preserved from the graph and warning-targets.json)
 * @param status extraction outcome
 * @param reason why the context is SKIPPED or FAILED
 * @param warning the warning's location, checker and message
 * @param target the target identity (member kind, signature, declaration, annotations); empty if
 *     the target is unresolved
 * @param references counts of matched, unresolved and excluded same-name references
 * @param categories evidence, in a fixed category order
 * @param limitations what the extraction does not cover or could not establish
 * @param compact the compact, member-grouped view of the kept evidence
 */
public record WarningContext(
        String warningId,
        ContextStatus status,
        Optional<String> reason,
        Map<String, Object> warning,
        Map<String, Object> target,
        Map<String, Object> references,
        List<EvidenceCategory> categories,
        List<String> limitations,
        CompactView compact) {

    public WarningContext {
        Objects.requireNonNull(warningId, "warningId");
        Objects.requireNonNull(status, "status");
        warning = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(warning));
        target = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(target));
        references = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(references));
        categories = List.copyOf(categories);
        limitations = List.copyOf(limitations);
        Objects.requireNonNull(compact, "compact");
    }

    public Optional<EvidenceCategory> category(String name) {
        return categories.stream().filter(c -> c.name().equals(name)).findFirst();
    }
}
