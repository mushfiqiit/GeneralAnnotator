package generalannotator.context;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The run snapshot's sources parsed <em>and attributed</em> by javac in one compilation task, so
 * that every reference carries the compiler's symbol. Code is analyzed, never generated: no class
 * files are written and annotation processors are disabled.
 *
 * <p>Attribution continues after errors (for example missing dependencies). Those errors are kept
 * so that references near them can be reported as unresolved instead of being trusted.
 */
public final class SourceModel implements AutoCloseable {

    /** A compiler error: file relative to the snapshot ("" if none), line, and offsets. */
    public record CompilerError(String file, long line, long position, long start, long end, String message) {}

    /** One analyzed compilation unit. */
    public record Unit(String relativePath, CompilationUnitTree tree, String text, int[] lineStarts) {

        public int line(long offset) {
            int i = Arrays.binarySearch(lineStarts, (int) offset);
            return i >= 0 ? i + 1 : -i - 1;
        }

        public int column(long offset) {
            return (int) (offset - lineStarts[line(offset) - 1]) + 1;
        }

        /** Text of 1-based line {@code line} without its terminator. */
        public String lineText(int line) {
            int start = lineStarts[line - 1];
            int end = line < lineStarts.length ? lineStarts[line] : text.length();
            String s = text.substring(start, end);
            return s.replaceAll("\\r?\\n$", "");
        }

        public int lineCount() {
            return lineStarts.length;
        }
    }

    /** Thrown when no attribution is possible at all (no compiler, unreadable files, javac crash). */
    public static final class AttributionException extends Exception {
        private static final long serialVersionUID = 1L;

        AttributionException(String message) {
            super(message);
        }
    }

    private final StandardJavaFileManager fileManager;
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final List<Unit> units;
    private final Map<CompilationUnitTree, Unit> byTree;
    private final List<CompilerError> errors;
    private final Map<String, List<CompilerError>> errorsByFile;
    private final Map<Element, TreePath> declarations;
    private final List<String> options;

    private SourceModel(StandardJavaFileManager fileManager, JavacTask task, List<Unit> units,
            List<CompilerError> errors, List<String> options) {
        this.fileManager = fileManager;
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
        this.types = task.getTypes();
        this.units = List.copyOf(units);
        this.byTree = new IdentityHashMap<>();
        units.forEach(u -> byTree.put(u.tree(), u));
        this.errors = List.copyOf(errors);
        this.errorsByFile = new HashMap<>();
        errors.forEach(e -> errorsByFile.computeIfAbsent(e.file(), f -> new ArrayList<>()).add(e));
        this.options = List.copyOf(options);
        this.declarations = new HashMap<>();
        for (Unit unit : units) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    record(getCurrentPath());
                    return super.visitVariable(node, unused);
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    record(getCurrentPath());
                    return super.visitMethod(node, unused);
                }

