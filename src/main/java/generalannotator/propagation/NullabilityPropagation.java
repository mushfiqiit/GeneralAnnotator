package generalannotator.propagation;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import generalannotator.context.SourceModel;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.AnnotationKinds;
import generalannotator.edits.AnnotationPosition;
import generalannotator.edits.EditAction;
import generalannotator.edits.MemberRef;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * The candidate analysis of the single-pass, warning-guided nullability propagation stage: every warning
 * of the run's baseline (its {@code warning-graph.json}) is classified against the unchanged snapshot,
 * attributed by javac ({@link SourceModel}). Only three flows match, and only when the diagnostic's kind,
 * the tree at its position and the resolved symbols all agree:
 * <ul>
 *   <li>{@link Rule#FIELD_ASSIGNMENT} / {@link Rule#FIELD_INITIALIZER}: NullAway's "assigning @Nullable
 *       expression to @NonNull field" on an assignment (or field initializer) whose value is a read of a
 *       field, or a call of a method, explicitly annotated {@code @Nullable}, or a local variable initialized
 *       (directly or through other locals) from one, and whose destination is a field of the editable sources
 *       without a nullness annotation;
 *   <li>{@link Rule#RETURN}: "returning @Nullable expression from method with @NonNull return type" on a
 *       {@code return} of such a value from an enclosing method of the editable sources that is not already
 *       {@code @Nullable}. The same package annotates the return type of every declaration the method overrides or
 *       implements (all supertypes, several interfaces, generic substitutions, and interface methods it implements
 *       for a subclass), never its overrides, siblings or overloads. Explicit non-null return annotations are
 *       replaced (recorded contract changes); already-nullable ancestors are kept. If an ancestor is declared outside
 *       the editable sources, the whole package is skipped ({@link SkipCategory#INCOMPLETE_HIERARCHY});
 *   <li>{@link Rule#ARGUMENT}: "passing @Nullable parameter 'e' where @NonNull is required" on an argument
 *       of a method or constructor call. The invoked executable is the one javac resolved (so overloads are
 *       told apart by attribution, never by name), the argument index maps to its formal parameter, and the
 *       argument is a field read, a method call or a local variable (or parameter); the diagnostic itself is
 *       the evidence that the value may be null. The receiving parameter is annotated {@code @Nullable}; an
 *       explicit non-null annotation on it is removed in the same package (a recorded contract change). For an
 *       overridable method (interface, abstract or overridden), the same package also annotates the parameter of
 *       every implementation and override found in the editable sources, by javac's override relation (inherited
 *       implementations and overrides of overrides included, never the method's ancestors, siblings or
 *       overloads); implementations that are already {@code @Nullable} are kept, explicit non-null ones replaced.
 *       If one of them cannot be edited (declared outside the editable sources or in an anonymous or local class)
 *       the whole package is skipped ({@link SkipCategory#INCOMPLETE_HIERARCHY}). Implementations outside the
 *       configured source roots are not discovered, and the evidence says so. Varargs positions, anonymous class
 *       creations, and executables declared outside the editable sources are skipped.
 * </ul>
 * The {@code @Nullable} annotation added is the one on the value's origin (the field, method or local); for
 * an argument without an annotated origin, the project's single nullable annotation type, if it uses exactly
 * one. Every other warning is skipped with its reason: other categories (for example dereferences of
 * {@code @Nullable} values), unsupported values ({@code null}, conditionals), unresolved or ambiguous positions
 * and symbols, explicit non-null contracts on fields, and destinations outside
 * the editable sources. Nothing is applied here; every candidate comes from the unchanged baseline, and
 * warnings that its edits introduce are left to a later pass.
 */
public final class NullabilityPropagation {

    /** The rule set's version, recorded with every result. */
    public static final String RULES = "nullable-flow-4";
    static final String ASSIGN = "assigning @Nullable expression to @NonNull field";
    static final String RETURN = "returning @Nullable expression from method with @NonNull return type";
    static final String PASS = "passing @Nullable parameter";
    static final String PASS_END = "where @NonNull is required";
    /** How many locals a value is traced back through to find its origin. */
    private static final int TRACE_DEPTH = 4;
    static final Set<String> NULLABLE = Set.of("Nullable", "CheckForNull", "NullableDecl", "NullableType",
            "RecentlyNullable");

    /** The matched flow. */
    public enum Rule { FIELD_ASSIGNMENT, FIELD_INITIALIZER, RETURN, ARGUMENT }

    /** Why a warning was skipped, grouped. */
    public enum SkipCategory {
        /** Another kind of warning (dereference, parameter, initialization, ...): out of scope for this pass. */
        OTHER_CATEGORY,
        /**
         * The value is not a read of a {@code @Nullable} field, a call of a {@code @Nullable} method or a local
         * traced to one (for arguments: not a field read, method call or local at all).
         */
        NOT_A_NULLABLE_FIELD_OR_METHOD,
        /** The position, tree or symbol could not be resolved unambiguously. */
        UNRESOLVED,
        /** The destination is already {@code @Nullable}, or is a field with an explicit non-null annotation. */
        EXPLICIT_CONTRACT,
        /** The destination is not declared in the editable project sources (or cannot be edited). */
        NOT_EDITABLE,
        /**
         * The receiving declaration is resolved but this pass does not change it: a varargs position or an
         * anonymous class creation.
         */
        UNSUPPORTED_TARGET,
        /**
         * The receiving method can be edited, but a declaration that must change with it (for a parameter, an
         * implementation or override in the editable sources; for a return, a declaration it overrides or
         * implements) cannot be changed consistently: it is declared outside the editable sources, in an anonymous
         * or local class, or carries an explicit non-null annotation that cannot be replaced. The whole package is
         * skipped.
         */
        INCOMPLETE_HIERARCHY
    }

    /**
     * A matched warning and its proposed edits, applied together as one package.
     *
     * @param edit the {@code @Nullable} annotation added to the receiving declaration
     * @param removal the explicit non-null annotation removed at the same position (a contract change), if any
     * @param hierarchy the edits to the receiving declaration's hierarchy, per declaration the removal of an
     *     explicit non-null annotation (if any) then the addition: for an argument passed to an overridable method,
     *     the same parameter of every implementation and override found in the editable sources; for a return, the
     *     return type of every declaration the method overrides or implements; empty otherwise
     */
    public record Match(String warningId, Rule rule, AnnotationEdit edit, Optional<AnnotationEdit> removal,
            List<AnnotationEdit> hierarchy, String evidence) {
        public Match {
            java.util.Objects.requireNonNull(removal, "removal");
            hierarchy = List.copyOf(hierarchy);
        }

        public Match(String warningId, Rule rule, AnnotationEdit edit, String evidence) {
            this(warningId, rule, edit, Optional.empty(), List.of(), evidence);
        }

        /**
         * Every edit of the match, applied together as one package: the removal (if any), the addition, then the
         * hierarchy's edits.
         */
        public List<AnnotationEdit> edits() {
            List<AnnotationEdit> all = new ArrayList<>();
            removal.ifPresent(all::add);
            all.add(edit);
            all.addAll(hierarchy);
            return List.copyOf(all);
        }

        /** Every explicit non-null annotation the package removes (contract changes), in order. */
        public List<AnnotationEdit> removals() {
            return edits().stream().filter(e -> e.action() == EditAction.REMOVE).toList();
        }
    }

    /** A declaration of the receiving method's hierarchy that cannot be changed consistently. */
    private static final class Incomplete extends Exception {
        private static final long serialVersionUID = 1L;

        Incomplete(String message) {
            super(message);
        }
    }

    /** Where a method declaration is edited. */
    private record Location(String root, String file, String declaringClass, MemberRef member) {}

    /**
     * Where a value comes from, as far as this pass follows it.
     *
     * @param kind {@code field}, {@code method result}, {@code local variable} or {@code parameter}; null for an
     *     unsupported value ({@code null}, a conditional, a cast, ...)
     * @param annotation the explicit {@code @Nullable} annotation found on the value's origin, if any
     * @param description a readable account of the value and its origin
     */
    private record Origin(String kind, Optional<String> annotation, String description) {}

    /** A skipped warning and why. */
    public record Skip(String warningId, SkipCategory category, String reason) {}

    /** The analysis of one baseline. */
    public record Analysis(String runId, List<String> sourceRoots, int warnings, List<Match> matches,
            List<Skip> skipped, List<String> compilerErrors) {
        public Analysis {
            sourceRoots = List.copyOf(sourceRoots);
            matches = List.copyOf(matches);
            skipped = List.copyOf(skipped);
            compilerErrors = List.copyOf(compilerErrors);
        }
    }

    /** The run directory cannot be analyzed at all. */
    public static final class InvalidException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidException(String message) {
            super(message);
        }
    }

    private final SourceModel model;
    private final List<String> sourceRoots;
    private List<TypeElement> sourceTypes;
    private Optional<String> projectNullable;

    private NullabilityPropagation(SourceModel model, List<String> sourceRoots) {
        this.model = model;
        this.sourceRoots = sourceRoots;
    }

    /** Analyzes every warning of the run's baseline against its snapshot (read only). */
    public static Analysis analyze(Path runDir) throws IOException, InvalidException {
        Path report = runDir.resolve("report.json");
        Path graphFile = runDir.resolve(WarningGraphJson.FILE_NAME);
        Path snapshot = runDir.resolve("workspace").resolve("target");
        if (!Files.isRegularFile(report) || !Files.isRegularFile(graphFile) || !Files.isDirectory(snapshot)) {
            throw new InvalidException(runDir + " is not a completed run (report.json, " + WarningGraphJson.FILE_NAME
                    + " and workspace/target are required)");
        }
        Map<?, ?> r = (Map<?, ?>) JsonParser.parse(Files.readString(report, StandardCharsets.UTF_8));
        Map<?, ?> config = r.get("configuration") instanceof Map<?, ?> c ? c : Map.of();
        List<String> roots = new ArrayList<>();
        if (config.get("sourceRoots") instanceof List<?> l) {
            l.forEach(o -> roots.add(String.valueOf(o)));
        }
        if (roots.isEmpty()) {
            throw new InvalidException(report + " has no configured source roots");
        }
        List<Path> classpath = new ArrayList<>();
        if (config.get("classpath") instanceof List<?> l) {
            for (Object o : l) {
                String e = String.valueOf(o);
                classpath.add(e.startsWith("<target>/") ? snapshot.resolve(e.substring("<target>/".length()))
                        : Path.of(e));
            }
        }
        OptionalInt release = config.get("javacRelease") instanceof Long v ? OptionalInt.of(Math.toIntExact(v))
                : OptionalInt.empty();
        WarningGraph graph = WarningGraphJson.load(graphFile);
        List<Path> files = SourceModel.javaFiles(roots.stream().map(snapshot::resolve).toList());
        try (SourceModel model = SourceModel.attribute(snapshot, files, classpath, release)) {
            NullabilityPropagation analysis = new NullabilityPropagation(model, roots);
            List<Match> matches = new ArrayList<>();
            List<Skip> skipped = new ArrayList<>();
            for (WarningNode node : graph.warningNodes()) {
                Object outcome = analysis.classify(node);
                if (outcome instanceof Match m) {
                    matches.add(m);
                } else {
                    skipped.add((Skip) outcome);
                }
            }
            List<String> errors = model.errors().stream().limit(50)
                    .map(e -> e.file() + ":" + e.line() + ": " + e.message()).toList();
            return new Analysis(String.valueOf(r.get("runId")), roots, graph.warningNodes().size(), matches, skipped,
                    errors);
        } catch (SourceModel.AttributionException e) {
            throw new InvalidException("the snapshot cannot be attributed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------

    private Object classify(WarningNode node) {
        String id = node.id();
        Diagnostic d = node.diagnostic();
        String message = d.message().lines().findFirst().orElse("").strip();
        boolean assign = message.startsWith(ASSIGN);
        boolean ret = message.startsWith(RETURN);
        boolean arg = message.startsWith(PASS) && message.endsWith(PASS_END);
        if (!assign && !ret && !arg) {
            return new Skip(id, SkipCategory.OTHER_CATEGORY, message.startsWith("dereferenced expression")
                    ? "a dereference of a @Nullable value; this pass annotates only nullable assignments, returns and "
                            + "arguments, never dereferences"
                    : "not an assignment, return or argument of a @Nullable value; other warning categories are "
                            + "ignored in this pass");
        }
        if (node.targetPath().isEmpty() || d.column().isEmpty()) {
            return new Skip(id, SkipCategory.UNRESOLVED, "the finding has no position inside the snapshot");
        }
        Optional<SourceModel.Unit> unit = model.unit(node.targetPath().get());
        if (unit.isEmpty()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, node.targetPath().get() + " is not under the configured "
                    + "source roots");
        }
        SourceModel.Unit u = unit.get();
        if (d.line() < 1 || d.line() > u.lineCount()) {
            return new Skip(id, SkipCategory.UNRESOLVED, "line " + d.line() + " is outside " + u.relativePath());
        }
        long offset = u.lineStarts()[d.line() - 1] + (long) d.column().getAsInt() - 1;
        if (arg) {
            return classifyArgument(id, message, u, offset);
        }
        Optional<TreePath> site = innermost(u.tree(), offset, assign);
        if (site.isEmpty()) {
            return new Skip(id, SkipCategory.UNRESOLVED, "no " + (assign ? "assignment or field initializer"
                    : "return statement") + " encloses the diagnostic position; the diagnostic does not concern a "
                    + "matchable flow");
        }
        TreePath path = site.get();
        ExpressionTree value;
        Element destination;
        Rule rule;
        String destinationText;
        CompilationUnitTree cu = u.tree();
        if (path.getLeaf() instanceof AssignmentTree a) {
            rule = Rule.FIELD_ASSIGNMENT;
            value = a.getExpression();
            ExpressionTree lhs = strip(a.getVariable());
            destination = model.trees().getElement(new TreePath(path, lhs));
            destinationText = model.text(cu, lhs);
            if (destination == null || destination.getKind() != ElementKind.FIELD) {
                return new Skip(id, SkipCategory.NOT_EDITABLE, "the assigned variable '" + destinationText
                        + "' is not a field");
            }
        } else if (path.getLeaf() instanceof VariableTree v) {
            rule = Rule.FIELD_INITIALIZER;
            value = v.getInitializer();
            destination = model.trees().getElement(path);
            destinationText = v.getName().toString();
            if (value == null || destination == null || destination.getKind() != ElementKind.FIELD) {
                return new Skip(id, SkipCategory.NOT_EDITABLE, "'" + destinationText + "' is not a field with an "
                        + "initializer");
            }
        } else {
            rule = Rule.RETURN;
            value = ((ReturnTree) path.getLeaf()).getExpression();
            Optional<TreePath> method = enclosingMethod(path);
            if (method.isEmpty()) {
                return new Skip(id, SkipCategory.UNRESOLVED, "the return is inside a lambda or outside a method");
            }
            destination = model.trees().getElement(method.get());
            destinationText = destination == null ? "?" : destination.getSimpleName() + "()";
            if (value == null || !(destination instanceof ExecutableElement m)
                    || m.getKind() != ElementKind.METHOD || m.getReturnType().getKind() == TypeKind.VOID) {
                return new Skip(id, SkipCategory.UNRESOLVED, "the return does not belong to a method with a result");
            }
        }

        // The value: a read of an explicitly @Nullable field, a call of an explicitly @Nullable method, or a local
        // initialized from one.
        ExpressionTree v = strip(value);
        String valueText = model.text(cu, v);
        Origin origin = origin(v, cu, 0);
        if (origin.kind() == null) {
            return new Skip(id, SkipCategory.NOT_A_NULLABLE_FIELD_OR_METHOD, "the value '" + valueText + "' is not a "
                    + "field read, method call or local variable (for example null or a conditional)");
        }
        Optional<String> nullable = origin.annotation();
        if (nullable.isEmpty()) {
            return new Skip(id, SkipCategory.NOT_A_NULLABLE_FIELD_OR_METHOD, origin.description() + ", which is not "
                    + "explicitly annotated @Nullable (NullAway may treat it as nullable for another reason, which "
                    + "this pass does not follow)");
        }
        if (!nullable.get().contains(".")) {
            return new Skip(id, SkipCategory.UNRESOLVED, "the @Nullable annotation of '" + valueText + "' does not "
                    + "resolve to a qualified type (is it on the classpath?)");
        }

        // The destination: editable, single-declaration, without any explicit nullness contract.
        TypeMirror type = destination instanceof ExecutableElement m ? m.getReturnType() : destination.asType();
        if (type.getKind().isPrimitive()) {
            return new Skip(id, SkipCategory.UNRESOLVED, "'" + destinationText + "' has a primitive type");
        }
        Optional<String> explicitNullable = nullness(destination, "nullable");
        if (explicitNullable.isPresent()) {
            return new Skip(id, SkipCategory.EXPLICIT_CONTRACT, "'" + destinationText + "' is already @"
                    + AnnotationKinds.simpleName(explicitNullable.get()));
        }
        if (rule == Rule.RETURN) {
            return classifyReturn(id, (ExecutableElement) destination, valueText, origin, nullable.get());
        }
        Optional<String> nonNull = nullness(destination, "non-null");
        if (nonNull.isPresent()) {
            return new Skip(id, SkipCategory.EXPLICIT_CONTRACT, "'" + destinationText + "' has an explicit non-null "
                    + "contract (@" + nonNull.get() + ")");
        }
        Optional<TreePath> declaration = model.declaration(destination);
        if (declaration.isEmpty()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, "'" + destinationText + "' is declared outside the editable "
                    + "project sources");
        }
        Element owner = destination.getEnclosingElement();
        if (!(owner instanceof TypeElement type0) || type0.getQualifiedName().isEmpty()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, "'" + destinationText + "' is declared in an anonymous or "
                    + "local class");
        }
        CompilationUnitTree declUnit = declaration.get().getCompilationUnit();
        SourceModel.Unit du = model.unit(declUnit);
        Optional<String> root = du == null ? Optional.empty() : sourceRoots.stream()
                .filter(r -> r.equals(".") || du.relativePath().startsWith(r + "/"))
                .max((x, y) -> Integer.compare(x.length(), y.length()));
        if (root.isEmpty()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, "'" + destinationText + "' is not in a configured source "
                    + "root");
        }
        if (declaration.get().getLeaf() instanceof VariableTree field && sharesDeclaration(declaration.get(), field)) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, "field '" + destinationText + "' is declared together with "
                    + "other fields; the applier edits single-field declarations only");
        }
        String file = root.get().equals(".") ? du.relativePath() : du.relativePath().substring(root.get().length() + 1);
        String member;
        if (declaration.get().getLeaf() instanceof MethodTree mt) {
            List<String> params = new ArrayList<>();
            for (VariableTree p : mt.getParameters()) {
                params.add(model.text(declUnit, p.getType()));
            }
            member = mt.getName() + "(" + String.join(", ", params) + ")";
        } else {
            member = destination.getSimpleName().toString();
        }
        AnnotationEdit edit;
        try {
            edit = new AnnotationEdit(root.get(), file, type0.getQualifiedName().toString(), MemberRef.parse(member),
                    rule == Rule.RETURN ? AnnotationPosition.returnType() : AnnotationPosition.field(),
                    EditAction.ADD, nullable.get());
        } catch (IllegalArgumentException e) {
            return new Skip(id, SkipCategory.UNRESOLVED, "no edit can describe '" + destinationText + "': "
                    + e.getMessage());
        }
        String evidence = switch (rule) {
            case FIELD_ASSIGNMENT -> "'" + model.text(cu, path.getLeaf()) + "' assigns " + origin.description()
                    + " to the unannotated field " + type0.getQualifiedName() + "#" + member;
            case FIELD_INITIALIZER -> "the initializer of field " + type0.getQualifiedName() + "#" + member
                    + " is " + origin.description();
            case RETURN, ARGUMENT -> throw new IllegalStateException();
        };
        return new Match(id, rule, edit, evidence);
    }

    /**
     * The {@link Rule#RETURN} flow, once the value is known to be explicitly {@code @Nullable}: the returning
     * method's return type is annotated, together with the return type of every declaration it overrides or
     * implements (its ancestors, which would otherwise promise a non-null result the method no longer keeps).
     * Overrides of the method and sibling implementations are left alone: a narrower, non-null return is
     * compatible with a nullable one.
     */
    private Object classifyReturn(String id, ExecutableElement m, String valueText, Origin origin, String nullable) {
        String target = describe(m);
        Optional<String> nonNull = nullness(m, "non-null");
        if (nonNull.isPresent() && !nonNull.get().contains(".")) {
            return new Skip(id, SkipCategory.UNRESOLVED, "the explicit @" + nonNull.get() + " return of " + target
                    + " does not resolve to a qualified type, so it cannot be replaced");
        }
        if (model.declaration(m).isEmpty()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, target + " is declared outside the editable project "
                    + "sources");
        }
        if (!(m.getEnclosingElement() instanceof TypeElement owner) || owner.getQualifiedName().isEmpty()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, "'" + m.getSimpleName() + "()' is declared in an anonymous "
                    + "or local class");
        }
        Location at;
        try {
            at = locate(m);
        } catch (Incomplete e) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, e.getMessage());
        }
        AnnotationEdit edit = new AnnotationEdit(at.root(), at.file(), at.declaringClass(), at.member(),
                AnnotationPosition.returnType(), EditAction.ADD, nullable);
        Optional<AnnotationEdit> removal = nonNull.map(n -> new AnnotationEdit(at.root(), at.file(),
                at.declaringClass(), at.member(), AnnotationPosition.returnType(), EditAction.REMOVE, n));

        List<ExecutableElement> ancestors;
        try {
            ancestors = ancestors(m);
        } catch (Incomplete e) {
            return new Skip(id, SkipCategory.INCOMPLETE_HIERARCHY, "a @Nullable return of " + target + " requires "
                    + "the return contracts it overrides or implements to be @Nullable too, but " + e.getMessage()
                    + "; the whole package is skipped");
        }
        List<AnnotationEdit> hierarchy = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        nonNull.ifPresent(n -> replaced.add(target + " (@" + AnnotationKinds.simpleName(n) + ")"));
        for (ExecutableElement a : ancestors) {
            String which = describe(a);
            if (nullness(a, "nullable").isPresent()) {
                kept.add(which);
                continue;
            }
            Optional<String> aNonNull = nullness(a, "non-null");
            if (aNonNull.isPresent() && !aNonNull.get().contains(".")) {
                return new Skip(id, SkipCategory.INCOMPLETE_HIERARCHY, "a @Nullable return of " + target
                        + " requires changing " + which + " too, but its explicit @" + aNonNull.get() + " does not "
                        + "resolve to a qualified type, so it cannot be replaced; the whole package is skipped");
            }
            Location aa;
            try {
                aa = locate(a);
            } catch (Incomplete e) {
                return new Skip(id, SkipCategory.INCOMPLETE_HIERARCHY, "a @Nullable return of " + target
                        + " requires changing " + which + " too, but " + e.getMessage()
                        + "; the whole package is skipped");
            }
            aNonNull.ifPresent(n -> {
                hierarchy.add(new AnnotationEdit(aa.root(), aa.file(), aa.declaringClass(), aa.member(),
                        AnnotationPosition.returnType(), EditAction.REMOVE, n));
                replaced.add(which + " (@" + AnnotationKinds.simpleName(n) + ")");
            });
            hierarchy.add(new AnnotationEdit(aa.root(), aa.file(), aa.declaringClass(), aa.member(),
                    AnnotationPosition.returnType(), EditAction.ADD, nullable));
            changed.add(which);
        }
        StringBuilder evidence = new StringBuilder("'return " + valueText + "' returns " + origin.description()
                + " from the " + (nonNull.isPresent() ? "" : "unannotated ") + "method " + owner.getQualifiedName()
                + "#" + at.member().canonical());
        if (!ancestors.isEmpty()) {
            evidence.append("; the return contracts it overrides or implements: ")
                    .append(changed.isEmpty() ? "" : "also annotated " + String.join(", ", changed))
                    .append(!changed.isEmpty() && !kept.isEmpty() ? "; " : "")
                    .append(kept.isEmpty() ? "" : "already @Nullable, kept: " + String.join(", ", kept))
                    .append("; overrides and sibling implementations keep their non-null returns");
        }
        if (!replaced.isEmpty()) {
            evidence.append("; contract change: the explicit non-null return annotation of ")
                    .append(String.join(", ", replaced)).append(" is replaced by @").append(nullable);
        }
        return new Match(id, Rule.RETURN, edit, removal, hierarchy, evidence.toString());
    }

    /**
     * Every method declaration that {@code m} overrides or implements, sorted: in every supertype of its class
     * (multi-level and through several interfaces), and, for an implementation inherited by a subclass of the
     * editable sources ({@code class Impl extends Helper implements I}), the interface methods it implements
     * there. The relation is javac's ({@link javax.lang.model.util.Elements#overrides}, with type arguments
     * substituted), so overloads are excluded.
     *
     * @throws Incomplete if one of them is declared outside the editable sources
     */
    private List<ExecutableElement> ancestors(ExecutableElement m) throws Incomplete {
        if (!(m.getEnclosingElement() instanceof TypeElement owner) || m.getModifiers().contains(Modifier.STATIC)
                || m.getModifiers().contains(Modifier.PRIVATE)) {
            return List.of();
        }
        TypeMirror ownerType = model.types().erasure(owner.asType());
        List<TypeElement> origins = new ArrayList<>(List.of(owner));
        for (TypeElement t : sourceTypes()) {
            if (!t.equals(owner) && model.types().isSubtype(model.types().erasure(t.asType()), ownerType)) {
                origins.add(t);
            }
        }
        Set<ExecutableElement> found = new LinkedHashSet<>();
        for (TypeElement origin : origins) {
            Set<TypeElement> seen = new java.util.HashSet<>();
            List<TypeMirror> pending = new ArrayList<>(model.types().directSupertypes(origin.asType()));
            while (!pending.isEmpty()) {
                TypeMirror t = pending.remove(0);
                if (!(model.types().asElement(t) instanceof TypeElement te) || !seen.add(te)) {
                    continue;
                }
                pending.addAll(model.types().directSupertypes(t));
                if (!te.equals(owner) && model.types().isSubtype(model.types().erasure(te.asType()), ownerType)) {
                    continue; // between m's class and a subclass: overrides of m, not contracts above it
                }
                for (Element member : te.getEnclosedElements()) {
                    if (member instanceof ExecutableElement other && other != m && other.getKind() == ElementKind.METHOD
                            && model.elements().overrides(m, other, origin)) {
                        found.add(other);
                    }
                }
            }
        }
        for (ExecutableElement a : found) {
            if (model.declaration(a).isEmpty()) {
                throw new Incomplete(describe(a) + ", which " + describe(m) + " overrides or implements, is declared "
                        + "outside the editable project sources (library or JDK); external contracts are not changed");
            }
        }
        List<ExecutableElement> sorted = new ArrayList<>(found);
        sorted.sort(java.util.Comparator.comparing(NullabilityPropagation::describe));
        return sorted;
    }

    /**
     * The {@link Rule#ARGUMENT} flow: the argument at the diagnostic position, the executable javac resolved for
     * its call, and the formal parameter at the argument's index.
     */
    private Object classifyArgument(String id, String message, SourceModel.Unit u, long offset) {
        CompilationUnitTree cu = u.tree();
        int q1 = message.indexOf('\'');
        int q2 = message.lastIndexOf('\'');
        Optional<String> quoted = q1 >= 0 && q2 > q1 ? Optional.of(message.substring(q1 + 1, q2)) : Optional.empty();
        List<ArgumentSite> sites = argumentSites(cu, offset);
        if (quoted.isPresent()) {
            sites = sites.stream().filter(s -> squash(model.text(cu, s.argument())).equals(squash(quoted.get())))
                    .toList();
        }
        if (sites.isEmpty()) {
            return new Skip(id, SkipCategory.UNRESOLVED, "no method or constructor call has an argument "
                    + quoted.map(t -> "'" + t + "' ").orElse("") + "at the diagnostic position");
        }
        ArgumentSite site = sites.get(sites.size() - 1); // the innermost call
        if (sites.stream().filter(s -> s.depth() == site.depth()).count() > 1) {
            return new Skip(id, SkipCategory.UNRESOLVED, "several call arguments match the diagnostic position");
        }
        Tree call = site.call().getLeaf();
        String callText = model.text(cu, call);
        if (call instanceof NewClassTree nc && nc.getClassBody() != null) {
            return new Skip(id, SkipCategory.UNSUPPORTED_TARGET, "'" + callText + "' creates an anonymous class; its "
                    + "constructor has no editable declaration");
        }
        Element invoked = model.trees().getElement(site.call());
        if (!(invoked instanceof ExecutableElement m)
                || (m.getKind() != ElementKind.METHOD && m.getKind() != ElementKind.CONSTRUCTOR)) {
            return new Skip(id, SkipCategory.UNRESOLVED, "the call '" + callText + "' does not resolve to a method or "
                    + "constructor (does the snapshot compile?)");
        }
        int index = site.index();
        String argText = model.text(cu, site.argument());
        List<? extends VariableElement> params = m.getParameters();
        String target = describe(m);
        if (m.isVarArgs() && index >= params.size() - 1) {
            return new Skip(id, SkipCategory.UNSUPPORTED_TARGET, "argument " + (index + 1) + " ('" + argText + "') of '"
                    + callText + "' is passed to the varargs parameter of " + target + "; annotating the varargs array "
                    + "or its elements is not supported");
        }
        if (index >= params.size()) {
            return new Skip(id, SkipCategory.UNRESOLVED, "argument " + (index + 1) + " has no formal parameter in "
                    + target);
        }
        VariableElement formal = params.get(index);
        String formalText = "parameter '" + formal.getSimpleName() + "' (index " + index + ") of " + target;
        if (formal.asType().getKind().isPrimitive()) {
            return new Skip(id, SkipCategory.UNRESOLVED, formalText + " has a primitive type");
        }

        // The value: the diagnostic says it may be null; its origin only chooses the annotation.
        Origin origin = origin(site.argument(), cu, 0);
        if (origin.kind() == null) {
            return new Skip(id, SkipCategory.NOT_A_NULLABLE_FIELD_OR_METHOD, "the argument '" + argText + "' is not a "
                    + "field read, method call or local variable (for example null, a conditional or a cast)");
        }
        Optional<String> nullable = origin.annotation().filter(a -> a.contains(".")).or(this::projectNullable);
        if (nullable.isEmpty()) {
            return new Skip(id, SkipCategory.UNRESOLVED, "no qualified @Nullable annotation to add: "
                    + origin.description() + " carries none, and the project does not use exactly one nullable "
                    + "annotation type");
        }

        // The receiving parameter: editable, not overridden, not already @Nullable.
        Optional<String> explicitNullable = nullness(formal, "nullable");
        if (explicitNullable.isPresent()) {
            return new Skip(id, SkipCategory.EXPLICIT_CONTRACT, formalText + " is already @"
                    + AnnotationKinds.simpleName(explicitNullable.get()));
        }
        Optional<String> nonNull = nullness(formal, "non-null");
        if (nonNull.isPresent() && !nonNull.get().contains(".")) {
            return new Skip(id, SkipCategory.UNRESOLVED, "the explicit @" + nonNull.get() + " of " + formalText
                    + " does not resolve to a qualified type, so it cannot be replaced");
        }
        if (model.declaration(m).isEmpty()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, target + " is declared outside the editable project "
                    + "sources (library, JDK or generated code)");
        }
        if (!(m.getEnclosingElement() instanceof TypeElement owner) || owner.getQualifiedName().isEmpty()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, target + " is declared in an anonymous or local class");
        }
        Location at;
        try {
            at = locate(m);
        } catch (Incomplete e) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, e.getMessage());
        }
        AnnotationEdit edit = new AnnotationEdit(at.root(), at.file(), at.declaringClass(), at.member(),
                AnnotationPosition.parameter(index), EditAction.ADD, nullable.get());
        Optional<AnnotationEdit> removal = nonNull.map(n -> new AnnotationEdit(at.root(), at.file(),
                at.declaringClass(), at.member(), AnnotationPosition.parameter(index), EditAction.REMOVE, n));

        // The hierarchy: every implementation or override of m in the editable sources must accept null too.
        List<ExecutableElement> implementations;
        try {
            implementations = implementations(m);
        } catch (Incomplete e) {
            return new Skip(id, SkipCategory.INCOMPLETE_HIERARCHY, "making " + formalText + " @Nullable requires "
                    + "changing its implementations and overrides too, but " + e.getMessage()
                    + "; the whole package is skipped");
        }
        List<AnnotationEdit> hierarchy = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        nonNull.ifPresent(n -> replaced.add(target + " (@" + AnnotationKinds.simpleName(n) + ")"));
        for (ExecutableElement x : implementations) {
            String which = describe(x);
            VariableElement p = x.getParameters().get(index);
            if (x.isVarArgs() && index == x.getParameters().size() - 1) {
                return new Skip(id, SkipCategory.UNSUPPORTED_TARGET, "the override " + which + " declares parameter "
                        + (index + 1) + " as varargs; annotating the varargs array or its elements is not supported");
            }
            if (nullness(p, "nullable").isPresent()) {
                kept.add(which);
                continue;
            }
            Optional<String> xNonNull = nullness(p, "non-null");
            if (xNonNull.isPresent() && !xNonNull.get().contains(".")) {
                return new Skip(id, SkipCategory.INCOMPLETE_HIERARCHY, "making " + formalText + " @Nullable requires "
                        + "changing " + which + " too, but its explicit @" + xNonNull.get() + " does not resolve to a "
                        + "qualified type, so it cannot be replaced; the whole package is skipped");
            }
            Location xa;
            try {
                xa = locate(x);
            } catch (Incomplete e) {
                return new Skip(id, SkipCategory.INCOMPLETE_HIERARCHY, "making " + formalText + " @Nullable requires "
                        + "changing " + which + " too, but " + e.getMessage() + "; the whole package is skipped");
            }
            xNonNull.ifPresent(n -> {
                hierarchy.add(new AnnotationEdit(xa.root(), xa.file(), xa.declaringClass(), xa.member(),
                        AnnotationPosition.parameter(index), EditAction.REMOVE, n));
                replaced.add(which + " (@" + AnnotationKinds.simpleName(n) + ")");
            });
            hierarchy.add(new AnnotationEdit(xa.root(), xa.file(), xa.declaringClass(), xa.member(),
                    AnnotationPosition.parameter(index), EditAction.ADD, nullable.get()));
            changed.add(which);
        }
        StringBuilder evidence = new StringBuilder("'" + callText + "' passes " + origin.description() + ", which "
                + "NullAway reports as @Nullable, as argument " + (index + 1) + " to parameter '"
                + formal.getSimpleName() + "' of " + owner.getQualifiedName() + "#" + at.member().canonical());
        if (origin.annotation().filter(a -> a.contains(".")).isEmpty()) {
            evidence.append(" (annotation @").append(nullable.get()).append(": the project's nullable annotation type)");
        }
        if (overridable(m)) {
            evidence.append("; hierarchy (the configured source roots only; implementations elsewhere, such as other "
                    + "modules, generated or library code, are not discovered): ");
            evidence.append(changed.isEmpty() && kept.isEmpty() ? "no implementation or override found"
                    : (changed.isEmpty() ? "" : "also annotated " + String.join(", ", changed))
                    + (!changed.isEmpty() && !kept.isEmpty() ? "; " : "")
                    + (kept.isEmpty() ? "" : "already @Nullable, kept: " + String.join(", ", kept)));
        }
        if (!replaced.isEmpty()) {
            evidence.append("; contract change: the explicit non-null annotation of ").append(String.join(", ",
                    replaced)).append(" is replaced by @").append(nullable.get());
        }
        return new Match(id, Rule.ARGUMENT, edit, removal, hierarchy, evidence.toString());
    }

    /**
     * Where {@code m} is edited: its configured source root, file, declaring class and member text.
     *
     * @throws Incomplete if it has no source declaration in a configured source root, or is declared in an
     *     anonymous or local class
     */
    private Location locate(ExecutableElement m) throws Incomplete {
        String which = describe(m);
        Optional<TreePath> declaration = model.declaration(m);
        if (declaration.isEmpty() || !(declaration.get().getLeaf() instanceof MethodTree mt)
                || mt.getParameters().size() != m.getParameters().size()) {
            throw new Incomplete(which + " is declared outside the editable project sources (library, JDK or "
                    + "generated code)");
        }
        if (!(m.getEnclosingElement() instanceof TypeElement owner) || owner.getQualifiedName().isEmpty()) {
            throw new Incomplete(which + " is declared in an anonymous or local class, which no edit can address");
        }
        CompilationUnitTree declUnit = declaration.get().getCompilationUnit();
        SourceModel.Unit du = model.unit(declUnit);
        Optional<String> root = du == null ? Optional.empty() : sourceRoots.stream()
                .filter(r -> r.equals(".") || du.relativePath().startsWith(r + "/"))
                .max((x, y) -> Integer.compare(x.length(), y.length()));
        if (root.isEmpty()) {
            throw new Incomplete(which + " is not in a configured source root");
        }
        String file = root.get().equals(".") ? du.relativePath() : du.relativePath().substring(root.get().length() + 1);
        try {
            return new Location(root.get(), file, owner.getQualifiedName().toString(),
                    MemberRef.parse(member(mt, declUnit, m)));
        } catch (IllegalArgumentException e) {
            throw new Incomplete("no edit can describe " + which + ": " + e.getMessage());
        }
    }

    /** A call argument: the call's path, the argument's index, and how deeply the call is nested. */
    private record ArgumentSite(TreePath call, int index, ExpressionTree argument, int depth) {}

    /** Every call argument whose source range contains {@code offset}, outermost call first. */
    private List<ArgumentSite> argumentSites(CompilationUnitTree cu, long offset) {
        List<ArgumentSite> sites = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            private int depth;

            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree == null) {
                    return null;
                }
                long start = model.start(cu, tree);
                long end = model.end(cu, tree);
                if (start >= 0 && end >= 0 && (offset < start || offset >= end)) {
                    return null;
                }
                return super.scan(tree, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                return call(node.getArguments(), () -> super.visitMethodInvocation(node, unused));
            }

            @Override
            public Void visitNewClass(NewClassTree node, Void unused) {
                return call(node.getArguments(), () -> super.visitNewClass(node, unused));
            }

            private Void call(List<? extends ExpressionTree> args, java.util.function.Supplier<Void> descend) {
                for (int i = 0; i < args.size(); i++) {
                    long start = model.start(cu, args.get(i));
                    long end = model.end(cu, args.get(i));
                    if (start >= 0 && offset >= start && offset < end) {
                        sites.add(new ArgumentSite(getCurrentPath(), i, args.get(i), depth));
                    }
                }
                depth++;
                try {
                    return descend.get();
                } finally {
                    depth--;
                }
            }
        }.scan(cu, null);
        return sites;
    }

    /** Where {@code value} comes from: a field read, a method call, or a local traced through its initializer. */
    private Origin origin(ExpressionTree value, CompilationUnitTree cu, int depth) {
        ExpressionTree v = strip(value);
        TreePath path = TreePath.getPath(cu, v);
        Element e = path == null ? null : model.trees().getElement(path);
        String text = model.text(cu, v);
        if (e == null) {
            return new Origin(null, Optional.empty(), "'" + text + "'");
        }
        Optional<String> explicit = nullness(e, "nullable");
        boolean name = v instanceof IdentifierTree || v instanceof MemberSelectTree;
        if (name && e.getKind() == ElementKind.FIELD) {
            return new Origin("field", explicit, "the " + (explicit.isPresent() ? "@Nullable " : "") + "field '"
                    + text + "'");
        }
        if (v instanceof MethodInvocationTree && e instanceof ExecutableElement) {
            return new Origin("method result", explicit, "the " + (explicit.isPresent() ? "@Nullable " : "")
                    + "method result '" + text + "'");
        }
        boolean local = e.getKind() == ElementKind.LOCAL_VARIABLE || e.getKind() == ElementKind.RESOURCE_VARIABLE;
        if (v instanceof IdentifierTree && (local || e.getKind() == ElementKind.PARAMETER)) {
            String kind = local ? "local variable" : "parameter";
            if (explicit.isPresent()) {
                return new Origin(kind, explicit, "the @Nullable " + kind + " '" + text + "'");
            }
            Optional<TreePath> declaration = model.declaration(e);
            if (local && depth < TRACE_DEPTH && declaration.isPresent()
                    && declaration.get().getLeaf() instanceof VariableTree vt && vt.getInitializer() != null) {
                Origin from = origin(vt.getInitializer(), declaration.get().getCompilationUnit(), depth + 1);
                return new Origin(kind, from.kind() == null ? Optional.empty() : from.annotation(), "the " + kind
                        + " '" + text + "' (initialized from " + from.description() + ")");
            }
            return new Origin(kind, Optional.empty(), "the " + kind + " '" + text + "'");
        }
        return new Origin(null, Optional.empty(), "'" + text + "'");
    }

    /**
     * The project's nullable annotation type: the single qualified nullable annotation on any declaration (field,
     * method, parameter or local) of the editable sources, if there is exactly one.
     */
    private Optional<String> projectNullable() {
        if (projectNullable == null) {
            Set<String> found = new LinkedHashSet<>();
            for (SourceModel.Unit unit : model.units()) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree node, Void unused) {
                        add(model.trees().getElement(getCurrentPath()));
                        return super.visitVariable(node, unused);
                    }

                    @Override
                    public Void visitMethod(MethodTree node, Void unused) {
                        add(model.trees().getElement(getCurrentPath()));
                        return super.visitMethod(node, unused);
                    }

                    private void add(Element e) {
                        if (e != null) {
                            nullness(e, "nullable").filter(a -> a.contains(".")).ifPresent(found::add);
                        }
                    }
                }.scan(unit.tree(), null);
            }
            projectNullable = found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
        }
        return projectNullable;
    }

    /** Whether {@code m} can be overridden or implemented at all. */
    private static boolean overridable(ExecutableElement m) {
        Set<Modifier> mods = m.getModifiers();
        return m.getKind() == ElementKind.METHOD && !mods.contains(Modifier.STATIC) && !mods.contains(Modifier.PRIVATE)
                && !mods.contains(Modifier.FINAL) && !(m.getEnclosingElement() instanceof TypeElement t
                        && t.getModifiers().contains(Modifier.FINAL));
    }

    /**
     * Every method of the editable sources' types that overrides or implements {@code m}, directly, through an
     * implementation inherited from another class, or through intermediate overrides (overrides of overrides),
     * sorted. The relation is javac's ({@link javax.lang.model.util.Elements#overrides}, with type arguments
     * substituted), never a name match, so overloads and unrelated methods are excluded; ancestors of {@code m}
     * are never included (a parameter may be widened in an override without changing what it overrides).
     *
     * @throws Incomplete if one of them is declared outside the editable sources or in an anonymous or local class
     */
    private List<ExecutableElement> implementations(ExecutableElement m) throws Incomplete {
        if (!overridable(m)) {
            return List.of();
        }
        List<ExecutableElement> found = new ArrayList<>();
        Set<ExecutableElement> seen = new java.util.HashSet<>(List.of(m));
        java.util.ArrayDeque<ExecutableElement> pending = new java.util.ArrayDeque<>(List.of(m));
        while (!pending.isEmpty()) {
            ExecutableElement y = pending.poll();
            TypeElement yOwner = (TypeElement) y.getEnclosingElement();
            TypeMirror yType = model.types().erasure(yOwner.asType());
            for (TypeElement t : sourceTypes()) {
                if (t.equals(yOwner) || !model.types().isSubtype(model.types().erasure(t.asType()), yType)) {
                    continue;
                }
                for (Element member : model.elements().getAllMembers(t)) {
                    if (!(member instanceof ExecutableElement x) || x.getKind() != ElementKind.METHOD
                            || seen.contains(x) || !model.elements().overrides(x, y, t)) {
                        continue;
                    }
                    seen.add(x);
                    String in = t.getQualifiedName().isEmpty() ? "an anonymous or local class" : t.getQualifiedName()
                            .toString();
                    if (model.declaration(x).isEmpty()) {
                        throw new Incomplete(describe(x) + ", which implements " + describe(y) + " in " + in
                                + ", is declared outside the editable project sources (library, JDK or generated "
                                + "code)");
                    }
                    if (!(x.getEnclosingElement() instanceof TypeElement xo) || xo.getQualifiedName().isEmpty()) {
                        throw new Incomplete("an override of " + describe(y) + " is declared in " + in + ", which no "
                                + "edit can address");
                    }
                    found.add(x);
                    pending.add(x);
                }
            }
        }
        found.sort(java.util.Comparator.comparing(NullabilityPropagation::describe));
        return found;
    }

    /** Every class, interface, enum and record declared in the editable sources (anonymous and local included). */
    private List<TypeElement> sourceTypes() {
        if (sourceTypes == null) {
            List<TypeElement> all = new ArrayList<>();
            for (SourceModel.Unit unit : model.units()) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        if (model.trees().getElement(getCurrentPath()) instanceof TypeElement t) {
                            all.add(t);
                        }
                        return super.visitClass(node, unused);
                    }
                }.scan(unit.tree(), null);
            }
            sourceTypes = all;
        }
        return sourceTypes;
    }

    /** The member text of a method or constructor declaration, as the applier matches it. */
    private String member(MethodTree mt, CompilationUnitTree unit, ExecutableElement m) {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < mt.getParameters().size(); i++) {
            String type = model.text(unit, mt.getParameters().get(i).getType());
            if (m.isVarArgs() && i == mt.getParameters().size() - 1 && !type.endsWith("...")) {
                type = (type.endsWith("[]") ? type.substring(0, type.length() - 2) : type) + "...";
            }
            params.add(type);
        }
        String name = m.getKind() == ElementKind.CONSTRUCTOR ? m.getEnclosingElement().getSimpleName().toString()
                : mt.getName().toString();
        return name + "(" + String.join(", ", params) + ")";
    }

    private static String describe(ExecutableElement m) {
        Element owner = m.getEnclosingElement();
        String type = owner instanceof TypeElement t && !t.getQualifiedName().isEmpty()
                ? t.getQualifiedName().toString() : owner.getSimpleName().toString();
        String name = m.getKind() == ElementKind.CONSTRUCTOR ? owner.getSimpleName().toString()
                : m.getSimpleName().toString();
        return type + "#" + name + "(" + String.join(", ", m.getParameters().stream()
                .map(p -> p.asType().toString()).toList()) + ")";
    }

    private static String squash(String text) {
        return text.replaceAll("\\s+", "");
    }

    /** The innermost assignment or field declaration (or return) whose source range contains {@code offset}. */
    private Optional<TreePath> innermost(CompilationUnitTree cu, long offset, boolean assign) {
        TreePath[] best = {null};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree == null) {
                    return null;
                }
                long start = model.start(cu, tree);
                long end = model.end(cu, tree);
                if (start >= 0 && end >= 0 && (offset < start || offset >= end)) {
                    return null; // the position is not inside this subtree
                }
                return super.scan(tree, unused);
            }

            @Override
            public Void visitAssignment(AssignmentTree node, Void unused) {
                if (assign) {
                    best[0] = getCurrentPath();
                }
                return super.visitAssignment(node, unused);
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                if (assign && node.getInitializer() != null && getCurrentPath().getParentPath().getLeaf()
                        instanceof ClassTree) {
                    best[0] = getCurrentPath();
                }
                return super.visitVariable(node, unused);
            }

            @Override
            public Void visitReturn(ReturnTree node, Void unused) {
                if (!assign) {
                    best[0] = getCurrentPath();
                }
                return super.visitReturn(node, unused);
            }
        }.scan(cu, null);
        return Optional.ofNullable(best[0]);
    }

    private static Optional<TreePath> enclosingMethod(TreePath path) {
        for (TreePath p = path.getParentPath(); p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof LambdaExpressionTree || p.getLeaf() instanceof ClassTree) {
                return Optional.empty();
            }
            if (p.getLeaf() instanceof MethodTree) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private static ExpressionTree strip(ExpressionTree e) {
        ExpressionTree t = e;
        while (t instanceof ParenthesizedTree p) {
            t = p.getExpression();
        }
        return t;
    }

    /**
     * The qualified name of an annotation of {@code family} ("nullable" or "non-null") on the element or its
     * (return) type, if any.
     */
    private static Optional<String> nullness(Element e, String family) {
        List<AnnotationMirror> mirrors = new ArrayList<>(e.getAnnotationMirrors());
        TypeMirror type = e instanceof ExecutableElement m ? m.getReturnType() : e.asType();
        mirrors.addAll(type.getAnnotationMirrors());
        for (AnnotationMirror a : mirrors) {
            Element t = a.getAnnotationType().asElement();
            String simple = t.getSimpleName().toString();
            boolean match = family.equals("nullable") ? NULLABLE.contains(simple)
                    : "non-null".equals(AnnotationKinds.nullness(simple));
            if (match) {
                return Optional.of(t instanceof TypeElement te && !te.getQualifiedName().isEmpty()
                        ? te.getQualifiedName().toString() : simple);
            }
        }
        return Optional.empty();
    }

    /** Whether {@code field} is one of several variables of one declaration ({@code Object a, b;}). */
    private boolean sharesDeclaration(TreePath declaration, VariableTree field) {
        if (!(declaration.getParentPath().getLeaf() instanceof ClassTree owner)) {
            return false;
        }
        CompilationUnitTree cu = declaration.getCompilationUnit();
        long start = model.start(cu, field.getType());
        return owner.getMembers().stream().anyMatch(t -> t != field && t instanceof VariableTree other
                && model.start(cu, other.getType()) == start);
    }
}
