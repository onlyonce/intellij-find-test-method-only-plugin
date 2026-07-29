package showcase;

/**
 * Only the test source root mentions {@code LoudGreeter.greet()} by name. Production reaches it via
 * {@link Greeter}, so it is in use and must not be reported.
 */
public class LoudGreeter implements Greeter {

    @Override
    public String greet() {
        return "HELLO";
    }
}
