package showcase;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a declaration — method, field or class — that the inspection <b>must</b> report.
 * <p>
 * {@code ShowcaseInspectionTest} loads these very source files, collects every declaration carrying
 * this annotation, and asserts that the inspection's findings are exactly that set — no more, no
 * fewer. So this annotation is the single source of truth shared by the showcase, the documentation
 * and the test: adding a case here needs no change to the test, and a case that stops being detected
 * fails the build.
 * <p>
 * A declaration <i>without</i> this annotation is asserted <i>not</i> to be reported, which is what
 * makes the exclusion cases meaningful rather than decorative. It is also what pins the roll-up rule:
 * the members of an annotated class carry no annotation of their own, so reporting them individually
 * would fail the same assertion.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.TYPE})
public @interface ExpectedFinding {

    /** Short description of the case, used in the showcase documentation. */
    String value();
}
