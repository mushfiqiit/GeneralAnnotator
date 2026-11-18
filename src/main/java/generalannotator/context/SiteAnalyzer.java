package generalannotator.context;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import generalannotator.context.Evidence.Condition;
import generalannotator.context.Evidence.LocalDefinition;
import generalannotator.context.Evidence.Snippet;
import generalannotator.context.Evidence.SourceRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Describes evidence sites in the attributed source model: enclosing conditions, syntactic null
 * guards, how a value is consumed, what kind of expression produces a value, snippets, local
 * definitions, member labels and declared annotations. All symbol questions are answered with
 * javac's symbols; nothing is matched by name.
 */
final class SiteAnalyzer {

    static final int MAX_EXPRESSION_CHARS = 160;
    static final int MAX_LINE_CHARS = 200;
    private static final int MAX_EARLIER_ASSIGNMENTS = 2;

    /** A variable as referenced at a site: its symbol and, for field accesses, the receiver text ("" for this). */
    record VarRef(Element element, String receiver) {}

    private final SourceModel model;
    private final ContextLimits limits;

    SiteAnalyzer(SourceModel model, ContextLimits limits) {
        this.model = model;
        this.limits = limits;
    }

    // ---------------------------------------------------------------- symbols and labels

    Element element(TreePath path) {
        return model.trees().getElement(path);
    }

    static boolean isErroneous(Element e) {
        return e == null || (e.asType() != null && e.asType().getKind() == TypeKind.ERROR
                && !(e instanceof VariableElement) && !(e instanceof ExecutableElement));
    }

    String typeName(TypeElement t) {
        String qualified = t.getQualifiedName().toString();
        return qualified.isEmpty() ? model.elements().getBinaryName(t).toString() : qualified;
    }

    /** Human-readable label: {@code p.C#m(String, int)}, {@code p.C#f}, or a local's name. */
    String label(Element e) {
        if (e == null) {
            return "<unresolved>";
        }
        if (e instanceof ExecutableElement ee && ee.getEnclosingElement() instanceof TypeElement owner) {
            String name = ee.getKind() == ElementKind.CONSTRUCTOR ? owner.getSimpleName().toString()
                    : ee.getSimpleName().toString();
            if (name.isEmpty()) {
                name = "<anonymous>";
            }
            List<String> params = new ArrayList<>();
            for (VariableElement p : ee.getParameters()) {
                params.add(simpleTypes(p.asType()));
            }
            if (ee.isVarArgs() && !params.isEmpty()) {
                String last = params.removeLast();
                params.add(last.endsWith("[]") ? last.substring(0, last.length() - 2) + "..." : last);
            }
            return typeName(owner) + "#" + name + "(" + String.join(", ", params) + ")";
        }
        if (e instanceof VariableElement v && v.getEnclosingElement() instanceof TypeElement owner) {
            return typeName(owner) + "#" + v.getSimpleName();
        }
        if (e instanceof TypeElement t) {
            return typeName(t);
        }
        return e.getSimpleName().toString();
    }

    /** A type's text with package qualifiers and type annotations removed, for display only. */
    static String simpleTypes(TypeMirror type) {
        String s = type.toString().replaceAll("@[\\w.$]+(\\([^)]*\\))?\\s*", "");
        return s.replaceAll("\\b(?:[a-z_][\\w$]*\\.)+(?=[A-Za-z_$])", "");
    }

    /** Label of the member (method, constructor, field, initializer) that contains {@code path}. */
    String enclosingMember(TreePath path) {
        for (TreePath p = path; p != null; p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            TreePath parent = p.getParentPath();
            if (leaf instanceof MethodTree) {
                return label(element(p));
            }
            if (parent != null && parent.getLeaf() instanceof ClassTree) {
                Element owner = element(parent);
                String ownerName = owner instanceof TypeElement t ? typeName(t) : "<class>";
                if (leaf instanceof VariableTree) {
                    return label(element(p));
                }
                if (leaf instanceof BlockTree b) {
                    return ownerName + (b.isStatic() ? "#<static initializer>" : "#<initializer>");
                }
            }
            if (leaf instanceof ClassTree) {
                Element owner = element(p);
                return owner instanceof TypeElement t ? typeName(t) : "<class>";
            }
        }
        return "<file>";
    }

    /** Lambda, anonymous/local class, constructor and initializer context of a site. */
    Set<Observation> contextObservations(TreePath path) {
        Set<Observation> obs = new LinkedHashSet<>();
        boolean memberSeen = false;
        Tree below = null;
        for (TreePath p = path; p != null; below = p.getLeaf(), p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            TreePath parent = p.getParentPath();
            if (leaf instanceof LambdaExpressionTree) {
                obs.add(Observation.IN_LAMBDA);
            }
            if (leaf instanceof ClassTree && element(p) instanceof TypeElement t
                    && (t.getNestingKind() == NestingKind.ANONYMOUS || t.getNestingKind() == NestingKind.LOCAL)) {
                obs.add(Observation.IN_ANONYMOUS_OR_LOCAL_CLASS);
            }
            if (!memberSeen && parent != null && parent.getLeaf() instanceof ClassTree) {
                if (leaf instanceof MethodTree m) {
                    memberSeen = true;
                    if (m.getName().contentEquals("<init>")) {
                        obs.add(Observation.IN_CONSTRUCTOR);
                    }
                } else if (leaf instanceof BlockTree) {
                    memberSeen = true;
                    obs.add(Observation.IN_INITIALIZER);
                } else if (leaf instanceof VariableTree v) {
                    memberSeen = true;
                    if (below != null && below == v.getInitializer()) {
                        obs.add(Observation.IN_INITIALIZER);
                    }
                }
            }
        }
        return obs;
    }

