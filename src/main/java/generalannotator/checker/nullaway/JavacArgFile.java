package generalannotator.checker.nullaway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes javac {@code @argfiles}. Every argument is double-quoted on its own line, with backslashes
 * and double quotes escaped, so paths with spaces survive. ({@code -J} options are not allowed in
 * argument files and must be passed on the command line.)
 */
public final class JavacArgFile {

    private JavacArgFile() {}

    public static String format(List<String> args) {
        StringBuilder out = new StringBuilder();
        for (String arg : args) {
            if (arg.startsWith("-J")) {
                throw new IllegalArgumentException("-J options are not allowed in javac argument files: " + arg);
            }
            out.append('"').append(arg.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
        }
        return out.toString();
    }

    public static void write(Path file, List<String> args) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, format(args), StandardCharsets.UTF_8);
    }
}
