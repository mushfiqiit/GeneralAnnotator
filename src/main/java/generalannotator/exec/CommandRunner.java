package generalannotator.exec;

/**
 * Runs external commands. Implementations never throw for command failures; start failures,
 * timeouts and non-zero exits are all reported in the {@link CommandResult}.
 */
public interface CommandRunner {

    CommandResult run(CommandSpec spec);
}
