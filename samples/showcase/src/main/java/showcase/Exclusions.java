package showcase;

/**
 * Cases the inspection must stay silent about. None carries {@code @ExpectedFinding}, and the test
 * asserts the reported set is exactly the annotated set — so if any of these ever starts being
 * reported, the build fails.
 */
public class Exclusions {

    /**
     * Constructors are excluded even when only tests instantiate the class. Object construction is
     * not the kind of API this inspection is about, and reporting it would flag most value types.
     */
    public Exclusions() {
    }

    /**
     * No callers at all — anywhere. That is plain dead code and belongs to the built-in
     * <i>Unused declaration</i> inspection. Reporting it here would duplicate that and blur the
     * distinction this plugin exists to draw.
     */
    public void neverCalledByAnyone() {
    }

    /** An application entry point is called by the JVM, not by project code. */
    public static void main(String[] args) {
        System.out.println(new Exclusions());
    }
}
