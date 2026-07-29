package showcase;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that the inspection <b>must</b> report.
 * <p>
 * {@code ShowcaseInspectionTest} loads these very source files, collects every method carrying this
 * annotation, and asserts that the inspection's findings are exactly that set — no more, no fewer.
 * So this annotation is the single source of truth shared by the showcase, the documentation and the
 * test: adding a case here needs no change to the test, and a case that stops being detected fails
 * the build.
 * <p>
 * A method <i>without</i> this annotation is asserted <i>not</i> to be reported, which is what makes
 * the exclusion cases meaningful rather than decorative.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface ExpectedFinding {

    /** Short description of the case, used in the showcase documentation. */
    String value();
}
