package generalannotator.targets;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Specimin target member for one warning node, or the reason none could be determined.
 *
 * <p>A resolved target only identifies the member enclosing the warning. Whether a Specimin slice
 * of that member still reproduces the warning is not checked here.
 */
public sealed interface WarningTarget {

    /** The warning node ID from the warning graph (preserved, never regenerated). */
    String warningId();

    /** {@code targetPath:line[:column]} of the warning. */
    String location();

    /** Context notes, for example "inside a lambda expression". */
    List<String> notes();

    /**
     * A target member was identified.
     *
     * @param sourceRoot Java package root, relative to the target project ({@code .} for its root);
     *     Specimin's {@code --root}
     * @param isolatedSourceRoot the same root as an absolute path in the isolated working copy
     * @param targetFile file path relative to {@code sourceRoot}; Specimin's {@code --targetFile}
     * @param memberKind method, constructor or field
     * @param enclosingClass Specimin-style qualified name of the class declaring the member
     * @param target {@code Class#method(T1, T2)} or {@code Class#field}; Specimin's {@code
     *     --targetMethod} or {@code --targetField} according to {@code memberKind}
     * @param configuredSourceRoot the configured {@code GA_TARGET_SOURCE_ROOTS} entry containing the file
     * @param requiresDisableRootValidation the member's top-level class is not the file's primary class,
     *     so Specimin needs {@code --disable-root-validation}
     */
    record Resolved(
            String warningId,
            String location,
            String sourceRoot,
            String isolatedSourceRoot,
            String targetFile,
            MemberKind memberKind,
            String enclosingClass,
            String target,
            Optional<String> configuredSourceRoot,
            boolean requiresDisableRootValidation,
            List<String> notes) implements WarningTarget {

        public Resolved {
            Objects.requireNonNull(warningId, "warningId");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(sourceRoot, "sourceRoot");
            Objects.requireNonNull(isolatedSourceRoot, "isolatedSourceRoot");
            Objects.requireNonNull(targetFile, "targetFile");
            Objects.requireNonNull(memberKind, "memberKind");
            Objects.requireNonNull(enclosingClass, "enclosingClass");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(configuredSourceRoot, "configuredSourceRoot");
            notes = List.copyOf(notes);
        }

        /** The value for {@code --targetMethod}, if this is a method or constructor. */
        public Optional<String> targetMethod() {
            return memberKind == MemberKind.FIELD ? Optional.empty() : Optional.of(target);
        }

        /** The value for {@code --targetField}, if this is a field. */
        public Optional<String> targetField() {
            return memberKind == MemberKind.FIELD ? Optional.of(target) : Optional.empty();
        }
    }

    /** No target member could be determined; {@code reason} says why. */
    record Unresolved(String warningId, String location, String reason, List<String> notes) implements WarningTarget {
        public Unresolved {
            Objects.requireNonNull(warningId, "warningId");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(reason, "reason");
            notes = List.copyOf(notes);
        }
    }
}
