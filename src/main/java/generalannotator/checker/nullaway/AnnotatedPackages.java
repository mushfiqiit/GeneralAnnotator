package generalannotator.checker.nullaway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Determines the value of NullAway's {@code AnnotatedPackages} option.
 *
 * <p>NullAway treats a package and all of its sub-packages as annotated when the package is listed.
 * When no value is configured, the scope is derived from the {@code package} declarations of the
 * analyzed source files, reduced to the minimal set of prefixes (a package is dropped when an
 * ancestor package is already listed). Sibling packages are never merged into a common parent.
 */
public final class AnnotatedPackages {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern PACKAGE =
            Pattern.compile("(?:^|[\\s;)])package\\s+([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.\\s]*?)\\s*;");
    private static final Pattern QUALIFIED_NAME =
            Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
                    + "(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*");

    private AnnotatedPackages() {}

    /** The declared package of a compilation unit, or empty for the unnamed package. */
    public static Optional<String> packageOf(String source) {
        String stripped = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
        Matcher m = PACKAGE.matcher(stripped);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(m.group(1).replaceAll("\\s+", ""));
    }

    /** Minimal set of package prefixes covering {@code packages}, sorted. */
    public static List<String> minimalPrefixes(Collection<String> packages) {
        List<String> result = new ArrayList<>();
        for (String pkg : new TreeSet<>(packages)) {
            boolean covered = result.stream().anyMatch(p -> pkg.equals(p) || pkg.startsWith(p + "."));
            if (!covered) {
                result.add(pkg);
            }
        }
        return result;
    }

    /**
     * Parses a configured comma-separated list.
     *
     * @return the packages, or a list of problems via {@link IllegalArgumentException}
     */
    public static List<String> parseConfigured(String value) {
        List<String> packages = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String part : value.split(",")) {
            String pkg = part.strip();
            if (pkg.isEmpty()) {
                continue;
            }
            if (QUALIFIED_NAME.matcher(pkg).matches()) {
                packages.add(pkg);
            } else {
                invalid.add("'" + pkg + "'");
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("invalid package name(s): " + String.join(", ", invalid));
        }
        if (packages.isEmpty()) {
            throw new IllegalArgumentException("no package names given");
        }
        return packages;
    }
}