    /** Declared annotations of a variable or method: declaration annotations and top-level type annotations. */
    List<String> annotations(Element e) {
        Set<String> out = new LinkedHashSet<>();
        if (e == null) {
            return List.of();
        }
        Optional<TreePath> declaration = model.declaration(e);
        if (declaration.isPresent()) {
            CompilationUnitTree unit = declaration.get().getCompilationUnit();
            Tree leaf = declaration.get().getLeaf();
            if (leaf instanceof VariableTree v) {
                v.getModifiers().getAnnotations().forEach(a -> out.add(collapse(model.text(unit, a))));
                typeAnnotations(unit, v.getType(), out);
            } else if (leaf instanceof MethodTree m) {
                m.getModifiers().getAnnotations().forEach(a -> out.add(collapse(model.text(unit, a))));
                typeAnnotations(unit, m.getReturnType(), out);
            }
            out.remove("");
            return List.copyOf(out);
        }
        e.getAnnotationMirrors().forEach(a -> out.add(a.toString()));
        TypeMirror type = e instanceof ExecutableElement ee ? ee.getReturnType() : e.asType();
        if (type != null) {
            type.getAnnotationMirrors().forEach(a -> out.add(a.toString()));
        }
        return List.copyOf(out);
    }

    private void typeAnnotations(CompilationUnitTree unit, Tree type, Set<String> out) {
        if (type instanceof AnnotatedTypeTree at) {
            for (AnnotationTree a : at.getAnnotations()) {
                out.add(collapse(model.text(unit, a)));
            }
        }
    }

    // ---------------------------------------------------------------- locations and text

    SourceRef ref(TreePath path) {
        long offset = startOf(path);
        SourceModel.Unit unit = model.unit(path.getCompilationUnit());
        if (offset < 0) {
            return new SourceRef(unit.relativePath(), 0, 0);
        }
        return new SourceRef(unit.relativePath(), unit.line(offset), unit.column(offset));
    }

    /** Start offset of the leaf, or of the nearest ancestor with a source position. */
    long startOf(TreePath path) {
        for (TreePath p = path; p != null; p = p.getParentPath()) {
            long s = model.start(path.getCompilationUnit(), p.getLeaf());
            if (s >= 0) {
                return s;
            }
        }
        return -1;
    }

    String text(TreePath path) {
        return model.text(path.getCompilationUnit(), path.getLeaf());
    }

    static String collapse(String text) {
        String s = text.replaceAll("\\s+", " ").strip();
        return s.length() > MAX_EXPRESSION_CHARS ? s.substring(0, MAX_EXPRESSION_CHARS - 1) + "…" : s;
    }

    static TreePath child(TreePath parent, Tree child) {
        return new TreePath(parent, child);
    }

    static TreePath stripParens(TreePath path) {
        TreePath p = path;
        while (p.getLeaf() instanceof ParenthesizedTree paren) {
            p = child(p, paren.getExpression());
        }
        return p;
    }

    static boolean isNullLiteral(Tree t) {
        Tree x = t;
        while (x instanceof ParenthesizedTree p) {
            x = p.getExpression();
        }
        return x.getKind() == Tree.Kind.NULL_LITERAL;
    }

    // ---------------------------------------------------------------- snippets

    /** The statement containing {@code site} (only the header of a compound statement whose header holds the site). */
    Snippet snippet(TreePath site) {
        CompilationUnitTree cu = site.getCompilationUnit();
        SourceModel.Unit unit = model.unit(cu);
        long siteOffset = startOf(site);
        if (siteOffset < 0) {
            return null;
        }
        TreePath stmt = statementOf(site);
        long start = model.start(cu, stmt.getLeaf());
        long end = headerEnd(stmt, siteOffset);
        if (start < 0 || end <= start) {
            start = siteOffset;
            end = siteOffset + 1;
        }
        int first = unit.line(start);
        int last = unit.line(Math.max(start, end - 1));
        return window(unit, first, last, unit.line(siteOffset));
    }

