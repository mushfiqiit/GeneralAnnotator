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
 *       {@code return} of such a value from an enclosing method of the editable sources without a nullness
 *       annotation on its return type (and not overriding another method);
 *   <li>{@link Rule#ARGUMENT}: "passing @Nullable parameter 'e' where @NonNull is required" on an argument
 *       of a method or constructor call. The invoked executable is the one javac resolved (so overloads are
 *       told apart by attribution, never by name), the argument index maps to its formal parameter, and the
 *       argument is a field read, a method call or a local variable (or parameter); the diagnostic itself is
 *       the evidence that the value may be null. The receiving parameter is annotated {@code @Nullable}; an
 *       explicit non-null annotation on it is removed in the same package (a recorded contract change).
 *       Varargs positions, parameters of abstract methods and of methods overridden in the editable sources,
 *       anonymous class creations, and executables declared outside the editable sources are skipped.
 * </ul>
 * The {@code @Nullable} annotation added is the one on the value's origin (the field, method or local); for
 * an argument without an annotated origin, the project's single nullable annotation type, if it uses exactly
 * one. Every other warning is skipped with its reason: other categories (for example dereferences of
 * {@code @Nullable} values), unsupported values ({@code null}, conditionals), unresolved or ambiguous positions
 * and symbols, explicit non-null contracts on fields and returns, overriding methods, and destinations outside
 * the editable sources. Nothing is applied here; every candidate comes from the unchanged baseline, and
 * warnings that its edits introduce are left to a later pass.
 */
public final class NullabilityPropagation {

    /** The rule set's version, recorded with every result. */
    public static final String RULES = "nullable-flow-2";
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
        /** The destination carries an explicit nullness annotation or inherits a return contract. */
        EXPLICIT_CONTRACT,
        /** The destination is not declared in the editable project sources (or cannot be edited). */
        NOT_EDITABLE,
        /**
         * The receiving declaration is resolved but this pass does not change it: a varargs position, a parameter
         * of an abstract method or of a method overridden in the project, or an anonymous class creation.
         */
        UNSUPPORTED_TARGET
    }

    /**
     * A matched warning and its proposed edits.
     *
     * @param edit the {@code @Nullable} annotation added
     * @param removal the explicit non-null annotation removed at the same position (a contract change), if any
     */
    public record Match(String warningId, Rule rule, AnnotationEdit edit, Optional<AnnotationEdit> removal,
            String evidence) {
        public Match {
            java.util.Objects.requireNonNull(removal, "removal");
        }

        public Match(String warningId, Rule rule, AnnotationEdit edit, String evidence) {
            this(warningId, rule, edit, Optional.empty(), evidence);
        }

        /** Every edit of the match, applied together as one package: the removal (if any), then the addition. */
        public List<AnnotationEdit> edits() {
            return removal.map(r -> List.of(r, edit)).orElse(List.of(edit));
        }
    }

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
    private List<ExecutableElement> declaredMethods;
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
        if (destination instanceof ExecutableElement m) {
            Optional<String> overridden = overridden(m);
            if (overridden.isPresent()) {
                return new Skip(id, SkipCategory.EXPLICIT_CONTRACT, destinationText + " overrides " + overridden.get()
                        + "; its inherited return contract is kept");
            }
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
            case RETURN -> "'return " + valueText + "' returns " + origin.description()
                    + " from the unannotated method " + type0.getQualifiedName() + "#" + member;
            case ARGUMENT -> throw new IllegalStateException();
        };
        return new Match(id, rule, edit, evidence);
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
        Optional<TreePath> declaration = model.declaration(m);
        if (declaration.isEmpty() || !(declaration.get().getLeaf() instanceof MethodTree mt)
                || mt.getParameters().size() != params.size()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, target + " is declared outside the editable project "
                    + "sources (library, JDK or generated code)");
        }
        if (!(m.getEnclosingElement() instanceof TypeElement owner) || owner.getQualifiedName().isEmpty()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, target + " is declared in an anonymous or local class");
        }
        if (m.getModifiers().contains(Modifier.ABSTRACT)) {
            return new Skip(id, SkipCategory.UNSUPPORTED_TARGET, target + " is abstract; its implementations, possibly "
                    + "outside the editable sources, would all have to accept null too, which this pass does not "
                    + "change");
        }
        List<String> overriders = overriders(m);
        if (!overriders.isEmpty()) {
            return new Skip(id, SkipCategory.UNSUPPORTED_TARGET, target + " is overridden by " + String.join(", ",
                    overriders) + "; a @Nullable parameter would require every overriding method to accept null "
                    + "too, which this pass does not change");
        }
        CompilationUnitTree declUnit = declaration.get().getCompilationUnit();
        SourceModel.Unit du = model.unit(declUnit);
        Optional<String> root = du == null ? Optional.empty() : sourceRoots.stream()
                .filter(r -> r.equals(".") || du.relativePath().startsWith(r + "/"))
                .max((x, y) -> Integer.compare(x.length(), y.length()));
        if (root.isEmpty()) {
            return new Skip(id, SkipCategory.NOT_EDITABLE, target + " is not in a configured source root");
        }
        String file = root.get().equals(".") ? du.relativePath() : du.relativePath().substring(root.get().length() + 1);
        String member = member(mt, declUnit, m);
        AnnotationEdit edit;
        Optional<AnnotationEdit> removal;
        try {
            MemberRef ref = MemberRef.parse(member);
            String cls = owner.getQualifiedName().toString();
            edit = new AnnotationEdit(root.get(), file, cls, ref, AnnotationPosition.parameter(index), EditAction.ADD,
                    nullable.get());
            removal = nonNull.map(n -> new AnnotationEdit(root.get(), file, cls, ref,
                    AnnotationPosition.parameter(index), EditAction.REMOVE, n));
        } catch (IllegalArgumentException e) {
            return new Skip(id, SkipCategory.UNRESOLVED, "no edit can describe " + formalText + ": " + e.getMessage());
        }
        String evidence = "'" + callText + "' passes " + origin.description() + ", which NullAway reports as "
                + "@Nullable, as argument " + (index + 1) + " to parameter '" + formal.getSimpleName() + "' of "
                + owner.getQualifiedName() + "#" + member
                + (origin.annotation().filter(a -> a.contains(".")).isEmpty() ? " (annotation @" + nullable.get()
                        + ": the project's nullable annotation type)" : "")
                + nonNull.map(n -> "; contract change: its explicit @" + n + " is replaced by @" + nullable.get())
                        .orElse("");
        return new Match(id, Rule.ARGUMENT, edit, removal, evidence);
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

    /** The methods of the editable sources (anonymous classes included) that override {@code m}. */
    private List<String> overriders(ExecutableElement m) {
        if (m.getKind() != ElementKind.METHOD || m.getModifiers().contains(Modifier.STATIC)
                || m.getModifiers().contains(Modifier.PRIVATE)) {
            return List.of();
        }
        if (declaredMethods == null) {
            List<ExecutableElement> all = new ArrayList<>();
            for (SourceModel.Unit unit : model.units()) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree node, Void unused) {
                        if (model.trees().getElement(getCurrentPath()) instanceof ExecutableElement e
                                && e.getKind() == ElementKind.METHOD) {
                            all.add(e);
                        }
                        return super.visitMethod(node, unused);
                    }
                }.scan(unit.tree(), null);
            }
            declaredMethods = all;
        }
        List<String> found = new ArrayList<>();
        for (ExecutableElement other : declaredMethods) {
            if (other != m && other.getEnclosingElement() instanceof TypeElement t
                    && model.elements().overrides(other, m, t)) {
                found.add(describe(other));
            }
        }
        return found;
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

    /** A method {@code m} overrides, as {@code Type#name}, if any. */
    private Optional<String> overridden(ExecutableElement m) {
        TypeElement owner = (TypeElement) m.getEnclosingElement();
        List<TypeMirror> pending = new ArrayList<>(model.types().directSupertypes(owner.asType()));
        while (!pending.isEmpty()) {
            TypeMirror t = pending.remove(0);
            Element e = model.types().asElement(t);
            if (!(e instanceof TypeElement te)) {
                continue;
            }
            for (Element member : te.getEnclosedElements()) {
                if (member instanceof ExecutableElement other && model.elements().overrides(m, other, owner)) {
                    return Optional.of(te.getQualifiedName() + "#" + other.getSimpleName() + "()");
                }
            }
            pending.addAll(model.types().directSupertypes(t));
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
