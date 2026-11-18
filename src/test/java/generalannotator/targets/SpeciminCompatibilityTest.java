package generalannotator.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.graph.WarningNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that every resolved target is a string Specimin would match: the member strings are built
 * the way Specimin's TargetMemberFinderVisitor builds them (JavaParser 3.28.2, the version Specimin
 * uses, JAVA_17 language level, getDeclarationAsString(false, false, false) and Specimin's
 * removeMethodReturnTypeAndAnnotationsImpl), and compared with whitespace removed, as Specimin does.
 * Specimin itself is not run.
 */
class SpeciminCompatibilityTest {

    @TempDir Path temp;

    /** Specimin's member strings for members of named (non-local, non-anonymous) classes. */
    private static Set<String> speciminMembers(String source) {
        CompilationUnit cu = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)).parse(source).getResult().orElseThrow();
        Set<String> members = new TreeSet<>();
        for (CallableDeclaration<?> callable : cu.findAll(CallableDeclaration.class)) {
            Optional<String> owner = namedOwner(callable);
            if (owner.isEmpty()) {
                continue;
            }
            String declaration = callable instanceof MethodDeclaration m
                    ? removeMethodReturnTypeAndAnnotationsImpl(m.getDeclarationAsString(false, false, false))
                    : ((ConstructorDeclaration) callable).getDeclarationAsString(false, false, false);
            members.add((owner.get() + "#" + declaration).replaceAll("\\s", ""));
        }
        for (VariableDeclarator v : cu.findAll(VariableDeclarator.class)) {
            if (v.getParentNode().orElseThrow() instanceof FieldDeclaration field) {
                namedOwner(field).ifPresent(owner -> members.add(owner + "#" + v.getNameAsString()));
            }
        }
        return members;
    }

    private static Optional<String> namedOwner(Node member) {
        if (member.getParentNode().orElseThrow() instanceof TypeDeclaration<?> type) {
            return type.getFullyQualifiedName();
        }
        return Optional.empty();
    }

    /** Copied from Specimin's JavaParserUtil (MIT License, Copyright (c) 2021 Martin Kellogg). */
    static String removeMethodReturnTypeAndAnnotationsImpl(String declAsString) {
        List<String> methodParts = Arrays.asList(declAsString.split(" "));
        String filteredMethodDeclaration = methodParts.stream()
                .filter(part -> !part.startsWith("@"))
                .map(part -> part.indexOf('@') == -1 ? part : part.substring(0, part.indexOf('@')))
                .collect(Collectors.joining(" "));
        methodParts = Arrays.asList(
                filteredMethodDeclaration.substring(0, filteredMethodDeclaration.indexOf('(')).split(" "));
        String methodName = methodParts.get(methodParts.size() - 1);
        String methodReturnType = filteredMethodDeclaration.substring(0, filteredMethodDeclaration.indexOf(methodName));
        String methodWithoutReturnType = filteredMethodDeclaration.replace(methodReturnType, "");
        return methodWithoutReturnType.replace("< ", "<");
    }

    @Test
    void everyResolvedFixtureTargetIsASpeciminMemberString() throws Exception {
        String source = TargetExtractorTest.SAMPLE;
        Path copy = temp.resolve("copy");
        Path file = copy.resolve("src/p/q/Sample.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        Set<String> specimin = speciminMembers(source);

        TargetExtractor extractor = new TargetExtractor(copy, List.of(Path.of("src")), OptionalInt.empty());
        Matcher markers = Pattern.compile("/\\*@(\\w+)\\*/").matcher(source);
        int resolved = 0;
        while (markers.find()) {
            int position = markers.end();
            int line = 1 + (int) source.substring(0, position).chars().filter(c -> c == '\n').count();
            int column = position - source.lastIndexOf('\n', position - 1);
            Diagnostic d = new Diagnostic(file.toString(), line, OptionalInt.of(column), Severity.ERROR,
                    Optional.of("NullAway"), "warning", Optional.empty());
            WarningTarget t = extractor.extract(
                    new WarningNode("W" + markers.group(1), d, Optional.of("src/p/q/Sample.java"), true, 0));
            if (t instanceof WarningTarget.Resolved r) {
                resolved++;
                String normalized = r.target().replaceAll("\\s", "");
                assertTrue(specimin.contains(normalized),
                        markers.group(1) + ": " + normalized + " is not among Specimin's members " + specimin);
            }
        }
        assertEquals(17, resolved, "resolvable fixture markers (the others are deliberately unresolved cases)");
    }
}
