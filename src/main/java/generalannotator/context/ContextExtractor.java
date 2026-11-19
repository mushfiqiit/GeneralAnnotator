package generalannotator.context;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import generalannotator.context.Evidence.LocalDefinition;
import generalannotator.context.Evidence.SourceRef;
import generalannotator.context.SiteAnalyzer.Conditions;
import generalannotator.context.SiteAnalyzer.VarRef;
import generalannotator.context.SourceModel.CompilerError;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.graph.WarningNode;
import generalannotator.targets.MemberKind;
import generalannotator.targets.MemberLocator;
import generalannotator.targets.ParsedSource;
import generalannotator.targets.WarningTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Extracts usage context for a warning's target member from the attributed run snapshot: where the
 * target's values come from and how they are used (a practical one-hop heuristic inspired by
 * bidirectional type slicing, not that formal algorithm).
 *
 * <p>References are matched by the compiler symbol javac resolved, never by name. A reference that
 * carries the target's name but has no symbol, or whose symbol javac chose while reporting an error
 * at that reference, is listed as unresolved and not counted as a usage. Usages are not filtered by
 * any safe/unsafe region policy (project-spec §6.2).
 */
public final class ContextExtractor {

    static final List<String> STANDARD_LIMITATIONS = List.of(
            "Evidence is syntactic and one hop: call sites, arguments, parameter uses, returns and field "
                    + "accesses of the target only; values are not followed through further calls.",
            "References are matched by the compile-time symbol javac resolved. Calls through a supertype "
                    + "method that the target overrides, reflection and method handles are not counted as uses "
                    + "of the target (overrideRelations lists the override links).",
            "Null guards are recognized syntactically (x != null conditions, instanceof, early exits, assert, "
                    + "Objects.requireNonNull); intervening assignments, aliasing and concurrency are ignored.",
            "Later uses of a local holding the value are attributed textually: an unconditional earlier "
                    + "reassignment ends attribution, a reassignment on some paths (or a loop back edge) makes the use "
                    + "MAY_BE_REASSIGNED; exceptions and labeled jumps are ignored.",
            "Only the configured source roots of the run snapshot are analyzed; generated sources and code "
                    + "outside them are not.",
            "No safe/unsafe region classification is applied (project-spec §6.2); all found usages are listed.",
            "Observations describe the code; they do not establish which annotation is correct.");

    private final SourceModel model;
    private final SiteAnalyzer sites;
    private final ContextLimits limits;
    private final List<String> runLimitations;
    private final CompactViewBuilder compact;

    /**
     * @param runLimitations limitations that apply to every warning of the run (for example compiler
     *     errors during attribution or missing classpath entries)
     */
    public ContextExtractor(SourceModel model, ContextLimits limits, List<String> runLimitations) {
        this.model = model;
        this.limits = limits;
        this.sites = new SiteAnalyzer(model, limits);
        this.runLimitations = List.copyOf(runLimitations);
        this.compact = new CompactViewBuilder(model, limits);
    }

    public WarningContext extract(WarningTarget target, Optional<WarningNode> node) {
        Map<String, Object> warning = warning(target, node);
        Optional<Evidence> site = warningSite(target.location());
        List<String> limitations = new ArrayList<>(runLimitations);
        if (target instanceof WarningTarget.Unresolved u) {
            limitations.add("The warning has no resolved target member (" + u.reason() + "), so only the warning "
                    + "site is described.");
            List<EvidenceCategory> categories = List.of(category("warningSite", site.stream().toList()));
            return new WarningContext(u.warningId(), ContextStatus.SKIPPED, Optional.of(u.reason()), warning, Map.of(),
                    Map.of(), categories, limitations, compact.build(categories, null));
        }
        WarningTarget.Resolved r = (WarningTarget.Resolved) target;
        String file = r.sourceRoot().equals(".") ? r.targetFile() : r.sourceRoot() + "/" + r.targetFile();
        Optional<SourceModel.Unit> unit = model.unit(file);
        if (unit.isEmpty()) {
            return failed(r, warning, site, limitations, "the target file " + file + " is not among the analyzed "
                    + "sources of the run snapshot");
        }
        ParsedSource parsed = new ParsedSource(unit.get().tree(), model.positions(), unit.get().text());
        String wanted = r.target().replaceAll("\\s", "");
        List<MemberLocator.Member> members = MemberLocator.targetableMembers(parsed).stream()
                .filter(m -> m.kind() == r.memberKind() && m.target().replaceAll("\\s", "").equals(wanted)).toList();
        if (members.size() != 1) {
            return failed(r, warning, site, limitations, members.isEmpty()
                    ? "no " + r.memberKind() + " " + r.target() + " is declared in " + file
                    : members.size() + " members of " + file + " match " + r.target());
        }
        TreePath memberPath = TreePath.getPath(unit.get().tree(), members.getFirst().tree());
        Element element = model.trees().getElement(memberPath);
        if (element == null) {
            return failed(r, warning, site, limitations, "javac has no symbol for " + r.target());
        }
        if (!r.notes().isEmpty()) {
            limitations.addAll(r.notes().stream().map(n -> "Target note: " + n).toList());
        }
        if (r.configuredSourceRoot().isEmpty()) {
            limitations.add("The target file is not under a configured source root.");
        }
        limitations.addAll(STANDARD_LIMITATIONS);

        References refs = new References(element, r.memberKind());
        for (SourceModel.Unit u : model.units()) {
            refs.scan(u.tree(), null);
        }
        List<EvidenceCategory> categories = new ArrayList<>();
        categories.add(category("warningSite", site.stream().toList()));
        int matched;
        if (r.memberKind() == MemberKind.FIELD) {
            List<Evidence> writes = new ArrayList<>();
            List<Evidence> reads = new ArrayList<>();
            VariableTree declaration = (VariableTree) memberPath.getLeaf();
            if (declaration.getInitializer() != null) {
                writes.add(initializer(memberPath, declaration));
            }
            for (TreePath m : refs.matches) {
                fieldAccess(m, (VariableElement) element, writes, reads);
            }
            matched = refs.matches.size();
            categories.add(category("fieldWrites", writes));
            categories.add(category("fieldReads", reads));
        } else {
            ExecutableElement method = (ExecutableElement) element;
            List<Evidence> calls = new ArrayList<>();
            List<Evidence> references = new ArrayList<>();
            for (TreePath m : refs.matches) {
                if (m.getLeaf() instanceof MemberReferenceTree) {
                    references.add(methodReference(m));
                } else {
                    calls.add(callSite(m, method, refs.implicit.contains(m.getLeaf())));
                }
            }
            matched = refs.matches.size();
            categories.add(category("callSites", calls));
            categories.add(category("methodReferences", references));
            categories.add(category("parameterUses", parameterUses(memberPath, method)));
            if (r.memberKind() == MemberKind.METHOD) {
                categories.add(category("returnStatements", returns(memberPath)));
                categories.add(category("overrideRelations", overrides(method)));
            }
        }
        categories.add(category("unresolvedReferences", refs.unresolved));
        if (!refs.unresolved.isEmpty()) {
            limitations.add(refs.unresolved.size() + " reference(s) carrying the target's name could not be "
                    + "trusted to refer to it (see unresolvedReferences); they are not counted as usages.");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("matched", matched);
        summary.put("unresolved", refs.unresolved.size());
        summary.put("sameNameOtherSymbols", refs.excluded);
        return new WarningContext(r.warningId(), ContextStatus.EXTRACTED, Optional.empty(), warning,
                identity(r, file, memberPath, element), summary, categories, limitations,
                compact.build(categories, sites.member(memberPath)));
    }

    private WarningContext failed(WarningTarget.Resolved r, Map<String, Object> warning, Optional<Evidence> site,
            List<String> limitations, String reason) {
        limitations.add("Usage context was not extracted: " + reason + ".");
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("memberKind", r.memberKind().name());
        target.put("target", r.target());
        List<EvidenceCategory> categories = List.of(category("warningSite", site.stream().toList()));
        return new WarningContext(r.warningId(), ContextStatus.FAILED, Optional.of(reason), warning, target, Map.of(),
                categories, limitations, compact.build(categories, null));
    }

    // ---------------------------------------------------------------- categories

    static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("warningSite", "The statement at the warning location."),
            Map.entry("callSites", "Calls that javac resolved to the target, with argument expressions and how "
                    + "the produced value is used."),
            Map.entry("methodReferences", "Method references (::) that javac resolved to the target."),
            Map.entry("parameterUses", "Uses of the target's parameters inside its body."),
            Map.entry("returnStatements", "Return statements of the target and the returned expressions."),
            Map.entry("overrideRelations", "Methods the target overrides and source methods that override it."),
            Map.entry("fieldWrites", "The field's initializer and assignments to it, with the assigned expressions."),
            Map.entry("fieldReads", "Reads of the field and how the read value is used."),
            Map.entry("unresolvedReferences", "References carrying the target's name that javac could not "
                    + "resolve, or resolved while reporting an error there; not counted as usages."));

