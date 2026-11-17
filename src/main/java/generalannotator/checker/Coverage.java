package generalannotator.checker;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What part of the target a run covered.
 *
 * @param analyzedSourceRoots source roots (target-relative) passed to the checker
 * @param analyzedSourceFiles number of target source files passed to the checker
 * @param javaFilesOutsideSourceRoots Java files in the isolated copy that were not analyzed,
 *     counted per top-level directory of the target
 * @param limitations human-readable coverage limitations
 */
public record Coverage(
        List<String> analyzedSourceRoots,
        int analyzedSourceFiles,
        SortedMap<String, Integer> javaFilesOutsideSourceRoots,
        List<String> limitations) {

    public Coverage {
        analyzedSourceRoots = List.copyOf(analyzedSourceRoots);
        javaFilesOutsideSourceRoots = new TreeMap<>(javaFilesOutsideSourceRoots);
        limitations = List.copyOf(limitations);
    }

    /** Coverage when nothing was analyzed. */
    public static Coverage none(List<String> roots, String limitation) {
        return new Coverage(roots, 0, new TreeMap<>(Map.of()), List.of(limitation));
    }
}
