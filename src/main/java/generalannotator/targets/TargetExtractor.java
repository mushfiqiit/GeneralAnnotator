package generalannotator.targets;

import generalannotator.diagnostics.Diagnostic;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningNode;
import generalannotator.targets.MemberLocator.Found;
import generalannotator.targets.MemberLocator.Location;
import generalannotator.targets.MemberLocator.NotFound;
import generalannotator.targets.ParsedSource.ParseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Determines the Specimin target member of every warning node of a graph, against the isolated
 * working copy that produced the warnings. Specimin is not run.
 *
 * <p>The file is located as {@code workingCopyRoot/targetPath}. Its Java package root
 * (Specimin's {@code --root}) is derived from its {@code package} declaration and must match the
 * file's directory; a mismatch is reported as unresolved rather than guessed.
 */
public final class TargetExtractor {

    private final Path workingCopyRoot;
    private final List<Path> configuredSourceRoots;
    private final OptionalInt release;
    private final Map<Path, Object> parsed = new HashMap<>();

    /**
     * @param workingCopyRoot root of the isolated copy that produced the warnings
     * @param configuredSourceRoots configured source roots, relative to the target root
     * @param release javac {@code --release} used for parsing, if configured
     */
    public TargetExtractor(Path workingCopyRoot, List<Path> configuredSourceRoots, OptionalInt release) {
        this.workingCopyRoot = workingCopyRoot;
        this.configuredSourceRoots = List.copyOf(configuredSourceRoots);
        this.release = release;
    }

    public List<WarningTarget> extract(WarningGraph graph) {
        List<WarningTarget> targets = new ArrayList<>();
        for (WarningNode node : graph.warningNodes()) {
            targets.add(extract(node));
        }
        return targets;
    }

    public WarningTarget extract(WarningNode node) {
        Diagnostic d = node.diagnostic();
        String location = node.targetPath().orElse(d.sourcePath()) + ":" + d.line()
                + (d.column().isPresent() ? ":" + d.column().getAsInt() : "");
        if (node.targetPath().isEmpty()) {
            return unresolved(node, location, "the diagnostic's file is outside the isolated working copy");
        }
        Path relative = Path.of(node.targetPath().get());
        Path file = workingCopyRoot.resolve(relative);
        if (!Files.isRegularFile(file)) {
            return unresolved(node, location, "source file " + file + " does not exist in the isolated working copy");
        }
        Object parse = parsed.computeIfAbsent(file, f -> {
            try {
                return ParsedSource.parse(f, release);
            } catch (ParseException e) {
                return e.getMessage();
            }
        });
        if (parse instanceof String failure) {
            return unresolved(node, location, failure);
        }
        ParsedSource source = (ParsedSource) parse;

        Location found = MemberLocator.locate(source, d.line(),
                d.column().isPresent() ? Optional.of(d.column().getAsInt()) : Optional.empty(), d.message());
        if (found instanceof NotFound nf) {
            return new WarningTarget.Unresolved(node.id(), location, nf.reason(), nf.notes());
        }
        Found member = (Found) found;

        String packageName = source.unit().getPackageName() == null ? "" : source.unit().getPackageName().toString();
        List<String> packageSegments = packageName.isEmpty() ? List.of() : List.of(packageName.split("\\."));
        List<String> directorySegments = new ArrayList<>();
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            directorySegments.add(relative.getName(i).toString());
        }
        int rootLength = directorySegments.size() - packageSegments.size();
        if (rootLength < 0 || !directorySegments.subList(rootLength, directorySegments.size()).equals(packageSegments)) {
            return new WarningTarget.Unresolved(node.id(), location, "the file's directory '"
                    + String.join("/", directorySegments) + "' does not end with its package path '"
                    + String.join("/", packageSegments) + "', so Specimin's source root cannot be determined",
                    member.notes());
        }
        List<String> rootSegments = directorySegments.subList(0, rootLength);
        String fileName = relative.getFileName().toString();
        List<String> fileSegments = new ArrayList<>(packageSegments);
        fileSegments.add(fileName);
        String sourceRoot = rootSegments.isEmpty() ? "." : String.join("/", rootSegments);
        String targetFile = String.join("/", fileSegments);
        String fileBase = fileName.replaceFirst("\\.java$", "");
        Optional<String> configured = configuredSourceRoots.stream()
                .filter(relative::startsWith).map(Path::toString).findFirst();

        List<String> notes = new ArrayList<>(member.notes());
        if (configured.isEmpty()) {
            notes.add("the file is not under any configured source root");
        }
        return new WarningTarget.Resolved(node.id(), location, sourceRoot,
                workingCopyRoot.resolve(sourceRoot).normalize().toString(),
                targetFile, member.kind(), member.enclosingClass(), member.target(), configured,
                !member.topLevelClass().equals(fileBase), notes);
    }

    private static WarningTarget unresolved(WarningNode node, String location, String reason) {
        return new WarningTarget.Unresolved(node.id(), location, reason, List.of());
    }
}
