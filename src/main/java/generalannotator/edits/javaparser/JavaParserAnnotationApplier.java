package generalannotator.edits.javaparser;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaParser;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.ast.type.WildcardType;
import generalannotator.edits.AnnotationApplier;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.AnnotationKinds;
import generalannotator.edits.EditAction;
import generalannotator.edits.MemberRef;
import generalannotator.edits.PositionKind;
import generalannotator.edits.TypeText;
import generalannotator.edits.TypeUsePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link AnnotationApplier} that locates declarations with JavaParser 3.28.2 and rewrites sources by
 * inserting or deleting only the annotation text at positions taken from JavaParser's tokens.
 * Everything else in a file, including comments and formatting, stays byte for byte. (JavaParser's
 * {@code LexicalPreservingPrinter} 3.28.2 was tried first: it ignores in-place changes to field
 * types and, after replacing a type, printed {@code @A} glued to primitive types, which is invalid,
 * so it is not used for rewriting.) Each rewritten file is parsed again and compared with the
 * original: apart from the edited annotations and comments, the trees must be identical, and every
 * applied edit must be observable at its position.
 *
 * <p>Declarations are found structurally: the package and nested class path of {@code
 * declaringClass}, then a field by name or a method or constructor by name and parameter types
 * (normalized; a raw type in the proposal also matches the same type with type arguments, and the
 * other way round, which Java's overload rules make unambiguous; everything else must be equal).
 * Positions are reached through {@link TypeUsePath} steps. Ambiguous or unsupported cases are
 * rejected with a reason:
 * <ul>
 *   <li>fields declared together with others ({@code Object a, b;});
 *   <li>C-style array declarators ({@code String a[]}) and varargs parameters;
 *   <li>placements whose legality depends on the annotation's {@code @Target} for annotations not in
 *       {@link AnnotationKinds};
 *   <li>existing annotations whose name cannot be resolved from the imports, and contradictory or
 *       different nullness annotations at the same position.
 * </ul>
 * New annotations are written fully qualified, so no import is added.
 */
public final class JavaParserAnnotationApplier implements AnnotationApplier {

    private final ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            .setStoreTokens(true);

    @Override
    public ApplyResult apply(Path workspaceRoot, List<AnnotationEdit> edits) throws IOException {
        Path root = workspaceRoot.toRealPath();
        Map<AnnotationEdit, Integer> index = new LinkedHashMap<>();
        EditResult[] results = new EditResult[edits.size()];
        for (int i = 0; i < edits.size(); i++) {
            Integer first = index.putIfAbsent(edits.get(i), i);
            if (first != null) {
                results[i] = new EditResult(edits.get(i), Outcome.NO_OP, "duplicate of edit " + first);
            }
        }
        conflicts(edits, results);

        Map<String, List<Integer>> byFile = new LinkedHashMap<>();
        for (int i = 0; i < edits.size(); i++) {
            if (results[i] == null) {
                byFile.computeIfAbsent(edits.get(i).projectPath(), f -> new ArrayList<>()).add(i);
            }
        }
        List<FileChange> changes = new ArrayList<>();
        Map<Path, String> writes = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : byFile.entrySet()) {
            FileWork work = file(root, e.getKey(), e.getValue(), edits, results);
            if (work != null && !work.newText.equals(work.oldText)) {
                changes.add(new FileChange(e.getKey(), work.oldText, work.newText));
                writes.put(work.path, work.newText);
            }
        }
        List<EditResult> list = List.of(results);
        boolean rejected = list.stream().anyMatch(r -> r.outcome() == Outcome.REJECTED);
        boolean applied = list.stream().anyMatch(r -> r.outcome() == Outcome.APPLIED);
        if (rejected || !applied) {
            return new ApplyResult(list, List.of(), false);
        }
        for (Map.Entry<Path, String> w : writes.entrySet()) {
            Path tmp = w.getKey().resolveSibling("." + w.getKey().getFileName() + ".ga-tmp");
            Files.writeString(tmp, w.getValue(), StandardCharsets.UTF_8);
            Files.move(tmp, w.getKey(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return new ApplyResult(list, changes, true);
    }

    /** Rejects ADD/REMOVE of the same annotation, or two different nullness annotations, at one position. */
    private static void conflicts(List<AnnotationEdit> edits, EditResult[] results) {
        Map<String, List<Integer>> byPosition = new LinkedHashMap<>();
        for (int i = 0; i < edits.size(); i++) {
            if (results[i] == null) {
                byPosition.computeIfAbsent(edits.get(i).positionKey(), k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> group : byPosition.values()) {
            for (int a : group) {
                for (int b : group) {
                    if (a >= b) {
                        continue;
                    }
                    AnnotationEdit x = edits.get(a);
                    AnnotationEdit y = edits.get(b);
                    String why = null;
                    if (x.annotation().equals(y.annotation()) && x.action() != y.action()) {
                        why = "conflicting ADD and REMOVE of @" + x.annotation() + " at the same position";
                    } else if (x.action() == EditAction.ADD && y.action() == EditAction.ADD
                            && AnnotationKinds.nullness(AnnotationKinds.simpleName(x.annotation())) != null
                            && AnnotationKinds.nullness(AnnotationKinds.simpleName(y.annotation())) != null) {
                        why = "two nullness annotations (@" + x.annotation() + ", @" + y.annotation()
                                + ") proposed for the same position";
                    }
                    if (why != null) {
                        results[a] = new EditResult(x, Outcome.REJECTED, why + " (edits " + a + " and " + b + ")");
                        results[b] = new EditResult(y, Outcome.REJECTED, why + " (edits " + a + " and " + b + ")");
                    }
                }
            }
        }
    }

    /** One file's planned rewrite. */
    private record FileWork(Path path, String oldText, String newText) {}

    /** One text change: delete {@code length} characters at {@code offset}, then insert {@code text}. */
    private record Splice(int offset, int length, String text, int order) {}

    private FileWork file(Path root, String projectPath, List<Integer> indices, List<AnnotationEdit> edits,
            EditResult[] results) throws IOException {
        Optional<String> unsafe = Optional.empty();
        Path path = root.resolve(projectPath).normalize();
        if (!path.startsWith(root)) {
            unsafe = Optional.of(projectPath + " leaves the workspace");
        } else if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            unsafe = Optional.of(projectPath + " does not exist in the snapshot");
        } else if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            unsafe = Optional.of(projectPath + " is not a regular file (symbolic links are not followed)");
        } else if (!path.toRealPath().equals(path)) {
            unsafe = Optional.of(projectPath + " is reached through a symbolic link");
        }
        if (unsafe.isPresent()) {
            rejectAll(indices, edits, results, unsafe.get());
            return null;
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Optional<CompilationUnit> cu = parse(text);
        if (cu.isEmpty()) {
            rejectAll(indices, edits, results, projectPath + " does not parse as Java");
            return null;
        }
        LineIndex lines = new LineIndex(text);
        List<Splice> splices = new ArrayList<>();
        List<Integer> applied = new ArrayList<>();
        for (int i : indices) {
            AnnotationEdit edit = edits.get(i);
            try {
                Plan plan = plan(cu.get(), edit, lines);
                results[i] = new EditResult(edit, plan.outcome, plan.detail);
                splices.addAll(plan.splices.stream().map(s -> new Splice(s.offset, s.length, s.text, i)).toList());
                if (plan.outcome == Outcome.APPLIED) {
                    applied.add(i);
                }
            } catch (Rejection r) {
                results[i] = new EditResult(edit, Outcome.REJECTED, r.getMessage());
            }
        }
        if (applied.isEmpty()) {
            return new FileWork(path, text, text);
        }
        String rewritten;
        try {
            rewritten = splice(text, splices);
        } catch (Rejection r) {
            rejectAll(applied, edits, results, r.getMessage());
            return null;
        }
        Optional<String> problem = verify(text, rewritten, applied.stream().map(edits::get).toList());
        if (problem.isPresent()) {
            rejectAll(applied, edits, results, "verification of the rewritten " + projectPath + " failed: "
                    + problem.get());
            return null;
        }
        return new FileWork(path, text, rewritten);
    }

    private static void rejectAll(List<Integer> indices, List<AnnotationEdit> edits, EditResult[] results,
            String why) {
        for (int i : indices) {
            results[i] = new EditResult(edits.get(i), Outcome.REJECTED, why);
        }
    }

    private Optional<CompilationUnit> parse(String text) {
        ParseResult<CompilationUnit> r = new JavaParser(configuration).parse(text);
        return r.isSuccessful() ? r.getResult() : Optional.empty();
    }

    // ------------------------------------------------------------------ locating

    /** An edit that cannot be applied reliably. */
    static final class Rejection extends Exception {
        private static final long serialVersionUID = 1L;

        Rejection(String message) {
            super(message);
        }
    }

    /**
     * A located position.
     *
     * @param declaration the field, method, constructor or parameter that owns the position
     * @param declarationAnnotations annotations written in its modifier position
     * @param declaredType the declared type the path starts from
     * @param target the annotated type
     * @param arrayRoot the outermost array type containing {@code target} at array depth {@code depth},
     *     when {@code target} is an array level
     * @param note how the declaration was matched, if not exactly
     */
    record Located(Node declaration, NodeList<AnnotationExpr> declarationAnnotations, Type declaredType,
            Type target, ArrayType arrayRoot, int depth, String note) {}

    static Located locate(CompilationUnit cu, AnnotationEdit edit) throws Rejection {
        TypeDeclaration<?> type = declaringType(cu, edit.declaringClass());
        TypeUsePath path = edit.position().path();
        Node declaration;
        NodeList<AnnotationExpr> annotations;
        Type declared;
        String note = null;
        if (edit.member() instanceof MemberRef.Field f) {
            FieldDeclaration field = type.getFields().stream()
                    .filter(fd -> fd.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(f.name())))
                    .findFirst().orElseThrow(() -> new Rejection("class " + edit.declaringClass() + " declares no field "
                            + f.name() + " (fields: " + type.getFields().stream().flatMap(fd -> fd.getVariables()
                                    .stream()).map(v -> v.getNameAsString()).toList() + ")"));
            if (field.getVariables().size() > 1) {
                throw new Rejection("field " + f.name() + " is declared together with "
                        + field.getVariables().stream().map(v -> v.getNameAsString())
                                .filter(n -> !n.equals(f.name())).toList()
                        + "; an annotation there would apply to all of them (splitting declarations is not supported)");
            }
            declaration = field;
            annotations = field.getAnnotations();
            declared = field.getVariable(0).getType();
        } else {
            MemberRef.Executable m = (MemberRef.Executable) edit.member();
            Match match = executable(type, m);
            note = match.note;
            CallableDeclaration<?> callable = match.callable;
            if (path.root() == TypeUsePath.Root.RETURN) {
                if (!(callable instanceof MethodDeclaration method)) {
                    throw new Rejection(m + " is a constructor; it has no return type");
                }
                if (method.getType() instanceof VoidType) {
                    throw new Rejection(m + " returns void");
                }
                declaration = method;
                annotations = method.getAnnotations();
                declared = method.getType();
            } else {
                int i = path.parameterIndex().orElseThrow();
                if (i >= callable.getParameters().size()) {
                    throw new Rejection(m + " has " + callable.getParameters().size() + " parameter(s); there is no "
                            + "parameter index " + i);
                }
                Parameter p = callable.getParameter(i);
                if (p.isVarArgs()) {
                    throw new Rejection("parameter " + i + " of " + m + " is a varargs parameter; annotating varargs "
                            + "parameters is not supported");
                }
                declaration = p;
                annotations = p.getAnnotations();
                declared = p.getType();
            }
        }
        for (ArrayType a : declared.findAll(ArrayType.class)) {
            if (a.getOrigin() == ArrayType.Origin.NAME) {
                throw new Rejection("the declaration uses C-style array brackets after the name; not supported");
            }
        }
        Type target = declared;
        ArrayType arrayRoot = declared instanceof ArrayType a ? a : null;
        int depth = 0;
        for (TypeUsePath.Step step : path.steps()) {
            switch (step) {
                case TypeUsePath.TypeArgument t -> {
                    if (!(target instanceof ClassOrInterfaceType c) || c.getTypeArguments().isEmpty()
                            || c.getTypeArguments().get().size() <= t.index()) {
                        throw new Rejection("type " + target + " has no type argument " + t.index() + " (path "
                                + path + ")");
                    }
                    target = c.getTypeArguments().get().get(t.index());
                    arrayRoot = target instanceof ArrayType a ? a : null;
                    depth = 0;
                }
                case TypeUsePath.ArrayComponent a -> {
                    if (!(target instanceof ArrayType at)) {
                        throw new Rejection("type " + target + " is not an array (path " + path + ")");
                    }
                    target = at.getComponentType();
                    depth++;
                    if (!(target instanceof ArrayType)) {
                        arrayRoot = null;
                    }
                }
                case TypeUsePath.WildcardBound w -> {
                    if (!(target instanceof WildcardType wt)
                            || (wt.getExtendedType().isEmpty() && wt.getSuperType().isEmpty())) {
                        throw new Rejection("type " + target + " is not a bounded wildcard (path " + path + ")");
                    }
                    target = wt.getExtendedType().isPresent() ? wt.getExtendedType().get() : wt.getSuperType().get();
                    arrayRoot = target instanceof ArrayType at ? at : null;
                    depth = 0;
                }
            }
        }
        if (!(target instanceof ClassOrInterfaceType || target instanceof ArrayType || target instanceof PrimitiveType
                || target instanceof WildcardType)) {
            throw new Rejection("annotating a " + target.getClass().getSimpleName() + " (" + target + ") is not "
                    + "supported");
        }
        return new Located(declaration, annotations, declared, target, arrayRoot, depth, note);
    }

    private static TypeDeclaration<?> declaringType(CompilationUnit cu, String declaringClass) throws Rejection {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String rest;
        if (pkg.isEmpty()) {
            rest = declaringClass;
        } else if (declaringClass.startsWith(pkg + ".")) {
            rest = declaringClass.substring(pkg.length() + 1);
        } else {
            throw new Rejection("class " + declaringClass + " is not in package " + pkg + " of this file");
        }
        String[] names = rest.split("\\.");
        List<? extends TypeDeclaration<?>> candidates = cu.getTypes();
        TypeDeclaration<?> current = null;
        for (String name : names) {
            List<TypeDeclaration<?>> found = new ArrayList<>();
            for (TypeDeclaration<?> t : candidates) {
                if (t.getNameAsString().equals(name)) {
                    found.add(t);
                }
            }
            if (found.size() != 1) {
                throw new Rejection("class " + declaringClass + " not found in this file (no unique type '" + name
                        + "'; types here: " + candidates.stream().map(t -> t.getNameAsString()).toList() + ")");
            }
            current = found.get(0);
            List<TypeDeclaration<?>> members = new ArrayList<>();
            for (BodyDeclaration<?> m : current.getMembers()) {
                if (m instanceof TypeDeclaration<?> td) {
                    members.add(td);
                }
            }
            candidates = members;
        }
        return Objects.requireNonNull(current);
    }

    private record Match(CallableDeclaration<?> callable, String note) {}

    private static Match executable(TypeDeclaration<?> type, MemberRef.Executable m) throws Rejection {
        List<CallableDeclaration<?>> named = new ArrayList<>(type.getMethodsByName(m.name()));
        if (m.name().equals(type.getNameAsString())) {
            for (BodyDeclaration<?> b : type.getMembers()) {
                if (b instanceof ConstructorDeclaration c) {
                    named.add(c);
                }
            }
        }
        if (named.isEmpty()) {
            throw new Rejection("class " + type.getFullyQualifiedName().orElse(type.getNameAsString())
                    + " declares no method or constructor named " + m.name());
        }
        List<CallableDeclaration<?>> exact = new ArrayList<>();
        List<CallableDeclaration<?>> erased = new ArrayList<>();
        for (CallableDeclaration<?> c : named) {
            List<String> types = parameterTypes(c);
            if (types.equals(m.parameterTypes())) {
                exact.add(c);
            } else if (rawEquivalent(types, m.parameterTypes())) {
                erased.add(c);
            }
        }
        if (exact.size() == 1) {
            return new Match(exact.get(0), null);
        }
        if (exact.isEmpty() && erased.size() == 1) {
            return new Match(erased.get(0), "matched " + signature(erased.get(0)) + " by ignoring type arguments "
                    + "that one side omits");
        }
        throw new Rejection((exact.size() + erased.size() > 1 ? "ambiguous: several" : "no")
                + " declarations match " + m + "; candidates: " + named.stream()
                        .map(JavaParserAnnotationApplier::signature).toList());
    }

    /** Equal, or equal after erasing type arguments where exactly one side wrote none. */
    private static boolean rawEquivalent(List<String> source, List<String> proposed) {
        if (source.size() != proposed.size()) {
            return false;
        }
        for (int i = 0; i < source.size(); i++) {
            String s = source.get(i);
            String p = proposed.get(i);
            if (s.equals(p)) {
                continue;
            }
            boolean oneRaw = TypeText.hasTypeArguments(s) != TypeText.hasTypeArguments(p);
            if (!oneRaw || !TypeText.erase(s).equals(TypeText.erase(p))) {
                return false;
            }
        }
        return true;
    }

    static List<String> parameterTypes(CallableDeclaration<?> c) {
        List<String> types = new ArrayList<>();
        for (Parameter p : c.getParameters()) {
            types.add(TypeText.normalize(withoutComments(p.getType()) + (p.isVarArgs() ? "..." : "")));
        }
        return types;
    }

    private static String withoutComments(Type t) {
        Type copy = t.clone();
        copy.getAllContainedComments().forEach(Comment::remove);
        copy.getComment().ifPresent(Comment::remove);
        return copy.toString();
    }

    private static String signature(CallableDeclaration<?> c) {
        return c.getNameAsString() + "(" + String.join(", ", parameterTypes(c)) + ")";
    }

    // ------------------------------------------------------------------ planning

    private record Plan(Outcome outcome, String detail, List<Splice> splices) {}

    private Plan plan(CompilationUnit cu, AnnotationEdit edit, LineIndex lines) throws Rejection {
        Located at = locate(cu, edit);
        AnnotationKinds.Kind kind = AnnotationKinds.kind(edit.annotation());
        boolean topLevel = edit.position().kind() != PositionKind.TYPE_USE;
        boolean simpleDeclared = !(at.declaredType instanceof ArrayType)
                && !(at.declaredType instanceof ClassOrInterfaceType c && c.getScope().isPresent());
        if (kind == AnnotationKinds.Kind.DECLARATION && !topLevel) {
            throw new Rejection("@" + edit.annotation() + " is a declaration annotation; it cannot annotate the "
                    + "type use " + edit.position());
        }
        if (kind == AnnotationKinds.Kind.UNKNOWN && topLevel && !simpleDeclared) {
            throw new Rejection("the declared type " + at.declaredType + " is qualified or an array, so where @"
                    + edit.annotation() + " goes depends on its @Target, which is unknown to this tool "
                    + "(known annotations: generalannotator.edits.AnnotationKinds)");
        }
        boolean declarationPlacement = kind == AnnotationKinds.Kind.DECLARATION;
        List<AnnotationExpr> present = new ArrayList<>();
        if (declarationPlacement) {
            present.addAll(at.declarationAnnotations);
        } else {
            present.addAll(at.target.getAnnotations());
            if (topLevel && simpleDeclared) {
                present.addAll(at.declarationAnnotations);
            }
        }
        Names names = new Names(cu);
        String wanted = edit.annotation();
        String wantedSimple = AnnotationKinds.simpleName(wanted);
        String wantedFamily = AnnotationKinds.nullness(wantedSimple);
        List<AnnotationExpr> matches = new ArrayList<>();
        for (AnnotationExpr a : present) {
            String simple = AnnotationKinds.simpleName(a.getNameAsString());
            Optional<String> fqn = names.resolve(a);
            if (fqn.isPresent() && fqn.get().equals(wanted)) {
                matches.add(a);
                continue;
            }
            if (simple.equals(wantedSimple) && fqn.isEmpty()) {
                throw new Rejection("the existing @" + a.getNameAsString() + " at " + edit.position() + " cannot be "
                        + "resolved to a fully qualified name (" + names.why(a) + "); not guessing whether it is @"
                        + wanted);
            }
            String family = AnnotationKinds.nullness(simple);
            if (edit.action() == EditAction.ADD && wantedFamily != null && family != null) {
                throw new Rejection("the position already has the nullness annotation @" + a.getNameAsString()
                        + (fqn.map(f -> " (" + f + ")").orElse("")) + "; adding @" + wanted + " would "
                        + (family.equals(wantedFamily) ? "duplicate" : "contradict") + " it, so it is not resolved by "
                        + "guessing");
            }
        }
        String where = describe(edit, at);
        if (edit.action() == EditAction.ADD) {
            if (!matches.isEmpty()) {
                return new Plan(Outcome.NO_OP, "@" + wanted + " is already present at " + where, List.of());
            }
            Splice s = declarationPlacement
                    ? insert(lines, at.declaration.getBegin(), "@" + wanted + " ")
                    : typeInsertion(lines, at, wanted);
            return new Plan(Outcome.APPLIED, "added @" + wanted + " at " + where, List.of(s));
        }
        if (matches.isEmpty()) {
            return new Plan(Outcome.NO_OP, "@" + wanted + " is not present at " + where, List.of());
        }
        List<Splice> removals = new ArrayList<>();
        for (AnnotationExpr a : matches) {
            removals.add(removal(lines, a));
        }
        return new Plan(Outcome.APPLIED, "removed @" + wanted + " at " + where, removals);
    }

    private static String describe(AnnotationEdit edit, Located at) {
        String what = edit.position() + " of " + edit.declaringClass() + "#" + edit.member() + " (type " + at.target
                + ")";
        return at.note == null ? what : what + "; " + at.note;
    }

    private static Splice typeInsertion(LineIndex lines, Located at, String annotation)
            throws Rejection {
        String text = "@" + annotation + " ";
        if (at.target instanceof ClassOrInterfaceType c) {
            return insert(lines, c.getName().getBegin(), text);
        }
        if (at.target instanceof PrimitiveType || at.target instanceof WildcardType) {
            return insert(lines, at.target.getBegin(), text);
        }
        ArrayType root = at.arrayRoot;
        if (root == null) {
            throw new Rejection("internal: no array root for " + at.target);
        }
        // The outermost array level is the leftmost [] after the element type.
        JavaToken token = root.getElementType().getTokenRange().orElseThrow().getEnd();
        int seen = 0;
        int parens = 0;
        for (Optional<JavaToken> t = token.getNextToken(); t.isPresent(); t = t.get().getNextToken()) {
            int k = t.get().getKind();
            if (k == GeneratedJavaParserConstants.LPAREN) {
                parens++;
            } else if (k == GeneratedJavaParserConstants.RPAREN) {
                parens--;
            } else if (k == GeneratedJavaParserConstants.LBRACKET && parens == 0) {
                if (seen == at.depth) {
                    Position p = t.get().getRange().orElseThrow().begin;
                    int offset = lines.offset(p);
                    boolean space = offset > 0 && Character.isWhitespace(lines.text().charAt(offset - 1));
                    return new Splice(offset, 0, (space ? "" : " ") + text, 0);
                }
                seen++;
            }
        }
        throw new Rejection("cannot find the brackets of array level " + at.depth + " of " + root);
    }

    private static Splice insert(LineIndex lines, Optional<Position> at, String text) throws Rejection {
        return new Splice(lines.offset(at.orElseThrow(() -> new Rejection("no source position"))), 0, text, 0);
    }

    /** Deletes an annotation and the blanks after it; a line left blank is deleted entirely. */
    private static Splice removal(LineIndex lines, AnnotationExpr a) throws Rejection {
        String text = lines.text();
        int start = lines.offset(a.getBegin().orElseThrow(() -> new Rejection("no source position")));
        int end = lines.offset(a.getEnd().orElseThrow(() -> new Rejection("no source position"))) + 1;
        int after = end;
        while (after < text.length() && (text.charAt(after) == ' ' || text.charAt(after) == '\t')) {
            after++;
        }
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        boolean onlyBlanksBefore = text.substring(lineStart, start).isBlank();
        boolean lineEnds = after >= text.length() || text.charAt(after) == '\n' || text.charAt(after) == '\r';
        if (onlyBlanksBefore && lineEnds) {
            int next = after;
            if (next < text.length() && text.charAt(next) == '\r') {
                next++;
            }
            if (next < text.length() && text.charAt(next) == '\n') {
                next++;
            }
            return new Splice(lineStart, next - lineStart, "", 0);
        }
        if (after < text.length() && text.charAt(after) == '[' && start > 0 && text.charAt(start - 1) == ' ') {
            start--; // "String @A []" becomes "String[]"
        }
        return new Splice(start, after - start, "", 0);
    }

    private static String splice(String text, List<Splice> splices) throws Rejection {
        List<Splice> sorted = new ArrayList<>(splices);
        sorted.sort(Comparator.comparingInt(Splice::offset).thenComparingInt(Splice::order));
        for (int i = 1; i < sorted.size(); i++) {
            Splice a = sorted.get(i - 1);
            Splice b = sorted.get(i);
            if (a.offset + a.length > b.offset || (a.length > 0 && a.offset == b.offset)) {
                throw new Rejection("edits " + a.order + " and " + b.order + " change overlapping source text");
            }
        }
        StringBuilder b = new StringBuilder(text);
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Splice s = sorted.get(i);
            b.replace(s.offset, s.offset + s.length, s.text);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ verification

    private Optional<String> verify(String before, String after, List<AnnotationEdit> applied) {
        Optional<CompilationUnit> oldCu = parse(before);
        Optional<CompilationUnit> newCu = parse(after);
        if (newCu.isEmpty() || oldCu.isEmpty()) {
            return Optional.of("the rewritten source does not parse");
        }
        List<String> oldComments = comments(oldCu.get());
        List<String> newComments = comments(newCu.get());
        if (!oldComments.equals(newComments)) {
            return Optional.of("comments changed");
        }
        for (AnnotationEdit e : applied) {
            try {
                Located at = locate(newCu.get(), e);
                Names names = new Names(newCu.get());
                List<AnnotationExpr> present = new ArrayList<>(at.target.getAnnotations());
                present.addAll(at.declarationAnnotations);
                boolean has = present.stream().anyMatch(a -> names.resolve(a).map(e.annotation()::equals)
                        .orElse(false));
                if (has != (e.action() == EditAction.ADD)) {
                    return Optional.of("@" + e.annotation() + " is " + (has ? "still" : "not") + " present at "
                            + e.position() + " of " + e.member());
                }
            } catch (Rejection r) {
                return Optional.of("the edited declaration is no longer found: " + r.getMessage());
            }
        }
        Set<String> edited = applied.stream().map(e -> AnnotationKinds.simpleName(e.annotation()))
                .collect(Collectors.toSet());
        if (!skeleton(oldCu.get(), edited).equals(skeleton(newCu.get(), edited))) {
            return Optional.of("the change is not limited to the edited annotations");
        }
        return Optional.empty();
    }

    private static List<String> comments(CompilationUnit cu) {
        List<String> list = new ArrayList<>();
        for (Comment c : cu.getAllComments()) {
            list.add(c.getContent());
        }
        return list;
    }

    /** The tree printed without comments and without annotations named like an edited one. */
    private static String skeleton(CompilationUnit cu, Set<String> editedSimpleNames) {
        CompilationUnit copy = cu.clone();
        new ArrayList<>(copy.getAllContainedComments()).forEach(Comment::remove);
        copy.getComment().ifPresent(Comment::remove);
        for (AnnotationExpr a : copy.findAll(AnnotationExpr.class)) {
            if (editedSimpleNames.contains(AnnotationKinds.simpleName(a.getNameAsString()))) {
                a.remove();
            }
        }
        return copy.toString();
    }

    // ------------------------------------------------------------------ helpers

    /** Resolves annotation names written in a compilation unit from its imports, without guessing. */
    static final class Names {
        private final Map<String, String> singleImports = new LinkedHashMap<>();
        private final Set<String> onDemand = new HashSet<>();
        private final String pkg;

        Names(CompilationUnit cu) {
            pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (ImportDeclaration i : cu.getImports()) {
                if (i.isStatic()) {
                    continue;
                }
                if (i.isAsterisk()) {
                    onDemand.add(i.getNameAsString());
                } else {
                    singleImports.put(AnnotationKinds.simpleName(i.getNameAsString()), i.getNameAsString());
                }
            }
        }

        /** The qualified name, if certain: written qualified, or imported by a single-type import. */
        Optional<String> resolve(AnnotationExpr a) {
            String name = a.getNameAsString();
            if (name.contains(".")) {
                return Optional.of(name);
            }
            return Optional.ofNullable(singleImports.get(name));
        }

        String why(AnnotationExpr a) {
            String name = a.getNameAsString();
            List<String> options = new ArrayList<>();
            onDemand.forEach(p -> options.add(p + "." + name));
            options.add((pkg.isEmpty() ? "" : pkg + ".") + name + " (same package or a nested type)");
            return "no single-type import; it could be " + String.join(", ", options);
        }
    }

    /** Line/column to offset conversion matching JavaParser's positions (tab width 1). */
    static final class LineIndex {
        private final String text;
        private final List<Integer> starts = new ArrayList<>();

        LineIndex(String text) {
            this.text = text;
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                    starts.add(i + 1);
                }
            }
        }

        String text() {
            return text;
        }

        int offset(Position p) throws Rejection {
            if (p.line < 1 || p.line > starts.size()) {
                throw new Rejection("source position " + p + " is outside the file");
            }
            int offset = starts.get(p.line - 1) + p.column - 1;
            if (offset < 0 || offset > text.length()) {
                throw new Rejection("source position " + p + " is outside the file");
            }
            return offset;
        }
    }
}
