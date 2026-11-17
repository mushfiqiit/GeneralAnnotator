package generalannotator.isolation;

import generalannotator.exec.RecordedCommand;
import java.util.List;

/** The isolated working copy could not be created. */
public final class IsolationException extends Exception {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // List.copyOf returns a serializable list; records are not serialized
    private final List<RecordedCommand> commands;

    public IsolationException(String message, List<RecordedCommand> commands, Throwable cause) {
        super(message, cause);
        this.commands = List.copyOf(commands);
    }

    /** Commands that ran before the failure. */
    public List<RecordedCommand> commands() {
        return commands;
    }
}