    /** Snippet of whole lines {@code first..last} centered on {@code focus}, limited and dedented. */
    Snippet window(SourceModel.Unit unit, int first, int last, int focus) {
        int count = last - first + 1;
        int max = limits.maxSnippetLines();
        int from = first;
        int to = last;
        if (count > max) {
            from = Math.max(first, focus - (max - 1) / 2);
            to = from + max - 1;
            if (to > last) {
                to = last;
                from = to - max + 1;
            }
        }
        List<String> lines = new ArrayList<>();
        for (int l = from; l <= to; l++) {
            lines.add(unit.lineText(l));
        }
        int indent = Integer.MAX_VALUE;
        for (String l : lines) {
            if (!l.isBlank()) {
                int i = 0;
                while (i < l.length() && Character.isWhitespace(l.charAt(i))) {
                    i++;
                }
                indent = Math.min(indent, i);
            }
        }
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            String s = l.isBlank() ? "" : l.substring(Math.min(indent, l.length()));
            out.add(s.length() > MAX_LINE_CHARS ? s.substring(0, MAX_LINE_CHARS - 1) + "…" : s);
        }
        return new Snippet(from, to, out, count - (to - from + 1));
    }

    private TreePath statementOf(TreePath site) {
        TreePath candidate = site;
        while (candidate.getParentPath() != null) {
            Tree leaf = candidate.getLeaf();
            Tree parent = candidate.getParentPath().getLeaf();
            if (parent instanceof MethodTree || parent instanceof ClassTree
                    || parent instanceof CompilationUnitTree) {
                return leaf instanceof BlockTree || parent instanceof MethodTree && leaf != ((MethodTree) parent)
                        .getBody() && !(leaf instanceof VariableTree) ? candidate.getParentPath() : candidate;
            }
            if (leaf instanceof StatementTree && !(parent instanceof ExpressionTree)
                    && !(leaf instanceof BlockTree)) {
                return candidate;
            }
            candidate = candidate.getParentPath();
        }
        return candidate;
    }

    private long headerEnd(TreePath stmt, long siteOffset) {
        CompilationUnitTree cu = stmt.getCompilationUnit();
        Tree leaf = stmt.getLeaf();
        Tree body = switch (leaf) {
            case IfTree t -> t.getThenStatement();
            case WhileLoopTree t -> t.getStatement();
            case ForLoopTree t -> t.getStatement();
            case EnhancedForLoopTree t -> t.getStatement();
            case SynchronizedTree t -> t.getBlock();
            case TryTree t -> t.getBlock();
            case LabeledStatementTree t -> t.getStatement();
            case SwitchTree t -> t.getCases().isEmpty() ? null : t.getCases().getFirst();
            case MethodTree t -> t.getBody();
            case ClassTree t -> t.getMembers().stream()
                    .filter(m -> model.start(cu, m) > model.start(cu, t) && model.end(cu, m) > 0).findFirst()
                    .orElse(null);
            default -> null;
        };
        long end = model.end(cu, leaf);
        if (body != null) {
            long bodyStart = model.start(cu, body);
            if (bodyStart > siteOffset || leaf instanceof MethodTree || leaf instanceof ClassTree) {
                return bodyStart > 0 ? bodyStart : end;
            }
        }
        if (leaf instanceof ClassTree && end > 0) {
            SourceModel.Unit unit = model.unit(cu);
            int brace = unit.text().indexOf('{', (int) model.start(cu, leaf));
            return brace > 0 ? brace + 1 : end;
        }
        return end;
    }

    // ---------------------------------------------------------------- conditions

    record Conditions(List<Condition> kept, int omitted) {}

    /** Enclosing conditions of {@code site} up to its member or lambda boundary, nearest first. */
    Conditions conditions(TreePath site) {
        List<Condition> all = new ArrayList<>();
        TreePath child = site;
        for (TreePath p = site.getParentPath(); p != null; child = p, p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            Tree c = child.getLeaf();
            if (leaf instanceof MethodTree || leaf instanceof ClassTree || leaf instanceof LambdaExpressionTree) {
                break;
            }
            switch (leaf) {
                case IfTree t when c == t.getThenStatement() -> all.add(condition("IF_THEN", p, t.getCondition()));
                case IfTree t when c == t.getElseStatement() -> all.add(condition("IF_ELSE", p, t.getCondition()));
                case WhileLoopTree t when c == t.getStatement() ->
                        all.add(condition("LOOP_BODY", p, t.getCondition()));
                case DoWhileLoopTree t when c == t.getStatement() ->
                        all.add(condition("LOOP_BODY", p, t.getCondition()));
                case ForLoopTree t when c == t.getStatement() && t.getCondition() != null ->
                        all.add(condition("LOOP_BODY", p, t.getCondition()));
                case EnhancedForLoopTree t when c == t.getStatement() -> all.add(new Condition("FOREACH_BODY",
                        collapse(model.text(p.getCompilationUnit(), t.getVariable()) + " : "
                                + model.text(p.getCompilationUnit(), t.getExpression())), lineOf(p, t)));
                case ConditionalExpressionTree t when c == t.getTrueExpression() ->
                        all.add(condition("TERNARY_TRUE", p, t.getCondition()));
                case ConditionalExpressionTree t when c == t.getFalseExpression() ->
                        all.add(condition("TERNARY_FALSE", p, t.getCondition()));
                case BinaryTree t when t.getKind() == Tree.Kind.CONDITIONAL_AND && c == t.getRightOperand() ->
                        all.add(condition("AND_RIGHT", p, t.getLeftOperand()));
                case BinaryTree t when t.getKind() == Tree.Kind.CONDITIONAL_OR && c == t.getRightOperand() ->
                        all.add(condition("OR_RIGHT", p, t.getLeftOperand()));
                case CaseTree t -> {
                    List<String> labels = new ArrayList<>();
                    CompilationUnitTree cu = p.getCompilationUnit();
                    t.getLabels().forEach(l -> labels.add(model.text(cu, l)));
                    all.add(new Condition("CASE", collapse(String.join(", ", labels)), lineOf(p, t)));
                }
                case CatchTree t -> all.add(new Condition("CATCH",
                        collapse(model.text(p.getCompilationUnit(), t.getParameter())), lineOf(p, t)));
                case SynchronizedTree t when c == t.getBlock() -> all.add(condition("SYNCHRONIZED", p,
                        t.getExpression()));
                default -> { }
            }
        }
        int keep = Math.min(all.size(), limits.maxConditions());
        return new Conditions(all.subList(0, keep), all.size() - keep);
    }

    private Condition condition(String kind, TreePath owner, Tree condition) {
        return new Condition(kind, collapse(model.text(owner.getCompilationUnit(), condition)),
                lineOf(owner, condition));
    }

    private int lineOf(TreePath any, Tree tree) {
        long s = model.start(any.getCompilationUnit(), tree);
        return s < 0 ? 0 : model.unit(any.getCompilationUnit()).line(s);
    }

    // ---------------------------------------------------------------- null guards

    /** Whether {@code expression} (a path) refers to {@code var}. */
    boolean refersTo(TreePath expression, VarRef var) {
        TreePath e = stripParens(expression);
        Tree leaf = e.getLeaf();
        if (!(leaf instanceof IdentifierTree) && !(leaf instanceof MemberSelectTree)) {
            return false;
        }
        Element el = element(e);
        if (el == null || !el.equals(var.element())) {
            return false;
        }
        return receiverOf(e).equals(var.receiver());
    }

    /** Normalized receiver text of a variable reference ("" for a simple name or {@code this.}). */
    String receiverOf(TreePath reference) {
        if (reference.getLeaf() instanceof MemberSelectTree ms) {
            String r = collapse(model.text(reference.getCompilationUnit(), ms.getExpression())).replace(" ", "");
            return r.equals("this") ? "" : r;
        }
        return "";
    }

    /** Whether {@code var} is non-null when {@code condition} evaluates to {@code outcome}. */
    boolean nonNullWhen(TreePath condition, VarRef var, boolean outcome) {
        TreePath c = stripParens(condition);
        Tree leaf = c.getLeaf();
        if (leaf instanceof BinaryTree b) {
            switch (b.getKind()) {
                case NOT_EQUAL_TO, EQUAL_TO -> {
                    boolean compares = (isNullLiteral(b.getRightOperand()) && refersTo(child(c, b.getLeftOperand()), var))
                            || (isNullLiteral(b.getLeftOperand()) && refersTo(child(c, b.getRightOperand()), var));
                    return compares && (b.getKind() == Tree.Kind.NOT_EQUAL_TO) == outcome;
                }
                case CONDITIONAL_AND -> {
                    return outcome && (nonNullWhen(child(c, b.getLeftOperand()), var, true)
                            || nonNullWhen(child(c, b.getRightOperand()), var, true));
                }
                case CONDITIONAL_OR -> {
                    return !outcome && (nonNullWhen(child(c, b.getLeftOperand()), var, false)
                            || nonNullWhen(child(c, b.getRightOperand()), var, false));
                }
                default -> {
                    return false;
                }
            }
        }
        if (leaf instanceof InstanceOfTree i) {
            return outcome && refersTo(child(c, i.getExpression()), var);
        }
        if (leaf instanceof UnaryTree u && u.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
            return nonNullWhen(child(c, u.getExpression()), var, !outcome);
        }
        return false;
    }

    /**
     * Syntactic null checks of {@code var} that guard {@code site}: enclosing conditions, earlier
     * early exits ({@code if (x == null) return;}), {@code assert x != null} and {@code
     * Objects.requireNonNull(x)} in enclosing blocks. Stops at the member or lambda boundary.
     */
    List<Map<String, Object>> nullGuards(TreePath site, VarRef var) {
        List<Map<String, Object>> guards = new ArrayList<>();
        TreePath child = site;
        for (TreePath p = site.getParentPath(); p != null; child = p, p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            Tree c = child.getLeaf();
            if (leaf instanceof MethodTree || leaf instanceof ClassTree || leaf instanceof LambdaExpressionTree) {
                break;
            }
            switch (leaf) {
                case IfTree t -> {
                    if ((c == t.getThenStatement() && nonNullWhen(child(p, t.getCondition()), var, true))
                            || (c == t.getElseStatement() && nonNullWhen(child(p, t.getCondition()), var, false))) {
                        guards.add(guard("ENCLOSING_CONDITION", p, t.getCondition()));
                    }
                }
                case ConditionalExpressionTree t -> {
                    if ((c == t.getTrueExpression() && nonNullWhen(child(p, t.getCondition()), var, true))
                            || (c == t.getFalseExpression() && nonNullWhen(child(p, t.getCondition()), var, false))) {
                        guards.add(guard("ENCLOSING_CONDITION", p, t.getCondition()));
                    }
                }
                case BinaryTree t when c == t.getRightOperand() -> {
                    if ((t.getKind() == Tree.Kind.CONDITIONAL_AND && nonNullWhen(child(p, t.getLeftOperand()), var, true))
                            || (t.getKind() == Tree.Kind.CONDITIONAL_OR
                                    && nonNullWhen(child(p, t.getLeftOperand()), var, false))) {
                        guards.add(guard("ENCLOSING_CONDITION", p, t.getLeftOperand()));
                    }
                }
                case WhileLoopTree t when c == t.getStatement() -> {
                    if (nonNullWhen(child(p, t.getCondition()), var, true)) {
                        guards.add(guard("ENCLOSING_CONDITION", p, t.getCondition()));
                    }
                }
                case ForLoopTree t when c == t.getStatement() && t.getCondition() != null -> {
                    if (nonNullWhen(child(p, t.getCondition()), var, true)) {
                        guards.add(guard("ENCLOSING_CONDITION", p, t.getCondition()));
                    }
                }
                case BlockTree b -> {
                    for (StatementTree s : b.getStatements()) {
                        if (s == c) {
                            break;
                        }
                        earlierGuard(child(p, s), var).ifPresent(guards::add);
                    }
                }
                default -> { }
            }
        }
        return guards;
    }

    private Optional<Map<String, Object>> earlierGuard(TreePath stmt, VarRef var) {
        Tree s = stmt.getLeaf();
        if (s instanceof IfTree t && t.getElseStatement() == null && exits(t.getThenStatement())
                && nonNullWhen(child(stmt, t.getCondition()), var, false)) {
            return Optional.of(guard("EARLY_EXIT", stmt, t.getCondition()));
        }
        if (s instanceof AssertTree a && nonNullWhen(child(stmt, a.getCondition()), var, true)) {
            return Optional.of(guard("ASSERTION", stmt, a.getCondition()));
        }
        if (s instanceof ExpressionStatementTree es && es.getExpression() instanceof MethodInvocationTree mi
                && !mi.getArguments().isEmpty() && isNonNullCheck(element(child(child(stmt, es.getExpression()),
                        mi.getMethodSelect())))
                && refersTo(child(child(stmt, es.getExpression()), mi.getArguments().getFirst()), var)) {
            return Optional.of(guard("REQUIRE_NON_NULL", stmt, mi));
        }
        return Optional.empty();
    }

    private boolean isNonNullCheck(Element e) {
        if (!(e instanceof ExecutableElement ee) || !(ee.getEnclosingElement() instanceof TypeElement owner)) {
            return false;
        }
        String o = owner.getQualifiedName().toString();
        String n = ee.getSimpleName().toString();
        return (o.equals("java.util.Objects") && n.equals("requireNonNull"))
                || (o.equals("com.google.common.base.Preconditions") && n.equals("checkNotNull"));
    }

    private static boolean exits(StatementTree s) {
        return switch (s) {
            case com.sun.source.tree.ReturnTree r -> true;
            case ThrowTree t -> true;
            case com.sun.source.tree.BreakTree b -> true;
            case com.sun.source.tree.ContinueTree c -> true;
            case BlockTree b -> !b.getStatements().isEmpty() && exits(b.getStatements().getLast());
            default -> false;
        };
    }

    private Map<String, Object> guard(String kind, TreePath owner, Tree condition) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("condition", collapse(model.text(owner.getCompilationUnit(), condition)));
        m.put("line", lineOf(owner, condition));
        return m;
    }

    // ---------------------------------------------------------------- consumption

    /**
     * How the value of the expression at {@code expr} is used by its context. Parentheses and casts
     * are looked through. When the value is stored in a local variable and {@code followLocals} is
     * set, the local's later uses in the same member are classified too (one hop, limited).
     */
    Map<String, Object> consumption(TreePath expr, Set<Observation> obs, boolean followLocals) {
        Map<String, Object> m = new LinkedHashMap<>();
        TreePath child = expr;
        TreePath p = expr.getParentPath();
        List<String> via = new ArrayList<>();
        while (p != null && (p.getLeaf() instanceof ParenthesizedTree
                || (p.getLeaf() instanceof TypeCastTree tc && tc.getExpression() == child.getLeaf()))) {
            if (p.getLeaf() instanceof TypeCastTree tc) {
                via.add("cast to " + collapse(model.text(p.getCompilationUnit(), tc.getType())));
            }
            child = p;
            p = p.getParentPath();
        }
        if (p == null) {
            m.put("kind", "OTHER");
            return m;
        }
        Tree c = child.getLeaf();
        Tree leaf = p.getLeaf();
        switch (leaf) {
            case MemberSelectTree ms when ms.getExpression() == c -> {
                TreePath grand = p.getParentPath();
                boolean call = grand != null && grand.getLeaf() instanceof MethodInvocationTree mi
                        && mi.getMethodSelect() == ms;
                m.put("kind", call ? "DEREFERENCE_METHOD_CALL" : "DEREFERENCE_FIELD_ACCESS");
                m.put("member", ms.getIdentifier().toString());
                obs.add(Observation.DEREFERENCE);
            }
            case MemberReferenceTree mr when mr.getQualifierExpression() == c -> {
                m.put("kind", "METHOD_REFERENCE_RECEIVER");
                m.put("member", mr.getName().toString());
                obs.add(Observation.DEREFERENCE);
            }
            case ArrayAccessTree aa -> {
                m.put("kind", aa.getExpression() == c ? "ARRAY_ACCESS" : "ARRAY_INDEX");
                if (aa.getExpression() == c) {
                    obs.add(Observation.DEREFERENCE);
                }
            }
            case BinaryTree b when b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO -> {
                Tree other = b.getLeftOperand() == c ? b.getRightOperand() : b.getLeftOperand();
                if (isNullLiteral(other)) {
                    m.put("kind", "NULL_COMPARISON");
                    m.put("operator", b.getKind() == Tree.Kind.EQUAL_TO ? "== null" : "!= null");
                    obs.add(Observation.NULL_COMPARISON);
                } else {
                    m.put("kind", "REFERENCE_COMPARISON");
                }
            }
            case BinaryTree b -> {
                TypeMirror t = model.trees().getTypeMirror(p);
                boolean concat = b.getKind() == Tree.Kind.PLUS && t != null && t.toString().equals("java.lang.String");
                m.put("kind", concat ? "STRING_CONCATENATION" : "BINARY_OPERAND");
                if (!concat) {
                    m.put("operator", b.getKind().name());
                }
            }
            case InstanceOfTree i -> m.put("kind", "INSTANCEOF_OPERAND");
            case UnaryTree u -> {
                m.put("kind", "UNARY_OPERAND");
                m.put("operator", u.getKind().name());
            }
            case VariableTree v when v.getInitializer() == c -> {
                m.put("kind", "ASSIGNED_TO_VARIABLE");
                variableDetails(p, element(p), m);
                obs.add(Observation.ASSIGNED);
                if (followLocals) {
                    localUses(p, element(p), model.end(p.getCompilationUnit(), v), m);
                }
            }
            case AssignmentTree a when a.getExpression() == c -> {
                TreePath lhs = child(p, a.getVariable());
                m.put("kind", "ASSIGNED_TO_VARIABLE");
                variableDetails(lhs, element(lhs), m);
                obs.add(Observation.ASSIGNED);
                if (followLocals) {
                    localUses(p, element(lhs), model.end(p.getCompilationUnit(), a), m);
                }
            }
            case CompoundAssignmentTree ca -> m.put("kind", "COMPOUND_ASSIGNMENT_OPERAND");
            case ReturnTree r -> {
                m.put("kind", "RETURNED");
                returnDetails(p, m);
                obs.add(Observation.RETURNED);
            }
            case LambdaExpressionTree l when l.getBody() == c -> {
                m.put("kind", "RETURNED_FROM_LAMBDA");
                obs.add(Observation.RETURNED);
            }
            case MethodInvocationTree mi when mi.getArguments().contains(c) -> {
                m.put("kind", "ARGUMENT");
                argumentDetails(p, mi.getArguments().indexOf(c), element(p), m);
                obs.add(Observation.PASSED_AS_ARGUMENT);
            }
            case NewClassTree nc when nc.getArguments().contains(c) -> {
                m.put("kind", "ARGUMENT");
                argumentDetails(p, nc.getArguments().indexOf(c), element(p), m);
                obs.add(Observation.PASSED_AS_ARGUMENT);
            }
            case EnhancedForLoopTree f when f.getExpression() == c -> {
                m.put("kind", "ITERATED");
                obs.add(Observation.DEREFERENCE);
            }
            case SynchronizedTree s -> {
                m.put("kind", "SYNCHRONIZED_ON");
                obs.add(Observation.DEREFERENCE);
            }
            case ThrowTree t -> {
                m.put("kind", "THROWN");
                obs.add(Observation.DEREFERENCE);
            }
            case SwitchTree s when s.getExpression() == c -> {
                m.put("kind", "SWITCH_SELECTOR");
                obs.add(Observation.DEREFERENCE);
            }
            case SwitchExpressionTree s when s.getExpression() == c -> {
                m.put("kind", "SWITCH_SELECTOR");
                obs.add(Observation.DEREFERENCE);
            }
            case IfTree t -> m.put("kind", "CONDITION");
            case WhileLoopTree t -> m.put("kind", "CONDITION");
            case DoWhileLoopTree t -> m.put("kind", "CONDITION");
            case ForLoopTree t -> m.put("kind", "CONDITION");
            case AssertTree t -> m.put("kind", "CONDITION");
            case ConditionalExpressionTree t when t.getCondition() == c -> m.put("kind", "TERNARY_CONDITION");
            case ConditionalExpressionTree t -> {
                m.put("kind", "TERNARY_BRANCH");
                m.put("ternaryUse", consumption(p, obs, followLocals));
            }
            case ExpressionStatementTree s -> {
                m.put("kind", "DISCARDED");
                obs.add(Observation.DISCARDED);
            }
            case NewArrayTree n -> m.put("kind", "ARRAY_ELEMENT");
            default -> {
                m.put("kind", "OTHER");
                m.put("context", leaf.getKind().name());
            }
        }
        if (!via.isEmpty()) {
            m.put("via", via);
        }
        return m;
    }

    private void variableDetails(TreePath at, Element variable, Map<String, Object> m) {
        if (variable == null) {
            m.put("variable", collapse(text(at)));
            m.put("variableKind", "UNRESOLVED");
            return;
        }
        m.put("variable", label(variable));
        m.put("variableKind", variable.getKind().name());
        m.put("variableType", simpleTypes(variable.asType()));
        m.put("variableAnnotations", annotations(variable));
    }

    private void returnDetails(TreePath returnPath, Map<String, Object> m) {
        for (TreePath p = returnPath; p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof LambdaExpressionTree) {
                m.put("from", "lambda expression");
                return;
            }
            if (p.getLeaf() instanceof MethodTree) {
                Element method = element(p);
                m.put("from", label(method));
                m.put("methodAnnotations", annotations(method));
                return;
            }
        }
    }

    private void argumentDetails(TreePath call, int index, Element callee, Map<String, Object> m) {
        m.put("argumentIndex", index);
        if (!(callee instanceof ExecutableElement ee)) {
            m.put("callee", "<unresolved>");
            return;
        }
        m.put("callee", label(ee));
        List<? extends VariableElement> params = ee.getParameters();
        if (params.isEmpty()) {
            return;
        }
        boolean varargs = ee.isVarArgs() && index >= params.size() - 1;
        VariableElement param = params.get(Math.min(index, params.size() - 1));
        m.put("parameter", param.getSimpleName().toString());
        m.put("parameterType", simpleTypes(param.asType()));
        if (varargs) {
            m.put("varargs", true);
        }
        m.put("parameterAnnotations", annotations(param));
    }

    /** Later uses of local {@code variable} after {@code after} within the same member, classified (no further hops). */
    private void localUses(TreePath at, Element variable, long after, Map<String, Object> m) {
        if (variable == null || !(variable.getKind() == ElementKind.LOCAL_VARIABLE
                || variable.getKind() == ElementKind.RESOURCE_VARIABLE)) {
            return;
        }
        TreePath scope = memberBody(at);
        if (scope == null) {
            return;
        }
        List<TreePath> uses = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                long s = model.start(getCurrentPath().getCompilationUnit(), node);
                if (s >= after && variable.equals(element(getCurrentPath()))) {
                    uses.add(getCurrentPath());
                }
                return null;
            }
        }.scan(scope, null);
        List<Map<String, Object>> described = new ArrayList<>();
        for (TreePath use : uses.subList(0, Math.min(uses.size(), limits.maxLocalUses()))) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("line", ref(use).line());
            Set<Observation> ignored = new LinkedHashSet<>();
            Tree parent = use.getParentPath().getLeaf();
            if (parent instanceof AssignmentTree a && a.getVariable() == use.getLeaf()) {
                u.put("use", Map.of("kind", "REASSIGNED"));
            } else {
                u.put("use", consumption(use, ignored, false));
            }
            List<Map<String, Object>> guards = nullGuards(use, new VarRef(variable, ""));
            if (!guards.isEmpty()) {
                u.put("nullGuards", guards);
            }
            Snippet s = snippet(use);
            u.put("statement", s == null ? "" : collapse(String.join(" ", s.lines())));
            described.add(u);
        }
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("variable", variable.getSimpleName().toString());
        local.put("total", uses.size());
        local.put("omitted", uses.size() - described.size());
        local.put("uses", described);
        m.put("laterUsesOfLocal", local);
    }

    /** The body of the nearest enclosing method, lambda, initializer block or field initializer. */
    TreePath memberBody(TreePath at) {
        for (TreePath p = at; p != null; p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            TreePath parent = p.getParentPath();
            if (leaf instanceof MethodTree mt) {
                return mt.getBody() == null ? null : child(p, mt.getBody());
            }
            if (parent != null && parent.getLeaf() instanceof ClassTree
                    && (leaf instanceof BlockTree || leaf instanceof VariableTree)) {
                return p;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- expressions

    /**
     * What kind of expression {@code expr} is (null literal, parameter, local, field, call, ...),
     * with the referenced symbol's declared annotations. Local variable definitions are added to
     * {@code definitions}.
     */
    Map<String, Object> describe(TreePath expr, Set<Observation> obs, List<LocalDefinition> definitions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", collapse(text(expr)));
        TreePath e = stripParens(expr);
        Tree leaf = e.getLeaf();
        switch (leaf) {
            case Tree t when t.getKind() == Tree.Kind.NULL_LITERAL -> {
                m.put("kind", "NULL_LITERAL");
                obs.add(Observation.NULL_LITERAL);
            }
            case com.sun.source.tree.LiteralTree l -> m.put("kind", "LITERAL");
            case IdentifierTree id when id.getName().contentEquals("this") -> m.put("kind", "THIS");
            case IdentifierTree id -> describeName(e, m, definitions);
            case MemberSelectTree ms -> describeName(e, m, definitions);
            case MethodInvocationTree mi -> {
                Element callee = element(e);
                m.put("kind", "METHOD_CALL");
                m.put("callee", callee instanceof ExecutableElement ? label(callee) : "<unresolved>");
                if (callee instanceof ExecutableElement) {
                    m.put("calleeAnnotations", annotations(callee));
                }
            }
            case NewClassTree nc -> m.put("kind", "NEW_OBJECT");
            case NewArrayTree na -> m.put("kind", "NEW_ARRAY");
            case ConditionalExpressionTree ce -> {
                m.put("kind", "CONDITIONAL");
                if (isNullLiteral(ce.getTrueExpression()) || isNullLiteral(ce.getFalseExpression())) {
                    obs.add(Observation.CONDITIONAL_WITH_NULL_BRANCH);
                }
                m.put("trueBranch", describe(child(e, ce.getTrueExpression()), new LinkedHashSet<>(), definitions)
                        .get("kind"));
                m.put("falseBranch", describe(child(e, ce.getFalseExpression()), new LinkedHashSet<>(), definitions)
                        .get("kind"));
            }
            case LambdaExpressionTree l -> m.put("kind", "LAMBDA");
            case MemberReferenceTree r -> m.put("kind", "METHOD_REFERENCE");
            case TypeCastTree tc -> {
                m.put("kind", "CAST");
                m.put("operand", describe(child(e, tc.getExpression()), obs, definitions));
            }
            case AssignmentTree a -> m.put("kind", "ASSIGNMENT");
            case BinaryTree b -> m.put("kind", "BINARY_EXPRESSION");
            case UnaryTree u -> m.put("kind", "UNARY_EXPRESSION");
            default -> m.put("kind", leaf.getKind().name());
        }
        return m;
    }

    private void describeName(TreePath e, Map<String, Object> m, List<LocalDefinition> definitions) {
        Element el = element(e);
        if (el instanceof VariableElement v) {
            m.put("kind", v.getKind().name());
            m.put("variable", label(v));
            List<String> annotations = annotations(v);
            if (!annotations.isEmpty()) {
                m.put("annotations", annotations);
            }
            if (v.getKind() == ElementKind.LOCAL_VARIABLE || v.getKind() == ElementKind.RESOURCE_VARIABLE
                    || v.getKind() == ElementKind.BINDING_VARIABLE) {
                definitions.addAll(localDefinitions(v, e));
            }
        } else if (isErroneous(el)) {
            m.put("kind", "UNRESOLVED_NAME");
        } else {
            m.put("kind", el.getKind().name());
        }
    }

    /** The declaration of a local variable and up to two earlier assignments to it before {@code use}. */
    List<LocalDefinition> localDefinitions(Element local, TreePath use) {
        List<LocalDefinition> defs = new ArrayList<>();
        String name = local.getSimpleName().toString();
        model.declaration(local).ifPresent(d -> {
            SourceModel.Unit unit = model.unit(d.getCompilationUnit());
            long s = model.start(d.getCompilationUnit(), d.getLeaf());
            defs.add(new LocalDefinition(name, "DECLARATION", s < 0 ? 0 : unit.line(s), collapse(text(d))));
        });
        long useStart = startOf(use);
        TreePath scope = memberBody(use);
        if (scope != null) {
            List<TreePath> assignments = new ArrayList<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitAssignment(AssignmentTree node, Void unused) {
                    TreePath lhs = child(getCurrentPath(), node.getVariable());
                    if (model.start(getCurrentPath().getCompilationUnit(), node) < useStart
                            && local.equals(element(lhs))) {
                        assignments.add(getCurrentPath());
                    }
                    return super.visitAssignment(node, unused);
                }
            }.scan(scope, null);
            for (TreePath a : assignments.subList(Math.max(0, assignments.size() - MAX_EARLIER_ASSIGNMENTS),
                    assignments.size())) {
                defs.add(new LocalDefinition(name, "ASSIGNMENT", ref(a).line(), collapse(text(a))));
            }
        }
        return defs;
    }

    /** Whether a method's override relations are meaningful (instance, non-private, not a constructor). */
    static boolean overridable(ExecutableElement method) {
        return method.getKind() == ElementKind.METHOD && !method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.PRIVATE);
    }
}
