package generalannotator.slicing;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandSpec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Builds a fake Specimin checkout and simulates its installDist build, for tests. */
public final class FakeSpecimin {

    public static final String MAIN_CLASS = "org.checkerframework.specimin.SpeciminRunner";

    private FakeSpecimin() {}

    /** Creates {@code dir} with an executable gradlew and a build.gradle. */
    public static Path checkout(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path gradlew = Files.writeString(dir.resolve("gradlew"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(dir.resolve("build.gradle"), "plugins { id(\"application\") }\n");
        return dir;
    }

    /** What installDist produces: build/install/specimin/lib with specimin.jar (Main-Class) and a dependency jar. */
    public static void install(Path home, String mainClass) throws IOException {
        Path lib = Files.createDirectories(home.resolve("build/install/specimin/lib"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        try (OutputStream out = Files.newOutputStream(lib.resolve("specimin.jar"));
                JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.flush();
        }
        try (OutputStream out = Files.newOutputStream(lib.resolve("javaparser-core-3.28.2.jar"));
                JarOutputStream jar = new JarOutputStream(out, new Manifest())) {
            jar.flush();
        }
    }

    public static boolean isInstallDist(CommandSpec spec) {
        return spec.command().getFirst().endsWith("gradlew") && spec.command().contains("installDist");
    }

    public static boolean isGit(CommandSpec spec) {
        return spec.command().getFirst().equals("git");
    }

    public static CommandResult result(CommandSpec spec, int exit, String stdout, String stderr) {
        return new CommandResult(spec, OptionalInt.of(exit), stdout, stderr, Duration.ofMillis(3), false,
                Optional.empty());
    }
}
