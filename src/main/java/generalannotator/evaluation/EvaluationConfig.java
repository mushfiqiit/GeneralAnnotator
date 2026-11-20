package generalannotator.evaluation;

import generalannotator.checker.nullaway.NullAwayChecker;
import generalannotator.config.AnalysisConfig;
import generalannotator.config.ConfigLoader;
import generalannotator.config.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The checker settings shared by an evaluation's baseline and every attempt, and their fingerprint.
 *
 * <p>Settings come from the same environment variables as {@code check} ({@code GA_CHECKER},
 * {@code GA_CHECKER_HOME}, {@code GA_TARGET_CLASSPATH}, {@code GA_CHECKER_JAVA_HOME},
 * {@code GA_JAVAC_RELEASE}, {@code GA_TIMEOUT_SECONDS}, {@code GA_NULLAWAY_ANNOTATED_PACKAGES}); the
 * target and source roots are the run's own. The fingerprint covers everything that can change
 * findings: checker and checkout, source roots, classpath entries with content hashes, the JDK (path
 * and {@code release} file hash), {@code --release}, and the annotated-packages setting. The built
 * checker itself is identified after it ran ({@link ToolIdentity}). The timeout is recorded but not
 * fingerprinted.
 *
 * @param analysis the validated configuration (target: the run snapshot)
 * @param annotatedPackagesSetting the raw {@code GA_NULLAWAY_ANNOTATED_PACKAGES} value, or null
 * @param fingerprint the comparability-relevant settings
 */
public record EvaluationConfig(AnalysisConfig analysis, String annotatedPackagesSetting,
        Map<String, Object> fingerprint) {

    /** Reads and validates the settings for checking {@code snapshot} with the given source roots. */
    public static EvaluationConfig load(Map<String, String> env, Path workingDirectory, Path defaultJavaHome,
            Path snapshot, List<String> sourceRoots, Path outputDirectory) throws ConfigurationException, IOException {
        AnalysisConfig c = new ConfigLoader(env, workingDirectory, defaultJavaHome)
                .loadForSnapshot(snapshot, sourceRoots, outputDirectory);
        String packages = env.get(NullAwayChecker.ANNOTATED_PACKAGES_ENV);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("checker", c.checkerId());
        f.put("checkerHome", c.checkerHome().toString());
        f.put("sourceRoots", c.sourceRoots().stream().map(Path::toString).toList());
        List<Object> classpath = new ArrayList<>();
        for (AnalysisConfig.ClasspathEntry e : c.classpath()) {
            Map<String, Object> m = new LinkedHashMap<>();
            Path resolved = e.relativeToTarget() ? c.targetProject().resolve(e.path()) : e.path();
            m.put("path", (e.relativeToTarget() ? "<target>/" : "") + e.path());
            m.put("sha256", EvaluationFiles.hashPath(resolved));
            classpath.add(m);
        }
        f.put("classpath", classpath);
        f.put("checkerJavaHome", c.checkerJavaHome().toString());
        Path release = c.checkerJavaHome().resolve("release");
        f.put("jdkReleaseFileSha256", Files.isRegularFile(release) ? EvaluationFiles.hashPath(release) : null);
        f.put("javacRelease", c.javacRelease().isPresent() ? c.javacRelease().getAsInt() : null);
        f.put("annotatedPackagesSetting", packages);
        return new EvaluationConfig(c, packages, f);
    }
}
