package generalannotator.targets;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * One Java source file parsed (syntax only, no attribution) with the JDK's compiler tree API
 * ({@code com.sun.source}), together with its text and position table.
 *
 * @param unit the compilation unit
 * @param positions start/end character offsets of trees
 * @param text the file's text as the parser saw it
 */
public record ParsedSource(CompilationUnitTree unit, SourcePositions positions, String text) {

    /** Why a file could not be parsed. */
    public static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    /**
     * Parses {@code file} as UTF-8.
     *
     * @param release javac {@code --release} for the language level, if known
     */
    public static ParsedSource parse(Path file, OptionalInt release) throws ParseException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ParseException("no Java compiler is available in this runtime (a JDK is required)");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of("-proc:none"));
            if (release.isPresent()) {
                options.addAll(List.of("--release", Integer.toString(release.getAsInt())));
            }
            JavacTask task = (JavacTask) compiler.getTask(
                    null, files, diagnostics, options, null, files.getJavaFileObjects(file.toFile()));
            Iterator<? extends CompilationUnitTree> units = task.parse().iterator();
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> "line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT))
                    .toList();
            if (!errors.isEmpty()) {
                throw new ParseException("the source does not parse: " + String.join("; ", errors));
            }
            if (!units.hasNext()) {
                throw new ParseException("the parser returned no compilation unit");
            }
            CompilationUnitTree unit = units.next();
            String text = unit.getSourceFile().getCharContent(true).toString();
            return new ParsedSource(unit, Trees.instance(task).getSourcePositions(), text);
        } catch (IOException | IllegalArgumentException e) {
            throw new ParseException("the source could not be read or parsed: " + e.getMessage());
        }
    }

    /**
     * Character offset of a 1-based line and column (column counted in characters, as in javac's
     * caret line), or -1 if outside the file.
     */
    public long offset(int line, int column) {
        long lineStart = lineStart(line);
        if (lineStart < 0) {
            return -1;
        }
        long lineEnd = lineEnd(lineStart);
        long offset = lineStart + column - 1;
        return column >= 1 && offset <= lineEnd ? offset : -1;
    }

    /** Offset of the first character of a 1-based line, or -1. */
    public long lineStart(int line) {
        if (line < 1) {
            return -1;
        }
        int current = 1;
        int i = 0;
        while (current < line) {
            int newline = text.indexOf('\n', i);
            if (newline < 0) {
                return -1;
            }
            i = newline + 1;
            current++;
        }
        return i <= text.length() ? i : -1;
    }

    /** Offset of the line terminator (or end of text) of the line starting at {@code lineStart}. */
    public long lineEnd(long lineStart) {
        int newline = text.indexOf('\n', (int) lineStart);
        int end = newline < 0 ? text.length() : newline;
        if (end > lineStart && text.charAt(end - 1) == '\r') {
            end--;
        }
        return end;
    }
}
