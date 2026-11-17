package generalannotator;

/**
 * Command-line entry point.
 *
 * <p>The annotation pipeline is not implemented yet; this entry point only reports that.
 */
public final class Main {

    static final String STATUS_MESSAGE =
            "GeneralAnnotator: no pipeline stages are implemented yet. See docs/tasks.md.";

    private Main() {}

    public static void main(String[] args) {
        System.out.println(STATUS_MESSAGE);
    }
}
