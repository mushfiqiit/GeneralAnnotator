package generalannotator.inference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic assignment of warning IDs to Slurm array tasks: IDs in numeric order, cut into
 * consecutive groups of {@code promptsPerTask} (the last group may be smaller). With 41 prompts and
 * 10 per task the groups are 10, 10, 10, 10, 1. Each prompt is still a separate LLM request.
 */
public final class TaskAssignment {

    /** {@code W0001 < W0002 < ... < W9999 < W10000}. */
    static final Comparator<String> WARNING_ORDER = Comparator.<String>comparingInt(String::length)
            .thenComparing(Comparator.naturalOrder());

    private TaskAssignment() {}

    public static List<List<String>> assign(List<String> warningIds, int promptsPerTask) {
        if (promptsPerTask < InferenceSettings.MIN_PROMPTS_PER_TASK
                || promptsPerTask > InferenceSettings.MAX_PROMPTS_PER_TASK) {
            throw new IllegalArgumentException("prompts per task must be 1 to 10, not " + promptsPerTask);
        }
        List<String> sorted = new ArrayList<>(warningIds);
        sorted.sort(WARNING_ORDER);
        List<List<String>> tasks = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i += promptsPerTask) {
            tasks.add(List.copyOf(sorted.subList(i, Math.min(sorted.size(), i + promptsPerTask))));
        }
        return List.copyOf(tasks);
    }
}
