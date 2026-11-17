package generalannotator.diagnostics;

import generalannotator.diagnostics.CompilerOutput.GeneralMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses javac's default (non-XML) diagnostic output, including diagnostics produced by Error
 * Prone plugins such as NullAway.
 *
 * <p>A file-located diagnostic has the form:
 *
 * <pre>
 * /path/Foo.java:12: error: [NullAway] dereferenced expression x is @Nullable
 *         return x.length();
 *                 ^
 *     (see http://t.uber.com/nullaway )
 * </pre>
 *
 * <p>Lines after the header up to the caret line belong to the diagnostic; the line directly before
 * the caret is the echoed source line and everything else is message text. After the caret, only
 * indented lines continue the message. Anything this parser does not recognize is kept in {@link
 * CompilerOutput#unrecognizedLines()} rather than dropped, and inconsistencies (missing caret,
 * missing or mismatched count lines, javac's own truncation notice) are reported as problems.
 */
public final class JavacOutputParser {

    private static final Pattern HEADER =
            Pattern.compile("^(.+\\.java):(\\d+): (error|warning|note): (.*)$");
    private static final Pattern GENERAL = Pattern.compile("^(error|warning|note): (.*)$");
    private static final Pattern NOTE = Pattern.compile("^Note: (.*)$");
    private static final Pattern JVM_INFO = Pattern.compile("^Picked up \\S*JAVA\\S*OPTIONS: .*$");
    private static final Pattern COUNT = Pattern.compile("^(\\d+) (error|errors|warning|warnings)$");
    private static final Pattern JAVAC_TRUNCATION =
            Pattern.compile("^only showing the first \\d+ (errors|warnings), of \\d+ total; .*$");
    private static final Pattern TAG = Pattern.compile("^\\[([A-Za-z0-9_.:-]+)\\] (.*)$", Pattern.DOTALL);
    private static final Pattern CARET = Pattern.compile("^\\s*\\^\\s*$");

    private JavacOutputParser() {}

    /** Parses the stderr and stdout of one javac run and checks the combined counts. */
    public static CompilerOutput parse(String stderr, String stdout) {
        return withCountChecks(parseStream(stderr).merge(parseStream(stdout)));
    }

    /** Parses a single stream of javac output and checks its counts. */
    public static CompilerOutput parse(String output) {
        return withCountChecks(parseStream(output));
    }

    private static CompilerOutput parseStream(String output) {
        State state = new State();
        for (String line : output.split("\\R", -1)) {
            state.accept(line);
        }
        state.finishBlock();
        return new CompilerOutput(
                state.diagnostics,
                state.generalMessages,
                state.errorCount,
                state.warningCount,
                state.unrecognized,
                state.problems);
    }

    private static CompilerOutput withCountChecks(CompilerOutput output) {
        List<String> problems = new ArrayList<>(output.problems());
        long errors = countSeverity(output, Severity.ERROR);
        long warnings = countSeverity(output, Severity.WARNING);
        checkCount("error", errors, output.reportedErrorCount(), problems);
        checkCount("warning", warnings, output.reportedWarningCount(), problems);
        return new CompilerOutput(
                output.diagnostics(),
                output.generalMessages(),
                output.reportedErrorCount(),
                output.reportedWarningCount(),
                output.unrecognizedLines(),
                problems);
    }

    private static long countSeverity(CompilerOutput output, Severity severity) {
        return output.diagnostics().stream().filter(d -> d.severity() == severity).count()
                + output.generalMessages().stream().filter(m -> m.severity() == severity).count();
    }

    private static void checkCount(String kind, long parsed, OptionalInt reported, List<String> problems) {
        if (reported.isPresent()) {
            if (reported.getAsInt() != parsed) {
                problems.add("javac reported " + reported.getAsInt() + " " + kind + "(s) but "
                        + parsed + " were parsed");
            }
        } else if (parsed > 0) {
            problems.add(parsed + " " + kind + "(s) were parsed but javac's '" + kind
                    + "' count line is missing (output may be truncated)");
        }
    }

    /** Mutable parse state for one stream. */
    private static final class State {
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final List<GeneralMessage> generalMessages = new ArrayList<>();
        final List<String> unrecognized = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        OptionalInt errorCount = OptionalInt.empty();
        OptionalInt warningCount = OptionalInt.empty();

        // Current file-located diagnostic block.
        Matcher header;
        final List<String> beforeCaret = new ArrayList<>();
        final List<String> afterCaret = new ArrayList<>();
        String caretLine;

        void accept(String line) {
            Matcher m = HEADER.matcher(line);
            if (m.matches()) {
                finishBlock();
                header = m;
                return;
            }
            if (acceptStandalone(line)) {
                return;
            }
            if (header != null) {
                if (caretLine == null) {
                    if (CARET.matcher(line).matches()) {
                        caretLine = line;
                    } else {
                        beforeCaret.add(line);
                    }
                    return;
                }
                if (!line.isEmpty() && Character.isWhitespace(line.charAt(0))) {
                    afterCaret.add(line);
                    return;
                }
                finishBlock();
            }
            if (!line.isBlank()) {
                unrecognized.add(line);
            }
        }

        /** Handles lines that always stand on their own; returns whether the line was consumed. */
        private boolean acceptStandalone(String line) {
            Matcher m = COUNT.matcher(line);
            if (m.matches()) {
                finishBlock();
                int value = Integer.parseInt(m.group(1));
                if (m.group(2).startsWith("error")) {
                    if (errorCount.isPresent()) {
                        problems.add("javac error count line appears more than once");
                    }
                    errorCount = OptionalInt.of(value);
                } else {
                    if (warningCount.isPresent()) {
                        problems.add("javac warning count line appears more than once");
                    }
                    warningCount = OptionalInt.of(value);
                }
                return true;
            }
            if (JAVAC_TRUNCATION.matcher(line).matches()) {
                finishBlock();
                problems.add("javac truncated its diagnostics: " + line);
                return true;
            }
            m = GENERAL.matcher(line);
            if (m.matches()) {
                finishBlock();
                generalMessages.add(new GeneralMessage(Severity.fromJavacKeyword(m.group(1)), m.group(2)));
                return true;
            }
            m = NOTE.matcher(line);
            if (m.matches() || JVM_INFO.matcher(line).matches()) {
                finishBlock();
                generalMessages.add(new GeneralMessage(Severity.NOTE, m.matches() ? m.group(1) : line));
                return true;
            }
            return false;
        }

        void finishBlock() {
            if (header == null) {
                return;
            }
            String path = header.group(1);
            int line = Integer.parseInt(header.group(2));
            Severity severity = Severity.fromJavacKeyword(header.group(3));

            List<String> messageLines = new ArrayList<>();
            Optional<String> snippet = Optional.empty();
            OptionalInt column = OptionalInt.empty();
            if (caretLine != null && !beforeCaret.isEmpty()) {
                messageLines.addAll(beforeCaret.subList(0, beforeCaret.size() - 1));
                snippet = Optional.of(beforeCaret.get(beforeCaret.size() - 1));
                column = OptionalInt.of(caretLine.indexOf('^') + 1);
            } else {
                messageLines.addAll(beforeCaret);
                if (caretLine != null) {
                    problems.add("diagnostic at " + path + ":" + line
                            + " has a caret line but no source line");
                } else {
                    problems.add("diagnostic at " + path + ":" + line
                            + " has no source/caret lines (output may be truncated)");
                }
            }
            for (String continuation : afterCaret) {
                messageLines.add(continuation.strip());
            }

            String first = header.group(4);
            Optional<String> checker = Optional.empty();
            Matcher tag = TAG.matcher(first);
            if (tag.matches()) {
                checker = Optional.of(tag.group(1));
                first = tag.group(2);
            }
            StringBuilder message = new StringBuilder(first);
            for (String extra : messageLines) {
                message.append('\n').append(extra);
            }
            diagnostics.add(new Diagnostic(
                    path, line, column, severity, checker, message.toString(), snippet));

            header = null;
            caretLine = null;
            beforeCaret.clear();
            afterCaret.clear();
        }
    }
}
