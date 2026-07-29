package showcase;

/**
 * Production code calls {@code greet()} through this interface, never through the implementing
 * class. The implementation below therefore has no direct production caller of its own — the verdict
 * has to be taken across the whole override family, or every {@code @Override} in a codebase would
 * be reported.
 */
public interface Greeter {

    String greet();
}
