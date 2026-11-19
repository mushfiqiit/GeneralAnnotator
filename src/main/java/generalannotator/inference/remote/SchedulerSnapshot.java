package generalannotator.inference.remote;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the scheduler said about the array tasks of some jobs. squeue is the source for active tasks
 * and sacct for history. A task that neither reports is {@link Category#UNKNOWN}: missing scheduler
 * information is never read as success. Scheduler states describe tasks, not prompts; a COMPLETED
 * task may still have failed prompts.
 *
 * @param activeKnown whether squeue answered (an "invalid job id" answer counts: the job left the queue)
 * @param historyKnown whether sacct answered
 * @param rows the records, one per task or per task range
 * @param problems why squeue or sacct could not be used
 */
public record SchedulerSnapshot(boolean activeKnown, boolean historyKnown, List<Row> rows, List<String> problems) {

    public SchedulerSnapshot {
        rows = List.copyOf(rows);
        problems = List.copyOf(problems);
    }

    /** Where a row came from. */
    public enum Source { SQUEUE, SACCT }

    /** How a state is treated. */
    public enum Category {
        /** Pending, running or otherwise not finished; results may still change. */
        ACTIVE,
        /** Finished (successfully or not) according to the scheduler. */
        TERMINAL,
        /** The scheduler gave no information. */
        UNKNOWN
    }

    /** Array tasks {@code from..to} (inclusive) of {@code jobId} are in {@code state}. */
    public record Row(String jobId, int from, int to, String state, String detail, Source source) {}

    /** The state of one array task. */
    public record TaskState(String state, Category category, String detail) {}

    static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT", "OUT_OF_MEMORY",
            "NODE_FAIL", "PREEMPTED", "BOOT_FAIL", "DEADLINE", "REVOKED");
    static final Set<String> ACTIVE = Set.of("PENDING", "RUNNING", "CONFIGURING", "COMPLETING", "SUSPENDED",
            "REQUEUED", "REQUEUE_HOLD", "REQUEUE_FED", "RESIZING", "STOPPED", "SIGNALING", "STAGE_OUT",
            "SPECIAL_EXIT", "RESV_DEL_HOLD", "POWER_UP_NODE", "UPDATE_DB");

    public static SchedulerSnapshot unavailable(String problem) {
        return new SchedulerSnapshot(false, false, List.of(), List.of(problem));
    }

    /** Category of a scheduler state; unrecognized states count as active (not finished). */
    public static Category categorize(String state) {
        String s = state.split("\\s+")[0].replace("+", "");
        if (TERMINAL.contains(s)) {
            return Category.TERMINAL;
        }
        return Category.ACTIVE;
    }

    public TaskState state(String jobId, int index) {
        Row queued = find(jobId, index, Source.SQUEUE);
        Row history = find(jobId, index, Source.SACCT);
        if (queued != null && categorize(queued.state()) == Category.ACTIVE) {
            return describe(queued);
        }
        if (history != null) {
            return describe(history);
        }
        if (queued != null) {
            return describe(queued);
        }
        String why;
        if (!activeKnown && !historyKnown) {
            why = "squeue and sacct gave no answer";
        } else if (!historyKnown) {
            why = "not in squeue; sacct gave no answer";
        } else {
            why = "neither squeue nor sacct reports this task";
        }
        return new TaskState("UNKNOWN", Category.UNKNOWN, why);
    }

    private static TaskState describe(Row row) {
        String state = row.state().split("\\s+")[0];
        String detail = row.source().name().toLowerCase() + (row.detail().isEmpty() ? "" : ": " + row.detail());
        return new TaskState(state, categorize(state), detail);
    }

    private Row find(String jobId, int index, Source source) {
        Row found = null;
        for (Row r : rows) {
            if (r.source() == source && r.jobId().equals(jobId) && index >= r.from() && index <= r.to()) {
                found = r; // the last record wins (sacct lists requeued tasks again)
            }
        }
        return found;
    }

    private static final Pattern TASK = Pattern.compile("([0-9]+)_(?:([0-9]+)|\\[([0-9,\\-%]+)])");

    /**
     * Parses a Slurm job ID field such as {@code 123_4} or {@code 123_[5-9,12%4]} into rows; other
     * forms (steps, non-array jobs) give none.
     */
    static List<Row> rows(String field, String state, String detail, Source source) {
        List<Row> rows = new ArrayList<>();
        Matcher m = TASK.matcher(field.strip());
        if (!m.matches()) {
            return rows;
        }
        String job = m.group(1);
        if (m.group(2) != null) {
            int i = Integer.parseInt(m.group(2));
            rows.add(new Row(job, i, i, state, detail, source));
            return rows;
        }
        String ranges = m.group(3);
        int percent = ranges.indexOf('%');
        if (percent >= 0) {
            ranges = ranges.substring(0, percent);
        }
        for (String part : ranges.split(",")) {
            if (part.isEmpty()) {
                continue;
            }
            String[] bounds = part.split("-");
            try {
                int from = Integer.parseInt(bounds[0]);
                int to = bounds.length > 1 ? Integer.parseInt(bounds[1]) : from;
                rows.add(new Row(job, from, to, state, detail, source));
            } catch (NumberFormatException e) {
                // not a task range; ignore
            }
        }
        return rows;
    }
}
