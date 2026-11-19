package generalannotator.prompts;

import generalannotator.context.ContextText;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.prompts.PromptSources.Inputs;
import generalannotator.prompts.PromptSources.SliceFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles one prompt unit from joined inputs. Sections are delimited by marker lines; slice files
 * and the usage context are data blocks whose markers carry a SHA-256 prefix of their content, so
 * text inside a block cannot imitate its end marker. Java files are reproduced exactly, never
 * truncated; a unit over a limit is reported as oversized instead.
 */
public final class PromptAssembler {

    /** An assembled unit with its measurements. */
    public record Rendered(String text, Map<String, Integer> sectionChars, Map<String, Integer> fileChars,
            List<String> oversized) {
        public Rendered {
            sectionChars = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sectionChars));
            fileChars = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fileChars));
            oversized = List.copyOf(oversized);
        }

        public boolean fits() {
            return oversized.isEmpty();
        }
    }

    static final Map<String, String> REPRODUCTION = Map.of(
            "NOT_VALIDATED", "the checker has not been run on this slice, so it is not known whether the slice "
                    + "still produces this warning");

    private PromptAssembler() {}

    public static Rendered assemble(Inputs in, PromptLimits limits) {
        String id = in.warning().id();
        Map<String, Integer> sections = new LinkedHashMap<>();
        Map<String, Integer> files = new LinkedHashMap<>();
        List<String> oversized = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        out.append("GeneralAnnotator prompt unit | template ").append(PromptTemplate.VERSION).append(" | run ")
                .append(in.runId()).append(" | warning ").append(id).append("\n\n");

        String instructions = PromptTemplate.instructions(in.checkerId(), id, in.target().sourceRoot());
        section(out, "INSTRUCTIONS", instructions);
        sections.put("instructions", instructions.length());

        String warning = warning(in);
        section(out, "WARNING", warning);
        sections.put("warning", warning.length());

        StringBuilder slice = new StringBuilder();
        slice.append("Specimin slice of the target member: ").append(in.files().size())
                .append(in.files().size() == 1 ? " Java file" : " Java files")
                .append(", paths relative to the slice root, shown verbatim without line numbers.\n");
        int n = 1;
        for (SliceFile f : in.files()) {
            String tag = "FILE " + n + "/" + in.files().size() + ": " + f.path() + " [sha256:" + hash(f.content()) + "]";
            String content = f.content().endsWith("\n") ? f.content() : f.content() + "\n";
            ensureNoMarker(content, "----- END " + tag);
            slice.append("\n----- BEGIN ").append(tag).append(" -----\n").append(content)
                    .append("----- END ").append(tag).append(" -----\n");
            files.put(f.path(), f.content().length());
            if (f.content().length() > limits.maxFileChars()) {
                oversized.add("slice file " + f.path() + " has " + f.content().length() + " characters (limit "
                        + limits.maxFileChars() + ")");
            }
            n++;
        }
        section(out, "SLICE", slice.toString());
        sections.put("slice", slice.length());

        String contextTag = "USAGE CONTEXT [sha256:" + hash(in.context()) + "]";
        ensureNoMarker(in.context(), "----- END " + contextTag);
        String context = "Evidence from the ORIGINAL project (read-only). Line numbers are original source line "
                + "numbers, not slice line numbers.\n\n----- BEGIN " + contextTag + " -----\n" + in.context()
                + "----- END " + contextTag + " -----\n";
        section(out, "USAGE CONTEXT", context);
        sections.put("usageContext", context.length());

        String response = PromptTemplate.responseFormat(id);
        section(out, "RESPONSE FORMAT", response);
        sections.put("responseFormat", response.length());
        out.append("END OF PROMPT UNIT ").append(id).append('\n');

        if (out.length() > limits.maxPromptChars()) {
            oversized.add("the prompt unit has " + out.length() + " characters (limit " + limits.maxPromptChars()
                    + ")");
        }
        return new Rendered(out.toString(), sections, files, oversized);
    }

    private static String warning(Inputs in) {
        Diagnostic d = in.warning().diagnostic();
        String location = PromptSources.location(in.warning());
        String reproduction = in.reproduction();
        StringBuilder s = new StringBuilder();
        s.append("Warning ID: ").append(in.warning().id()).append('\n');
        s.append("Checker: ").append(d.checkerName().orElse(in.checkerId())).append(" (").append(d.severity())
                .append(")\n");
        s.append("Message: ").append(ContextText.stripUrls(d.message())).append('\n');
        s.append("Original location: ").append(location)
                .append(" (path in the original project; line and column in the original file, not in the slice)\n");
        s.append("Target member: ").append(in.target().memberKind()).append(' ').append(in.target().target())
                .append('\n');
        s.append("Target file: ").append(in.target().targetFile()).append(" (slice-relative; source root ")
                .append(in.target().sourceRoot()).append(")\n");
        s.append("Slice reproduction: ").append(reproduction);
        if (REPRODUCTION.containsKey(reproduction)) {
            s.append(" (").append(REPRODUCTION.get(reproduction)).append(')');
        }
        s.append('\n');
        return s.toString();
    }

    private static void section(StringBuilder out, String name, String body) {
        out.append("===== BEGIN ").append(name).append(" =====\n").append(body);
        if (!body.endsWith("\n")) {
            out.append('\n');
        }
        out.append("===== END ").append(name).append(" =====\n\n");
    }

    private static void ensureNoMarker(String content, String marker) {
        if (content.contains(marker)) {
            throw new IllegalArgumentException("the content contains its own end marker '" + marker + "'");
        }
    }

    static String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
