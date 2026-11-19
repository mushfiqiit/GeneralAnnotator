package generalannotator.inference.remote;

/** A remote operation that did not complete; {@link #kind()} says whether the connection was the cause. */
public final class RemoteException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Why the operation did not complete. */
    public enum Kind {
        /** The shared SSH connection is gone or unusable; reconnect interactively, then rerun. */
        DISCONNECTED,
        /** The command did not finish in time; its effect on the cluster is unknown. */
        TIMED_OUT,
        /** The command ran and failed, or its output could not be understood. */
        FAILED
    }

    private final Kind kind;

    public RemoteException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
