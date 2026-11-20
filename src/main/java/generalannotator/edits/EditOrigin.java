package generalannotator.edits;

import java.util.Optional;

/**
 * Where a proposed edit came from and what the proposer said about it; not part of edit identity.
 *
 * @param warningId the warning whose answer proposed it
 * @param editIndex its index in the answer's {@code edits} array
 * @param uncertainty the per-edit uncertainty the proposer gave
 */
public record EditOrigin(String warningId, int editIndex, Optional<String> uncertainty) {}
