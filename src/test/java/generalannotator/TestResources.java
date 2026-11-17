package generalannotator;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads text fixtures from the test classpath. */
public final class TestResources {

    private TestResources() {}

    public static String text(String name) {
        try (InputStream in = TestResources.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing test resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