                private void record(TreePath path) {
                    Element e = trees.getElement(path);
                    if (e != null) {
                        declarations.putIfAbsent(e, path);
                    }
                }
            }.scan(unit.tree(), null);
        }
    }

    /** All {@code .java} files under the given directories, sorted, excluding {@code module-info.java}. */
    public static List<Path> javaFiles(List<Path> directories) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path dir : directories) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")
                        && !p.getFileName().toString().equals("module-info.java")).forEach(files::add);
            }
        }
        return files.stream().map(Path::normalize).distinct().sorted().toList();
    }

    /**
     * Parses and attributes {@code files} together.
     *
     * @param snapshotRoot root that relative paths are reported against
     * @param classpath dependency entries passed with {@code -classpath}
     * @param release javac {@code --release}, if configured
     */
    public static SourceModel attribute(Path snapshotRoot, List<Path> files, List<Path> classpath, OptionalInt release)
            throws AttributionException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AttributionException("no Java compiler is available in this runtime (a JDK is required)");
        }
        if (files.isEmpty()) {
            throw new AttributionException("no Java source files to analyze");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        List<String> options = new ArrayList<>(List.of("-proc:none", "-implicit:none", "-sourcepath", "",
                "--should-stop=ifError=FLOW", "-Xmaxerrs", "100000", "-XDaddTypeAnnotationsToSymbol=true"));
        if (!classpath.isEmpty()) {
            options.add("-classpath");
            options.add(String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        }
        if (release.isPresent()) {
            options.addAll(List.of("--release", Integer.toString(release.getAsInt())));
        }
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, diagnostics, options, null,
                    fm.getJavaFileObjectsFromPaths(files));
            List<CompilationUnitTree> parsed = new ArrayList<>();
            task.parse().forEach(parsed::add);
            task.analyze();
            Path root = snapshotRoot.toAbsolutePath().normalize();
            List<Unit> units = new ArrayList<>();
            for (CompilationUnitTree tree : parsed) {
                Path file = Path.of(tree.getSourceFile().toUri()).toAbsolutePath().normalize();
                String text = tree.getSourceFile().getCharContent(true).toString();
                units.add(new Unit(relative(root, file), tree, text, lineStarts(text)));
            }
            units.sort(Comparator.comparing(Unit::relativePath));
            List<CompilerError> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) {
                    continue;
                }
                String file = d.getSource() == null ? ""
                        : relative(root, Path.of(d.getSource().toUri()).toAbsolutePath().normalize());
                errors.add(new CompilerError(file, d.getLineNumber(), d.getPosition(), d.getStartPosition(),
                        d.getEndPosition(), d.getMessage(Locale.ROOT).lines().findFirst().orElse("")));
            }
            errors.sort(Comparator.comparing(CompilerError::file).thenComparingLong(CompilerError::position)
                    .thenComparing(CompilerError::message));
            return new SourceModel(fm, task, units, errors, options);
        } catch (IOException | RuntimeException e) {
            try {
                fm.close();
            } catch (IOException ignored) {
                // already failing
            }
            throw new AttributionException("javac could not parse and attribute the sources: " + e);
        }
    }

    private static String relative(Path root, Path file) {
        Path rel = file.startsWith(root) ? root.relativize(file) : file;
        return rel.toString().replace(java.io.File.separatorChar, '/');
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        if (starts.size() > 1 && starts.getLast() == text.length()) {
            starts.removeLast();
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    public Trees trees() {
        return trees;
    }

    public Elements elements() {
        return elements;
    }

    public Types types() {
        return types;
    }

    public SourcePositions positions() {
        return trees.getSourcePositions();
    }

    public List<Unit> units() {
        return units;
    }

    public Unit unit(CompilationUnitTree tree) {
        return byTree.get(tree);
    }

    public java.util.Optional<Unit> unit(String relativePath) {
        return units.stream().filter(u -> u.relativePath().equals(relativePath)).findFirst();
    }

    public List<CompilerError> errors() {
        return errors;
    }

    public List<CompilerError> errors(String relativePath) {
        return errorsByFile.getOrDefault(relativePath, List.of());
    }

    public List<String> options() {
        return options;
    }

    /** The source declaration (variable or method) of {@code element}, if it is declared in the analyzed sources. */
    public java.util.Optional<TreePath> declaration(Element element) {
        return java.util.Optional.ofNullable(declarations.get(element));
    }

    public long start(CompilationUnitTree unit, Tree tree) {
        return positions().getStartPosition(unit, tree);
    }

    public long end(CompilationUnitTree unit, Tree tree) {
        return positions().getEndPosition(unit, tree);
    }

    /** Source text of {@code tree}, or "" if it has no source range (compiler-generated). */
    public String text(CompilationUnitTree unit, Tree tree) {
        long s = start(unit, tree);
        long e = end(unit, tree);
        Unit u = unit(unit);
        if (u == null || s < 0 || e <= s || e > u.text().length()) {
            return "";
        }
        return u.text().substring((int) s, (int) e);
    }

    @Override
    public void close() throws IOException {
        fileManager.close();
    }
}
