package generalannotator.slicing;

/**
 * Produces a program slice for one warning's target member. Implementations run each request in
 * isolation and report failures in the {@link SliceResult}; they never modify the source snapshot.
 *
 * <p>A generated slice is not known to reproduce the warning: reproduction is validated separately.
 */
public interface Slicer {

    SliceResult slice(SliceRequest request);
}
