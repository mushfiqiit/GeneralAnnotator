package generalannotator.context;

import java.util.List;

/**
 * Evidence of one category after deduplication, ordering and truncation.
 *
 * @param name category key, for example {@code callSites}
 * @param description what the category contains
 * @param total items found before truncation
 * @param items kept items (at most the configured limit)
 */
public record EvidenceCategory(String name, String description, int total, List<Evidence> items) {
    public EvidenceCategory {
        items = List.copyOf(items);
    }

    public int omitted() {
        return total - items.size();
    }
}
