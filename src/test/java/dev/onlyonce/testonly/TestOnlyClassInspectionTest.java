package dev.onlyonce.testonly;

import com.intellij.codeInspection.ex.EntryPointsManagerBase;

import java.util.Set;

/**
 * The class rule, and the two things that make it different from the member rules: references that
 * stay inside the class do not count, and a reported class swallows the findings for its own members.
 */
public class TestOnlyClassInspectionTest extends InspectionFixtureTestCase {

    public void testReportsClassUsedOnlyFromTestSources() {
        production("Helper.java", """
                public class Helper {
                    public int build() { return 1; }
                }
                """);
        inTestSources("HelperTest.java", """
                public class HelperTest {
                    public void checks() { new Helper().build(); }
                }
                """);

        assertEquals(Set.of("class Helper"), runInspection(true));
    }

    /**
     * The roll-up. Without it this class produces four findings — itself and each of its members — and
     * the one that matters, "delete the file", is the hardest of the four to see.
     */
    public void testDoesNotReportMembersOfAReportedClass() {
        production("Helper.java", """
                public class Helper {
                    public static final int LIMIT = 3;
                    public int build() { return 1; }
                    public int also() { return 2; }
                    public static class Inner {
                        public int deep() { return 3; }
                    }
                }
                """);
        inTestSources("HelperTest.java", """
                public class HelperTest {
                    public void checks() {
                        new Helper().build();
                        new Helper().also();
                        new Helper.Inner().deep();
                        System.out.println(Helper.LIMIT);
                    }
                }
                """);

        assertEquals(Set.of("class Helper"), runInspection(true));
    }

    /**
     * The rule the class verdict turns on. {@code createDefault} refers to {@code Builder} from inside
     * {@code Builder}, and if that counted as production use nothing would ever be reported, because
     * every factory, builder and fluent setter does it.
     * <p>
     * Both directions are asserted: switching the class rule off leaves the member findings, which
     * proves the class really was a candidate and the first assertion did not pass by accident.
     */
    public void testSelfReferenceDoesNotCountAsProductionUse() {
        production("Builder.java", """
                public class Builder {
                    public static Builder createDefault() { return new Builder(); }
                    public Builder withName(String name) { return this; }
                }
                """);
        inTestSources("BuilderTest.java", """
                public class BuilderTest {
                    public void checks() { Builder.createDefault().withName("x"); }
                }
                """);

        assertEquals(Set.of("class Builder"), runInspection(true));
        assertEquals("the members must be what is left when the class rule is off",
                Set.of("method Builder.createDefault/0", "method Builder.withName/1"),
                runInspection(true, i -> i.reportClasses = false));
    }

    public void testDoesNotReportClassReferencedFromProduction() {
        production("Helper.java", """
                public class Helper {
                    public int build() { return 1; }
                }
                """);
        production("ProductionUser.java", """
                public class ProductionUser {
                    public Helper make() { return new Helper(); }
                }
                """);
        inTestSources("HelperTest.java", """
                public class HelperTest {
                    public void checks() { new Helper().build(); }
                }
                """);

        // The class is in use; only the method is test-only.
        assertEquals(Set.of("method Helper.build/0"), runInspection(true));
    }

    /**
     * Stage two for classes. A javadoc {@code @link} is invisible to the reference graph, so without
     * the index confirmation this class would be reported despite production naming it.
     */
    public void testDoesNotReportClassReferencedFromProductionJavadoc() {
        production("Helper.java", """
                public class Helper {
                    public int build() { return 1; }
                }
                """);
        production("Docs.java", """
                /** Superseded by the new pricing path, see {@link Helper}. */
                public class Docs { }
                """);
        inTestSources("HelperTest.java", """
                public class HelperTest {
                    public void checks() { new Helper().build(); }
                }
                """);

        assertEquals(Set.of("method Helper.build/0"), runInspection(true));
    }

    /**
     * Only top-level classes are reported. For a nested class the same reference from the outer class
     * is internal to the file and external to the nested type, and the plugin does not guess which
     * reading was meant.
     */
    public void testDoesNotReportNestedClass() {
        production("Outer.java", """
                public class Outer {
                    public int usedInProduction() { return 1; }
                    public static class Nested {
                        public int onlyTestsCallThis() { return 2; }
                    }
                }
                """);
        production("OuterCaller.java", """
                public class OuterCaller {
                    public int go() { return new Outer().usedInProduction(); }
                }
                """);
        inTestSources("OuterTest.java", """
                public class OuterTest {
                    public void checks() { new Outer.Nested().onlyTestsCallThis(); }
                }
                """);

        assertEquals(Set.of("method Nested.onlyTestsCallThis/0"), runInspection(true));
    }

    /**
     * One framework-owned member is enough to keep the whole class: something outside the sources
     * constructs it, and no caller will ever appear in the graph to say so.
     * <p>
     * Self-validating in both directions, so neither assertion can pass for the wrong reason.
     */
    @SuppressWarnings("UnstableApiUsage") // ADDITIONAL_ANNOTATIONS, as in TestOnlyMethodInspectionTest
    public void testDoesNotReportClassWithAnEntryPointMember() {
        production("Wired.java", """
                public @interface Wired { }
                """);
        production("Listener.java", """
                public class Listener {
                    @Wired
                    public void onEvent() { }
                }
                """);
        inTestSources("ListenerTest.java", """
                public class ListenerTest {
                    public void checks() { new Listener().onEvent(); }
                }
                """);

        assertEquals("without the annotation registered the class must be a candidate",
                Set.of("class Listener"), runInspection(true));

        EntryPointsManagerBase entryPoints = EntryPointsManagerBase.getInstance(getProject());
        entryPoints.ADDITIONAL_ANNOTATIONS.add("Wired");
        try {
            assertEquals(Set.of(), runInspection(true));
        } finally {
            entryPoints.ADDITIONAL_ANNOTATIONS.remove("Wired");
        }
    }

    public void testDoesNotReportClassDeclaredInTestSources() {
        production("Anchor.java", """
                public class Anchor { }
                """);
        inTestSources("Fixtures.java", """
                public class Fixtures {
                    public int build() { return 1; }
                }
                """);
        inTestSources("FixturesTest.java", """
                public class FixturesTest {
                    public void checks() { new Fixtures().build(); }
                }
                """);

        assertEquals(Set.of(), runInspection(true));
    }

    public void testConfiguredAnnotationOnClassSuppressesTheClassReport() {
        production("acme/KeptForTests.java", """
                package acme;
                public @interface KeptForTests { }
                """);
        production("Helper.java", """
                @acme.KeptForTests
                public class Helper {
                    public int build() { return 1; }
                }
                """);
        inTestSources("HelperTest.java", """
                public class HelperTest {
                    public void checks() { new Helper().build(); }
                }
                """);

        assertEquals("with an empty list the class must still be reported",
                Set.of("class Helper"), runInspection(true, i -> i.ignoredAnnotations.clear()));
        assertEquals("configuring the annotation must suppress it",
                Set.of(), runInspection(true, i -> {
                    i.ignoredAnnotations.clear();
                    i.ignoredAnnotations.add("acme.KeptForTests");
                }));
    }
}
