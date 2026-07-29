package showcase;

/**
 * Record accessors are generated from the component list, not written by hand. Reporting one as
 * "test-only" would be noise: the fix a developer would reach for — deleting it — is not available.
 * <p>
 * {@code cents()} is called only from the test source root, and must still not be reported.
 */
public record Money(long cents) {
}
