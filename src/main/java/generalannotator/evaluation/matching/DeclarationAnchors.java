package generalannotator.evaluation.matching;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithMembers;
import generalannotator.edits.TypeText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * Finds the structural anchor of a finding: the innermost declaration of one state's source whose
 * range contains the finding's position, identified by its type chain and member signature. Parsed
 * files are cached per instance, so use one instance per repository state.
 */
public final class DeclarationAnchors {

    private final Path root;
    private final Map<String, Optional<CompilationUnit>> parsed = new HashMap<>();
    private final Map<String, String> problems = new HashMap<>();
    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    /** @param root the root of the state's source tree (paths are relative to it) */
    public DeclarationAnchors(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public ObservedFinding.Anchor anchor(String relativePath, int line, OptionalInt column) {
        Optional<CompilationUnit> cu = parsed.computeIfAbsent(relativePath, this::parse);
        if (cu.isEmpty()) {
            return ObservedFinding.Anchor.unresolved(problems.get(relativePath));
        }
        BodyDeclaration<?> best = null;
        for (BodyDeclaration<?> d : cu.get().findAll(BodyDeclaration.class)) {
            Optional<Range> r = d.getRange();
            if (r.isEmpty() || !contains(r.get(), line, column)) {
                continue;
            }
            if (best == null || inside(r.get(), best.getRange().orElseThrow())) {
                best = d;
            }
        }
        if (best == null) {
            return ObservedFinding.Anchor.unresolved("no declaration contains line " + line);
        }
        String pkg = cu.get().getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        return new ObservedFinding.Anchor(pkg + identity(best), best.getBegin().orElseThrow().line, "");
    }

    private Optional<CompilationUnit> parse(String relativePath) {
        Path file = root.resolve(relativePath).normalize();
        try {
            if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().startsWith(root.toRealPath())) {
                problems.put(relativePath, "not a regular source file inside the state");
                return Optional.empty();
            }
            ParseResult<CompilationUnit> r = parser.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!r.isSuccessful() || r.getResult().isEmpty()) {
                problems.put(relativePath, "the file does not parse: " + r.getProblems().stream().findFirst()
                        .map(Object::toString).orElse("unknown problem"));
                return Optional.empty();
            }
            return r.getResult();
        } catch (IOException | RuntimeException e) {
            problems.put(relativePath, "cannot read the file: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean contains(Range r, int line, OptionalInt column) {
        if (column.isEmpty()) {
            return r.begin.line <= line && line <= r.end.line;
        }
        Position p = new Position(line, column.getAsInt());
        return !p.isBefore(r.begin) && !p.isAfter(r.end);
    }

    private static boolean inside(Range inner, Range outer) {
        return !inner.begin.isBefore(outer.begin) && !inner.end.isAfter(outer.end) && !inner.equals(outer);
    }

    /** Type chain and member, for example {@code A.Inner#run()} or {@code A#new Runnable{}#run()}. */
    static String identity(BodyDeclaration<?> declaration) {
        List<String> chain = new ArrayList<>();
        Node member = declaration;
        String self = declaration instanceof TypeDeclaration<?> t ? "#<type " + t.getNameAsString() + ">"
                : "#" + member(declaration);
        for (Node n = declaration instanceof TypeDeclaration<?> ? declaration : declaration.getParentNode().orElse(null);
                n != null; n = n.getParentNode().orElse(null)) {
            if (n instanceof TypeDeclaration<?> t) {
                chain.add(t.getNameAsString());
            } else if (n instanceof ObjectCreationExpr o && o.getAnonymousClassBody().isPresent()) {
                chain.add("new " + TypeText.normalize(o.getType().toString()) + "{}");
            } else if (n instanceof EnumConstantDeclaration e) {
                chain.add(e.getNameAsString() + "{}");
            } else if (n instanceof CallableDeclaration<?> c && n != member) {
                chain.add(member((BodyDeclaration<?>) c));
            }
        }
        Collections.reverse(chain);
        return String.join(".", chain) + self;
    }

    private static String member(BodyDeclaration<?> d) {
        return switch (d) {
            case MethodDeclaration m -> m.getNameAsString() + parameters(m);
            case ConstructorDeclaration c -> "<init>" + parameters(c);
            case FieldDeclaration f -> "field:" + f.getVariables().stream().map(VariableDeclarator::getNameAsString)
                    .collect(Collectors.joining(","));
            case InitializerDeclaration i -> (i.isStatic() ? "<clinit>" : "<init-block>") + "#" + initializerIndex(i);
            case EnumConstantDeclaration e -> "enum:" + e.getNameAsString();
            case AnnotationMemberDeclaration a -> a.getNameAsString() + "()";
            default -> d.getClass().getSimpleName();
        };
    }

    private static String parameters(CallableDeclaration<?> c) {
        List<String> types = new ArrayList<>();
        for (Parameter p : c.getParameters()) {
            types.add(TypeText.normalize(p.getType().toString()) + (p.isVarArgs() ? "[]" : ""));
        }
        return "(" + String.join(",", types) + ")";
    }

    private static int initializerIndex(InitializerDeclaration i) {
        if (i.getParentNode().orElse(null) instanceof NodeWithMembers<?> owner) {
            int index = 0;
            for (BodyDeclaration<?> m : owner.getMembers()) {
                if (m == i) {
                    return index;
                }
                if (m instanceof InitializerDeclaration) {
                    index++;
                }
            }
        }
        return 0;
    }
}
