package generalannotator.checker.nullaway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnnotatedPackagesTest {

    @Test
    void findsPackageAfterLicenseHeaderAndIgnoresCommentedOutPackage() {
        String source = """
                /*
                 * package not.this.one;
                 */
                // package nor.this;
                package org.greenrobot.eventbus ;

                import java.util.List;
                """;
        assertEquals(Optional.of("org.greenrobot.eventbus"), AnnotatedPackages.packageOf(source));
    }

    @Test
    void annotatedPackageInfoAndUnnamedPackage() {
        assertEquals(Optional.of("a.b"), AnnotatedPackages.packageOf("@Deprecated\npackage a.b;"));
        assertEquals(Optional.empty(), AnnotatedPackages.packageOf("class NoPackage {}"));
    }

    @Test
    void minimalPrefixesDropSubpackagesButKeepSiblings() {
        assertEquals(
                List.of("org.a", "org.ab"),
                AnnotatedPackages.minimalPrefixes(List.of("org.a.x", "org.a", "org.ab", "org.a.y.z")));
    }

    @Test
    void parsesAndValidatesConfiguredList() {
        assertEquals(List.of("com.x", "org.y"), AnnotatedPackages.parseConfigured(" com.x , org.y,"));
        assertThrows(IllegalArgumentException.class, () -> AnnotatedPackages.parseConfigured("com.x,1bad"));
        assertThrows(IllegalArgumentException.class, () -> AnnotatedPackages.parseConfigured(" , "));
    }
}