    /**
     * Deduplicates by kind, source location and symbol; keeps distinct evidence first (round robin
     * over facets: kind, how the value is used, observations) up to the item limit; returns the kept
     * items in source order.
     */
    private EvidenceCategory category(String name, List<Evidence> items) {
        Map<String, Evidence> unique = new LinkedHashMap<>();
        for (Evidence e : items) {
            String where = e.location() == null ? "binary:" + e.enclosingMember()
                    : e.location().file() + "@" + e.sortOffset();
            Object symbol = e.details().containsKey("parameterIndex") ? e.details().get("parameterIndex")
                    : e.details().get("method");
            unique.putIfAbsent(e.kind() + "|" + where + "|" + symbol, e);
        }
        List<Evidence> sorted = new ArrayList<>(unique.values());
        sorted.sort(SOURCE_ORDER);
        Map<String, List<Evidence>> facets = new LinkedHashMap<>();
        for (Evidence e : sorted) {
            facets.computeIfAbsent(facet(e), f -> new ArrayList<>()).add(e);
        }
        List<Evidence> kept = new ArrayList<>();
        for (int round = 0; kept.size() < limits.maxItemsPerCategory(); round++) {
            boolean any = false;
            for (List<Evidence> group : facets.values()) {
                if (round < group.size() && kept.size() < limits.maxItemsPerCategory()) {
                    kept.add(group.get(round));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        kept.sort(SOURCE_ORDER);
        return new EvidenceCategory(name, DESCRIPTIONS.get(name), sorted.size(), kept);
    }

    private static final Comparator<Evidence> SOURCE_ORDER = Comparator
            .<Evidence, String>comparing(e -> e.location() == null ? "\uffff" : e.location().file())
            .thenComparingLong(Evidence::sortOffset)
            .thenComparing(Evidence::kind)
            .thenComparing(e -> e.expression() == null ? "" : e.expression())
            .thenComparing(e -> String.valueOf(e.details().get("parameterIndex")));

    /** What makes evidence distinct for prioritization: its kind, how the value is used, and its observations. */
    private static String facet(Evidence e) {
        String use = "";
        for (String key : List.of("use", "resultUse", "returned", "assigned", "referenceUse")) {
            if (e.details().get(key) instanceof Map<?, ?> m && m.get("kind") != null) {
                use = String.valueOf(m.get("kind"));
                break;
            }
        }
        return e.kind() + "|" + use + "|" + e.observations();
    }

    private Evidence evidence(String kind, TreePath site, String expression, Map<String, Object> details,
            Set<Observation> obs, List<LocalDefinition> definitions) {
        Set<Observation> all = new LinkedHashSet<>(sites.contextObservations(site));
        all.addAll(obs);
        Conditions conditions = sites.conditions(site);
        List<LocalDefinition> defs = new ArrayList<>(new LinkedHashSet<>(definitions));
        Evidence.Snippet snippet = sites.snippet(site);
        return new Evidence(kind, sites.ref(site), sites.startOf(site), sites.enclosingMember(site), expression,
                snippet, conditions.kept(), conditions.omitted(), defs, details, all,
                marks(kind, site, snippet, conditions.kept(), defs, details, all));
    }

    /** Source ranges the compact view shows for one evidence item. */
    private List<Evidence.Mark> marks(String kind, TreePath site, Evidence.Snippet snippet,
            List<Evidence.Condition> conditions, List<LocalDefinition> defs, Map<String, Object> details,
            Set<Observation> obs) {
        Evidence.Member member = sites.member(site);
        if (member == null) {
            return List.of();
        }
        List<Evidence.Mark> marks = new ArrayList<>();
        int line = sites.ref(site).line();
        if (snippet != null) {
            marks.add(new Evidence.Mark(member, snippet.startLine(), snippet.endLine(), null));
        }
        marks.add(new Evidence.Mark(member, line, line, new Evidence.Label(label(kind, details))));
        for (String note : annotationNotes(details)) {
            marks.add(new Evidence.Mark(member, line, line, new Evidence.Label(note)));
        }
        for (Evidence.Condition c : conditions) {
            if (c.line() > 0) {
                marks.add(new Evidence.Mark(member, c.line(), c.line(), null));
            }
        }
        for (LocalDefinition d : defs) {
            if (d.line() > 0) {
                marks.add(new Evidence.Mark(member, d.line(), d.line(), null));
            }
        }
        if (obs.contains(Observation.NULL_COMPARISON)) {
            int[] exit = sites.exitBranchLines(site);
            if (exit != null) {
                marks.add(new Evidence.Mark(member, exit[0], Math.min(exit[1],
                        exit[0] + limits.maxSnippetLines() - 1), null));
            }
        }
        collect(details, member, line, marks);
        return marks;
    }

    /** Guard lines and local alias uses anywhere in the details. */
    private void collect(Object value, Evidence.Member member, int fromLine, List<Evidence.Mark> marks) {
        if (value instanceof Map<?, ?> m) {
            if (m.containsKey("kind") && m.containsKey("condition") && m.get("line") instanceof Integer l && l > 0) {
                marks.add(new Evidence.Mark(member, l, l, null));
                if (m.get("exitLines") instanceof List<?> exit && exit.size() == 2) {
                    int a = (Integer) exit.get(0);
                    int b = (Integer) exit.get(1);
                    marks.add(new Evidence.Mark(member, a, Math.min(b, a + limits.maxSnippetLines() - 1), null));
                }
            }
            if (m.get("laterUsesOfLocal") instanceof Map<?, ?> local) {
                String variable = String.valueOf(local.get("variable"));
                for (Object o : (List<?>) local.get("uses")) {
                    Map<?, ?> use = (Map<?, ?>) o;
                    int at = (Integer) use.get("line");
                    List<Integer> reassigned = new ArrayList<>();
                    if (use.get("reassignedAt") instanceof List<?> rs) {
                        rs.forEach(x -> reassigned.add((Integer) x));
                    }
                    marks.add(new Evidence.Mark(member, at, at, new Evidence.Alias(variable, fromLine,
                            "DEFINITE".equals(use.get("flow")), reassigned,
                            Boolean.TRUE.equals(use.get("assignedOnSomePathsOnly")))));
                    collect(use.get("nullGuards"), member, fromLine, marks);
                    if (use.get("exitLines") instanceof List<?> exit && exit.size() == 2) {
                        int a = (Integer) exit.get(0);
                        int b = (Integer) exit.get(1);
                        marks.add(new Evidence.Mark(member, a, Math.min(b, a + limits.maxSnippetLines() - 1), null));
                    }
                }
                if (local.get("declaredAt") instanceof Integer d) {
                    marks.add(new Evidence.Mark(member, d, d, null));
                }
                for (Object x : (List<?>) local.get("reassignedAt")) {
                    marks.add(new Evidence.Mark(member, (Integer) x, (Integer) x,
                            new Evidence.Label("reassigns " + variable)));
                }
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!"laterUsesOfLocal".equals(e.getKey())) {
                    collect(e.getValue(), member, fromLine, marks);
                }
            }
        } else if (value instanceof List<?> list) {
            list.forEach(x -> collect(x, member, fromLine, marks));
        }
    }

    /** The short note shown on the evidence line. */
    private static String label(String kind, Map<String, Object> details) {
        return switch (kind) {
            case "WARNING_SITE" -> "warning";
            case "METHOD_CALL", "OBJECT_CREATION" -> "call";
            case "THIS_CALL" -> "this(...) call";
            case "SUPER_CALL" -> "super(...) call";
            case "IMPLICIT_SUPER_CALL" -> "implicit super() call";
            case "ANONYMOUS_CLASS_CREATION" -> "anonymous subclass creation";
            case "METHOD_REFERENCE" -> "method reference";
            case "PARAMETER_USE" -> "param " + details.get("parameter");
            case "PARAMETER_REASSIGNED" -> "param " + details.get("parameter") + " reassigned";
            case "RETURN" -> "return";
            case "OVERRIDES" -> "overridden by the target";
            case "OVERRIDDEN_BY" -> "overrides the target";
            case "INITIALIZER" -> "initializer";
            case "ASSIGNMENT" -> "write";
            case "COMPOUND_ASSIGNMENT" -> "write (compound)";
            case "INCREMENT_OR_DECREMENT" -> "write (++/--)";
            case "READ" -> "read";
            default -> kind.toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** Declared annotations that the snippet does not show (callee parameters, assigned variables, ...). */
    static List<String> annotationNotes(Map<String, Object> details) {
        Set<String> notes = new LinkedHashSet<>();
        annotationNotes(details, notes);
        return List.copyOf(notes);
    }

    private static void annotationNotes(Object value, Set<String> notes) {
        if (value instanceof Map<?, ?> m) {
            note(m, "parameterAnnotations", "param " + m.get("parameter") + " of " + m.get("callee"), notes);
            note(m, "variableAnnotations", String.valueOf(m.get("variable")), notes);
            note(m, "methodAnnotations", String.valueOf(m.containsKey("from") ? m.get("from") : m.get("method")), notes);
            note(m, "calleeAnnotations", m.get("callee") + " returns", notes);
            note(m, "annotations", String.valueOf(m.containsKey("variable") ? m.get("variable") : m.get("name")), notes);
            // Later uses of a local are on other lines; their annotations are not notes for this line.
            m.forEach((k, v) -> {
                if (!"laterUsesOfLocal".equals(k)) {
                    annotationNotes(v, notes);
                }
            });
        } else if (value instanceof List<?> list) {
            list.forEach(v -> annotationNotes(v, notes));
        }
    }

    private static void note(Map<?, ?> m, String key, String subject, Set<String> notes) {
        if (m.get(key) instanceof List<?> annotations && !annotations.isEmpty()
                && annotations.getFirst() instanceof String) {
            notes.add(subject + ": " + String.join(" ", annotations.stream().map(String::valueOf).toList()));
        }
    }

    // ---------------------------------------------------------------- warning

    private static Map<String, Object> warning(WarningTarget target, Optional<WarningNode> node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("location", target.location());
        node.ifPresent(n -> {
            Diagnostic d = n.diagnostic();
            m.put("checker", d.checkerName().orElse(null));
            m.put("severity", d.severity().name());
            m.put("message", d.message());
        });
        return m;
    }

    /** The statement at {@code path:line[:column]} (path relative to the snapshot). */
    private Optional<Evidence> warningSite(String location) {
        String[] parts = location.split(":");
        int line;
        int column = -1;
        String file;
        try {
            if (parts.length >= 3 && parts[parts.length - 1].matches("\\d+") && parts[parts.length - 2].matches("\\d+")) {
                column = Integer.parseInt(parts[parts.length - 1]);
                line = Integer.parseInt(parts[parts.length - 2]);
                file = location.substring(0, location.length() - parts[parts.length - 1].length()
                        - parts[parts.length - 2].length() - 2);
            } else if (parts.length >= 2 && parts[parts.length - 1].matches("\\d+")) {
                line = Integer.parseInt(parts[parts.length - 1]);
                file = location.substring(0, location.length() - parts[parts.length - 1].length() - 1);
            } else {
                return Optional.empty();
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        Optional<SourceModel.Unit> unit = model.unit(file);
        if (unit.isEmpty() || line < 1 || line > unit.get().lineCount()) {
            return Optional.empty();
        }
        SourceModel.Unit u = unit.get();
        String lineText = u.lineText(line);
        int col = column;
        if (col < 1) {
            col = 1;
            while (col <= lineText.length() && Character.isWhitespace(lineText.charAt(col - 1))) {
                col++;
            }
        }
        long offset = u.lineStarts()[line - 1] + col - 1L;
        TreePath deepest = deepestAt(u.tree(), offset);
        if (deepest == null) {
            return Optional.empty();
        }
        TreePath site = deepest;
        while (site.getParentPath() != null && site.getParentPath().getLeaf() instanceof ExpressionTree
                && model.start(u.tree(), site.getParentPath().getLeaf()) == offset) {
            site = site.getParentPath();
        }
        List<LocalDefinition> defs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element e = sites.element(getCurrentPath());
                if (e != null && (e.getKind() == ElementKind.LOCAL_VARIABLE || e.getKind() == ElementKind.RESOURCE_VARIABLE)
                        && seen.size() < 3 && seen.add(e.getSimpleName().toString())) {
                    defs.addAll(sites.localDefinitions(e, getCurrentPath()));
                }
                return null;
            }
        }.scan(site, null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("treeKind", site.getLeaf().getKind().name());
        return Optional.of(evidence("WARNING_SITE", site, SiteAnalyzer.collapse(sites.text(site)), details, Set.of(),
                defs));
    }

    private TreePath deepestAt(CompilationUnitTree unit, long offset) {
        TreePath[] best = {null};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree == null) {
                    return null;
                }
                long s = model.start(unit, tree);
                long e = model.end(unit, tree);
                if (s < 0 || e <= s || offset < s || offset >= e) {
                    return s < 0 || e <= s ? super.scan(tree, unused) : null;
                }
                best[0] = new TreePath(getCurrentPath() == null ? new TreePath(unit) : getCurrentPath(), tree);
                return super.scan(tree, unused);
            }
        }.scan(new TreePath(unit), null);
        return best[0];
    }

    // ---------------------------------------------------------------- reference matching

    /** Finds references whose compiler symbol is the target; reports same-name references it cannot trust. */
    private final class References extends TreePathScanner<Void, Void> {
        final Element target;
        final MemberKind kind;
        final String name;
        final TypeElement owner;
        final List<TreePath> matches = new ArrayList<>();
        final Set<Tree> implicit = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Evidence> unresolved = new ArrayList<>();
        int excluded;

        References(Element target, MemberKind kind) {
            this.target = target;
            this.kind = kind;
            this.owner = (TypeElement) target.getEnclosingElement();
            this.name = kind == MemberKind.CONSTRUCTOR ? owner.getSimpleName().toString()
                    : target.getSimpleName().toString();
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            return null;
        }

        @Override
        public Void visitPackage(PackageTree node, Void unused) {
            return null;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (kind != MemberKind.FIELD) {
                TreePath path = getCurrentPath();
                Element e = sites.element(path);
                String invoked = invokedName(node.getMethodSelect());
                boolean candidate = kind == MemberKind.METHOD ? invoked.equals(name)
                        : (invoked.equals("this") || invoked.equals("super")) && constructorCallOfOwner(path, invoked);
                if (e instanceof ExecutableElement ee && sameMethod(ee, (ExecutableElement) target)) {
                    if (model.end(path.getCompilationUnit(), node) < 0) {
                        TreePath anonymous = anonymousCreation(path);
                        if (anonymous != null) {
                            matches.add(anonymous);
                        } else {
                            matches.add(path);
                            implicit.add(node);
                        }
                    } else {
                        trustOrReport(path, node.getArguments(), "call");
                    }
                } else if (candidate && model.end(path.getCompilationUnit(), node) >= 0) {
                    otherOrUnresolved(path, e, node.getArguments(), "call to a method");
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            if (kind == MemberKind.CONSTRUCTOR) {
                TreePath path = getCurrentPath();
                Element e = sites.element(path);
                boolean candidate = simpleName(node.getIdentifier()).equals(name);
                if (node.getClassBody() != null) {
                    // An anonymous class: its generated constructor's super(...) call is matched instead.
                    if (candidate && (e == null || SiteAnalyzer.isErroneous(e))) {
                        report(path, "UNRESOLVED_CALL", "javac could not resolve this anonymous class creation",
                                errorsAt(path, node.getArguments()));
                    }
                } else if (e instanceof ExecutableElement ee && sameMethod(ee, (ExecutableElement) target)) {
                    trustOrReport(path, node.getArguments(), "object creation");
                } else if (candidate) {
                    otherOrUnresolved(path, e, node.getArguments(), "object creation");
                }
            }
            return super.visitNewClass(node, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            if (kind != MemberKind.FIELD) {
                TreePath path = getCurrentPath();
                Element e = sites.element(path);
                boolean candidate = kind == MemberKind.METHOD ? node.getName().contentEquals(name)
                        : node.getName().contentEquals("<init>")
                                && simpleName(node.getQualifierExpression()).equals(name);
                if (e instanceof ExecutableElement ee && sameMethod(ee, (ExecutableElement) target)) {
                    trustOrReport(path, List.of(), "method reference");
                } else if (candidate) {
                    if (!(e instanceof ExecutableElement)) {
                        report(path, "UNRESOLVED_METHOD_REFERENCE", "javac could not resolve this method reference",
                                errorsAt(path, List.of()));
                    } else {
                        excluded++;
                    }
                }
            }
            return super.visitMemberReference(node, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            if (kind == MemberKind.FIELD && node.getName().contentEquals(name)) {
                variableReference(getCurrentPath());
            }
            return super.visitIdentifier(node, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            if (kind == MemberKind.FIELD && node.getIdentifier().contentEquals(name)) {
                variableReference(getCurrentPath());
            }
            return super.visitMemberSelect(node, unused);
        }

        private void variableReference(TreePath path) {
            Tree parent = path.getParentPath().getLeaf();
            if (parent instanceof MethodInvocationTree mi && mi.getMethodSelect() == path.getLeaf()) {
                return; // a method name, not a variable
            }
            if (model.end(path.getCompilationUnit(), path.getLeaf()) < 0) {
                return;
            }
            Element e = sites.element(path);
            if (target.equals(e)) {
                trustOrReport(path, List.of(), "field access");
            } else if (e == null || SiteAnalyzer.isErroneous(e)
                    || (e instanceof PackageElement && !errorsAt(path, List.of()).isEmpty())) {
                report(path, "UNRESOLVED_NAME", "javac could not resolve this name to a variable",
                        errorsAt(path, List.of()));
            } else if (e instanceof VariableElement) {
                excluded++;
            }
        }

        private void trustOrReport(TreePath path, List<? extends Tree> arguments, String what) {
            List<CompilerError> errors = errorsAt(path, arguments);
            if (!errors.isEmpty()) {
                report(path, "RESOLVED_DESPITE_ERROR", "javac reported an error at this " + what + "; the symbol it "
                        + "chose may be an error-recovery guess", errors);
            } else if (hasErroneousArgument(path, arguments) && otherOverloadAccepts(arguments.size())) {
                report(path, "RESOLVED_DESPITE_ERROR", "an argument has an erroneous type and another overload "
                        + "accepts " + arguments.size() + " argument(s), so javac's overload choice is a guess",
                        errorsAt(path, List.of()));
            } else {
                matches.add(path);
            }
        }

        /** A same-name call that javac did not resolve to the target. */
        private void otherOrUnresolved(TreePath path, Element e, List<? extends ExpressionTree> arguments,
                String what) {
            if (!(e instanceof ExecutableElement)) {
                report(path, "UNRESOLVED_CALL", "javac could not resolve this " + what, errorsAt(path, arguments));
            } else if (hasErroneousArgument(path, arguments) && accepts((ExecutableElement) target, arguments.size())) {
                report(path, "RESOLVED_DESPITE_ERROR", "javac chose " + sites.label(e) + ", but an argument has an "
                        + "erroneous type and the target also accepts " + arguments.size() + " argument(s)",
                        errorsAt(path, List.of()));
            } else {
                excluded++;
            }
        }

        private boolean hasErroneousArgument(TreePath call, List<? extends Tree> arguments) {
            for (Tree a : arguments) {
                TypeMirror t = model.trees().getTypeMirror(new TreePath(call, a));
                if (t != null && t.getKind() == TypeKind.ERROR) {
                    return true;
                }
            }
            return false;
        }

        private boolean otherOverloadAccepts(int count) {
            for (Element m : model.elements().getAllMembers(owner)) {
                if (m instanceof ExecutableElement other && !sameMethod(other, (ExecutableElement) target)
                        && other.getKind() == target.getKind()
                        && other.getSimpleName().equals(target.getSimpleName()) && accepts(other, count)) {
                    return true;
                }
            }
            return false;
        }

        private void report(TreePath path, String kind, String reason, List<CompilerError> errors) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", reason);
            details.put("compilerErrors", errors.stream().map(e -> "line " + e.line() + ": " + e.message()).toList());
            ExpressionTree receiver = switch (path.getLeaf()) {
                case MethodInvocationTree mi when mi.getMethodSelect() instanceof MemberSelectTree ms -> ms.getExpression();
                case MemberSelectTree ms -> ms.getExpression();
                case MemberReferenceTree mr -> mr.getQualifierExpression();
                default -> null;
            };
            if (receiver != null) {
                TypeMirror type = model.trees().getTypeMirror(TreePath.getPath(path, receiver));
                details.put("receiverTypeUnresolved", type == null || type.getKind() == TypeKind.ERROR);
            }
            unresolved.add(evidence(kind, path, SiteAnalyzer.collapse(sites.text(path)), details, Set.of(), List.of()));
        }

        /** Whether an unresolved or other-symbol {@code this(...)}/{@code super(...)} could be a call of the target's class. */
        private boolean constructorCallOfOwner(TreePath call, String invoked) {
            for (TreePath p = call; p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof ClassTree ct && sites.element(p) instanceof TypeElement cls) {
                    if (invoked.equals("this")) {
                        return cls.equals(owner);
                    }
                    TypeMirror superclass = cls.getSuperclass();
                    if (superclass.getKind() == TypeKind.DECLARED) {
                        return ((DeclaredType) superclass).asElement().equals(owner);
                    }
                    return ct.getExtendsClause() != null && simpleName(ct.getExtendsClause()).equals(name);
                }
            }
            return false;
        }
    }

    private static boolean accepts(ExecutableElement method, int count) {
        int n = method.getParameters().size();
        return count == n || (method.isVarArgs() && count >= n - 1);
    }

    /** The {@code new} expression whose anonymous class contains the generated constructor holding {@code call}. */
    private static TreePath anonymousCreation(TreePath call) {
        for (TreePath p = call; p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof MethodTree) {
                TreePath cls = p.getParentPath();
                TreePath creation = cls == null ? null : cls.getParentPath();
                if (creation != null && creation.getLeaf() instanceof NewClassTree nc
                        && nc.getClassBody() == cls.getLeaf()) {
                    return creation;
                }
                return null;
            }
        }
        return null;
    }

    /** Same method symbol; also accepts javac's per-call copies (diamond inference) of one declaration. */
    private boolean sameMethod(ExecutableElement a, ExecutableElement b) {
        if (a.equals(b)) {
            return true;
        }
        if (!a.getEnclosingElement().equals(b.getEnclosingElement()) || !a.getSimpleName().equals(b.getSimpleName())
                || a.getParameters().size() != b.getParameters().size()) {
            return false;
        }
        for (int i = 0; i < a.getParameters().size(); i++) {
            if (!model.types().isSameType(model.types().erasure(a.getParameters().get(i).asType()),
                    model.types().erasure(b.getParameters().get(i).asType()))) {
                return false;
            }
        }
        return true;
    }

    private static String invokedName(ExpressionTree select) {
        return switch (select) {
            case IdentifierTree id -> id.getName().toString();
            case MemberSelectTree ms -> ms.getIdentifier().toString();
            default -> "";
        };
    }

    private static String simpleName(Tree type) {
        return switch (type) {
            case IdentifierTree id -> id.getName().toString();
            case MemberSelectTree ms -> ms.getIdentifier().toString();
            case ParameterizedTypeTree pt -> simpleName(pt.getType());
            case null, default -> "";
        };
    }

    /** Compiler errors positioned inside {@code path}'s tree but outside the given argument subtrees. */
    private List<CompilerError> errorsAt(TreePath path, List<? extends Tree> arguments) {
        CompilationUnitTree cu = path.getCompilationUnit();
        long s = model.start(cu, path.getLeaf());
        long e = model.end(cu, path.getLeaf());
        if (s < 0 || e <= s) {
            return List.of();
        }
        List<CompilerError> found = new ArrayList<>();
        for (CompilerError error : model.errors(model.unit(cu).relativePath())) {
            long pos = error.position() >= 0 ? error.position() : error.start();
            if (pos < s || pos >= e) {
                continue;
            }
            boolean inArgument = arguments.stream().anyMatch(a -> pos >= model.start(cu, a) && pos < model.end(cu, a));
            if (!inArgument) {
                found.add(error);
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- method and constructor evidence

    private Evidence callSite(TreePath call, ExecutableElement target, boolean implicitCall) {
        Set<Observation> obs = new LinkedHashSet<>();
        List<LocalDefinition> defs = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        List<? extends ExpressionTree> args;
        String callKind;
        if (call.getLeaf() instanceof NewClassTree nc) {
            args = nc.getArguments();
            callKind = nc.getClassBody() != null ? "ANONYMOUS_CLASS_CREATION" : "OBJECT_CREATION";
        } else {
            MethodInvocationTree mi = (MethodInvocationTree) call.getLeaf();
            args = mi.getArguments();
            String invoked = invokedName(mi.getMethodSelect());
            callKind = implicitCall ? "IMPLICIT_SUPER_CALL"
                    : invoked.equals("this") ? "THIS_CALL" : invoked.equals("super") ? "SUPER_CALL" : "METHOD_CALL";
            if (mi.getMethodSelect() instanceof MemberSelectTree ms) {
                TreePath receiver = SiteAnalyzer.child(SiteAnalyzer.child(call, ms), ms.getExpression());
                details.put("receiver", sites.describe(receiver, new LinkedHashSet<>(), defs));
            }
        }
        if (implicitCall) {
            obs.add(Observation.IMPLICIT_CALL);
        }
        details.put("callKind", callKind);
        List<Map<String, Object>> arguments = new ArrayList<>();
        List<? extends VariableElement> params = target.getParameters();
        for (int i = 0; i < args.size(); i++) {
            TreePath argPath = SiteAnalyzer.child(call, args.get(i));
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("index", i);
            if (!params.isEmpty()) {
                VariableElement p = params.get(Math.min(i, params.size() - 1));
                a.put("parameter", p.getSimpleName().toString());
                if (target.isVarArgs() && i >= params.size() - 1) {
                    a.put("varargs", true);
                }
            }
            Set<Observation> argObs = new LinkedHashSet<>();
            a.putAll(sites.describe(argPath, argObs, defs));
            if (argObs.contains(Observation.NULL_LITERAL)) {
                obs.add(Observation.NULL_ARGUMENT);
            }
            if (argObs.contains(Observation.CONDITIONAL_WITH_NULL_BRANCH)) {
                obs.add(Observation.CONDITIONAL_WITH_NULL_BRANCH);
            }
            TypeMirror type = model.trees().getTypeMirror(argPath);
            if (type != null && type.getKind() == TypeKind.ERROR) {
                obs.add(Observation.ERRONEOUS_ARGUMENT_TYPES);
                a.put("erroneousType", true);
            }
            Element argElement = sites.element(SiteAnalyzer.stripParens(argPath));
            if (argElement instanceof VariableElement) {
                TreePath ref = SiteAnalyzer.stripParens(argPath);
                List<Map<String, Object>> guards = sites.nullGuards(call, new VarRef(argElement, sites.receiverOf(ref)));
                if (!guards.isEmpty()) {
                    a.put("nullGuards", guards);
                    obs.add(Observation.GUARDED_BY_NULL_CHECK);
                }
            }
            arguments.add(a);
        }
        details.put("arguments", arguments);
        boolean producesValue = callKind.equals("OBJECT_CREATION") || callKind.equals("ANONYMOUS_CLASS_CREATION")
                || (callKind.equals("METHOD_CALL") && target.getReturnType().getKind() != TypeKind.VOID);
        if (producesValue) {
            details.put("resultUse", sites.consumption(call, obs, true));
        } else if (callKind.equals("METHOD_CALL")) {
            details.put("resultUse", Map.of("kind", "VOID_METHOD"));
        }
        String expression = implicitCall ? "super() (inserted by the compiler)"
                : SiteAnalyzer.collapse(sites.text(call));
        return evidence(callKind, call, expression, details, obs, defs);
    }

    private Evidence methodReference(TreePath ref) {
        Set<Observation> obs = new LinkedHashSet<>();
        Map<String, Object> details = new LinkedHashMap<>();
        TypeMirror type = model.trees().getTypeMirror(ref);
        details.put("functionalInterface", type == null ? null : SiteAnalyzer.simpleTypes(type));
        details.put("referenceUse", sites.consumption(ref, obs, false));
        return evidence("METHOD_REFERENCE", ref, SiteAnalyzer.collapse(sites.text(ref)), details, obs, List.of());
    }

    private List<Evidence> parameterUses(TreePath memberPath, ExecutableElement method) {
        MethodTree tree = (MethodTree) memberPath.getLeaf();
        if (tree.getBody() == null) {
            return List.of();
        }
        Map<Element, Integer> params = new LinkedHashMap<>();
        for (int i = 0; i < tree.getParameters().size(); i++) {
            Element p = sites.element(SiteAnalyzer.child(memberPath, tree.getParameters().get(i)));
            if (p != null) {
                params.put(p, i);
            }
        }
        List<Evidence> uses = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                TreePath use = getCurrentPath();
                Element e = sites.element(use);
                Integer index = e == null ? null : params.get(e);
                if (index != null) {
                    uses.add(parameterUse(use, e, index));
                }
                return null;
            }
        }.scan(SiteAnalyzer.child(memberPath, tree.getBody()), null);
        return uses;
    }

    private Evidence parameterUse(TreePath use, Element param, int index) {
        Set<Observation> obs = new LinkedHashSet<>();
        List<LocalDefinition> defs = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("parameter", param.getSimpleName().toString());
        details.put("parameterIndex", index);
        Tree parent = use.getParentPath().getLeaf();
        String kind;
        if (parent instanceof AssignmentTree a && a.getVariable() == use.getLeaf()) {
            kind = "PARAMETER_REASSIGNED";
            details.put("assigned", sites.describe(SiteAnalyzer.child(use.getParentPath(), a.getExpression()), obs,
                    defs));
        } else {
            kind = "PARAMETER_USE";
            details.put("use", sites.consumption(use, obs, true));
            List<Map<String, Object>> guards = sites.nullGuards(use, new VarRef(param, ""));
            if (!guards.isEmpty()) {
                details.put("nullGuards", guards);
                obs.add(Observation.GUARDED_BY_NULL_CHECK);
            }
        }
        return evidence(kind, use, param.getSimpleName().toString(), details, obs, defs);
    }

    private List<Evidence> returns(TreePath memberPath) {
        MethodTree tree = (MethodTree) memberPath.getLeaf();
        if (tree.getBody() == null) {
            return List.of();
        }
        List<Evidence> out = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitReturn(ReturnTree node, Void unused) {
                if (node.getExpression() != null) {
                    TreePath ret = getCurrentPath();
                    TreePath expr = SiteAnalyzer.child(ret, node.getExpression());
                    Set<Observation> obs = new LinkedHashSet<>();
                    obs.add(Observation.RETURNED);
                    List<LocalDefinition> defs = new ArrayList<>();
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("returned", sites.describe(expr, obs, defs));
                    Element e = sites.element(SiteAnalyzer.stripParens(expr));
                    if (e instanceof VariableElement) {
                        List<Map<String, Object>> guards = sites.nullGuards(ret,
                                new VarRef(e, sites.receiverOf(SiteAnalyzer.stripParens(expr))));
                        if (!guards.isEmpty()) {
                            details.put("nullGuards", guards);
                            obs.add(Observation.GUARDED_BY_NULL_CHECK);
                        }
                    }
                    out.add(evidence("RETURN", ret, SiteAnalyzer.collapse(sites.text(expr)), details, obs, defs));
                }
                return super.visitReturn(node, unused);
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                return null; // returns inside lambdas do not return from the target
            }

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                return null;
            }
        }.scan(SiteAnalyzer.child(memberPath, tree.getBody()), null);
        return out;
    }

    private List<Evidence> overrides(ExecutableElement method) {
        if (!SiteAnalyzer.overridable(method)) {
            return List.of();
        }
        TypeElement owner = (TypeElement) method.getEnclosingElement();
        List<Evidence> out = new ArrayList<>();
        Set<Element> seenTypes = new LinkedHashSet<>();
        List<TypeMirror> queue = new ArrayList<>(model.types().directSupertypes(owner.asType()));
        while (!queue.isEmpty()) {
            TypeMirror t = queue.removeFirst();
            if (t.getKind() != TypeKind.DECLARED) {
                continue;
            }
            TypeElement type = (TypeElement) ((DeclaredType) t).asElement();
            if (!seenTypes.add(type)) {
                continue;
            }
            for (Element m : type.getEnclosedElements()) {
                if (m instanceof ExecutableElement other && other.getSimpleName().equals(method.getSimpleName())
                        && model.elements().overrides(method, other, owner)) {
                    out.add(overrideEvidence("OVERRIDES", other));
                }
            }
            queue.addAll(model.types().directSupertypes(t));
        }
        for (SourceModel.Unit u : model.units()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    if (node.getName().equals(method.getSimpleName())
                            && sites.element(getCurrentPath()) instanceof ExecutableElement other
                            && other.getEnclosingElement() instanceof TypeElement otherOwner
                            && !other.equals(method) && model.elements().overrides(other, method, otherOwner)) {
                        out.add(overrideEvidence("OVERRIDDEN_BY", other));
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(u.tree(), null);
        }
        return out;
    }

    private Evidence overrideEvidence(String kind, ExecutableElement other) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", sites.label(other));
        details.put("methodAnnotations", sites.annotations(other));
        List<Map<String, Object>> params = new ArrayList<>();
        for (VariableElement p : other.getParameters()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.getSimpleName().toString());
            pm.put("annotations", sites.annotations(p));
            params.add(pm);
        }
        details.put("parameters", params);
        Optional<TreePath> declaration = model.declaration(other);
        if (declaration.isEmpty()) {
            details.put("declaredIn", "class file (no source in the snapshot)");
            return new Evidence(kind, null, -1, sites.label(other), sites.label(other), null, List.of(), 0, List.of(),
                    details, Set.of(), List.of());
        }
        details.put("declaredIn", "source");
        return evidence(kind, declaration.get(), sites.label(other), details, Set.of(), List.of());
    }

    // ---------------------------------------------------------------- field evidence

    private Evidence initializer(TreePath fieldPath, VariableTree field) {
        Set<Observation> obs = new LinkedHashSet<>();
        List<LocalDefinition> defs = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        TreePath init = SiteAnalyzer.child(fieldPath, field.getInitializer());
        details.put("assigned", sites.describe(init, obs, defs));
        obs.add(Observation.ASSIGNED);
        return evidence("INITIALIZER", init, SiteAnalyzer.collapse(sites.text(init)), details, obs, defs);
    }

    private void fieldAccess(TreePath access, VariableElement field, List<Evidence> writes, List<Evidence> reads) {
        TreePath parentPath = access.getParentPath();
        Tree parent = parentPath.getLeaf();
        Set<Observation> obs = new LinkedHashSet<>();
        List<LocalDefinition> defs = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        String receiver = sites.receiverOf(access);
        details.put("receiver", receiver.isEmpty() ? "this/implicit" : receiver);
        if (parent instanceof AssignmentTree a && a.getVariable() == access.getLeaf()) {
            details.put("assigned", sites.describe(SiteAnalyzer.child(parentPath, a.getExpression()), obs, defs));
            obs.add(Observation.ASSIGNED);
            writes.add(evidence("ASSIGNMENT", parentPath, SiteAnalyzer.collapse(sites.text(parentPath)), details, obs,
                    defs));
        } else if (parent instanceof CompoundAssignmentTree ca && ca.getVariable() == access.getLeaf()) {
            details.put("assigned", sites.describe(SiteAnalyzer.child(parentPath, ca.getExpression()), obs, defs));
            obs.add(Observation.READ_MODIFY_WRITE);
            writes.add(evidence("COMPOUND_ASSIGNMENT", parentPath, SiteAnalyzer.collapse(sites.text(parentPath)),
                    details, obs, defs));
        } else if (parent instanceof UnaryTree u && (u.getKind() == Tree.Kind.POSTFIX_INCREMENT
                || u.getKind() == Tree.Kind.POSTFIX_DECREMENT || u.getKind() == Tree.Kind.PREFIX_INCREMENT
                || u.getKind() == Tree.Kind.PREFIX_DECREMENT)) {
            obs.add(Observation.READ_MODIFY_WRITE);
            writes.add(evidence("INCREMENT_OR_DECREMENT", parentPath, SiteAnalyzer.collapse(sites.text(parentPath)),
                    details, obs, defs));
        } else {
            details.put("use", sites.consumption(access, obs, true));
            List<Map<String, Object>> guards = sites.nullGuards(access, new VarRef(field, receiver));
            if (!guards.isEmpty()) {
                details.put("nullGuards", guards);
                obs.add(Observation.GUARDED_BY_NULL_CHECK);
            }
            reads.add(evidence("READ", access, SiteAnalyzer.collapse(sites.text(access)), details, obs, defs));
        }
    }

    // ---------------------------------------------------------------- identity

    private Map<String, Object> identity(WarningTarget.Resolved r, String file, TreePath memberPath, Element element) {
        CompilationUnitTree cu = memberPath.getCompilationUnit();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("memberKind", r.memberKind().name());
        m.put("target", r.target());
        m.put("enclosingClass", r.enclosingClass());
        m.put("file", file);
        SourceRef at = sites.ref(memberPath);
        m.put("declaredAt", at.toString());
        Tree leaf = memberPath.getLeaf();
        if (leaf instanceof MethodTree method) {
            long start = model.start(cu, method);
            long end = method.getBody() != null ? model.start(cu, method.getBody()) : model.end(cu, method);
            m.put("declaration", collapseLong(model.unit(cu).text().substring((int) start, (int) end)));
            m.put("modifiers", method.getModifiers().getFlags().stream().map(f -> f.toString()).sorted().toList());
            m.put("annotations", sites.annotations(element));
            m.put("returnType", method.getReturnType() == null ? null : model.text(cu, method.getReturnType()));
            List<Map<String, Object>> params = new ArrayList<>();
            for (int i = 0; i < method.getParameters().size(); i++) {
                VariableTree p = method.getParameters().get(i);
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("index", i);
                pm.put("name", p.getName().toString());
                pm.put("type", model.text(cu, p.getType()));
                pm.put("annotations", sites.annotations(sites.element(SiteAnalyzer.child(memberPath, p))));
                params.add(pm);
            }
            m.put("parameters", params);
        } else {
            VariableTree field = (VariableTree) leaf;
            m.put("declaration", collapseLong(model.text(cu, field)));
            m.put("modifiers", field.getModifiers().getFlags().stream().map(f -> f.toString()).sorted().toList());
            m.put("annotations", sites.annotations(element));
            m.put("type", model.text(cu, field.getType()));
            m.put("initializer", field.getInitializer() == null ? null
                    : SiteAnalyzer.collapse(model.text(cu, field.getInitializer())));
        }
        m.put("targetNotes", r.notes());
        return m;
    }

    private static String collapseLong(String text) {
        String s = text.replaceAll("\\s+", " ").strip();
        return s.length() > 400 ? s.substring(0, 399) + "…" : s;
    }
}
