package dev.onlyonce.testonly;

import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.QuickFix;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The quick fix, and the gate in front of it.
 * <p>
 * The gate is the part worth testing hardest: a fix that writes an annotation the module cannot
 * resolve, or one whose {@code @Target} forbids the declaration it lands on, leaves the file red.
 * That is worse than offering no fix at all, so "no fix offered" is asserted as carefully as
 * "fix works".
 */
public class AnnotateAsTestFacingFixTest extends InspectionFixtureTestCase {

    private static final String ANY_TARGET_ANNOTATION = """
            package acme;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target({ElementType.METHOD, ElementType.FIELD, ElementType.TYPE})
            public @interface ForTesting { }
            """;

    /**
     * {@code getMockJdk17} ships no {@code java.lang.annotation} package, so without these the
     * {@code @Target} on a fixture annotation does not resolve, {@code getQualifiedName()} answers
     * with the bare text {@code "Target"}, and the target check is skipped entirely — every test below
     * would pass while testing nothing. Declaring them as project sources is what puts the check back
     * in the path; in any real project they come from {@code java.base}.
     */
    private void declareJdkAnnotationApi() {
        production("java/lang/annotation/ElementType.java", """
                package java.lang.annotation;
                public enum ElementType {
                    TYPE, FIELD, METHOD, PARAMETER, CONSTRUCTOR, LOCAL_VARIABLE, ANNOTATION_TYPE, PACKAGE
                }
                """);
        production("java/lang/annotation/Target.java", """
                package java.lang.annotation;
                public @interface Target {
                    ElementType[] value();
                }
                """);
    }

    public void testFixAnnotatesTheReportedMethod() {
        PsiFile service = subjectWithTestOnlyMethod();
        declareJdkAnnotationApi();
        production("acme/ForTesting.java", ANY_TARGET_ANNOTATION);

        applyOnlyFix("method Service.onlyTestsCallThis/0", offering("acme.ForTesting"));

        assertTrue("the annotation was not written: " + service.getText(),
                service.getText().contains("@ForTesting"));
        assertTrue("the import was not added: " + service.getText(),
                service.getText().contains("import acme.ForTesting;"));
    }

    public void testFixAnnotatesTheReportedClass() {
        PsiFile helper = myFixture.addFileToProject("Helper.java", """
                public class Helper {
                    public int build() { return 1; }
                }
                """);
        declareJdkAnnotationApi();
        production("acme/ForTesting.java", ANY_TARGET_ANNOTATION);
        inTestSources("HelperTest.java", """
                public class HelperTest {
                    public void checks() { new Helper().build(); }
                }
                """);

        applyOnlyFix("class Helper", offering("acme.ForTesting"));

        assertTrue("the annotation was not written: " + helper.getText(),
                helper.getText().contains("@ForTesting"));
    }

    /**
     * The default list names three annotations, none of which is on this fixture's classpath. Nothing
     * may be offered — writing {@code @VisibleForTesting} into a project without Guava produces a red
     * file, and a fix that breaks the build is worse than no fix.
     * <p>
     * The second half puts Guava's annotation on the classpath and asserts the same default list now
     * offers it. Without that, this test would keep passing if the fix machinery stopped producing
     * anything at all.
     */
    public void testOffersNothingWhenNoCandidateResolves() {
        subjectWithTestOnlyMethod();

        assertEquals("nothing on the classpath, nothing to offer",
                List.of(), fixNamesFor("method Service.onlyTestsCallThis/0", i -> { }));

        production("com/google/common/annotations/VisibleForTesting.java", """
                package com.google.common.annotations;
                public @interface VisibleForTesting { }
                """);

        assertEquals("the same default list must offer it once it resolves",
                List.of("Annotate as @VisibleForTesting"),
                fixNamesFor("method Service.onlyTestsCallThis/0", i -> { }));
    }

    /**
     * {@code @Target(FIELD)} on a method finding. Both annotations resolve and both are candidates, so
     * the only thing separating them is the target check — and asserting the permissive one in the
     * same test is what stops the negative from passing because nothing was offered at all.
     */
    public void testOffersNothingWhenTargetForbidsTheDeclarationKind() {
        subjectWithTestOnlyMethod();
        declareJdkAnnotationApi();
        production("acme/ForTesting.java", ANY_TARGET_ANNOTATION);
        production("acme/FieldOnly.java", """
                package acme;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                @Target(ElementType.FIELD)
                public @interface FieldOnly { }
                """);

        assertEquals("a method-targetable annotation must still be offered",
                List.of("Annotate as @ForTesting"),
                fixNamesFor("method Service.onlyTestsCallThis/0", offering("acme.ForTesting")));
        assertEquals("@Target(FIELD) cannot go on a method",
                List.of(),
                fixNamesFor("method Service.onlyTestsCallThis/0", offering("acme.FieldOnly")));
    }

    /**
     * No {@code @Target} at all means applicable everywhere (JLS 9.6.4.1), so absence must read as
     * yes. Guava's {@code @VisibleForTesting} is declared exactly this way, which is why it matters.
     */
    public void testOffersFixForAnnotationWithoutTarget() {
        subjectWithTestOnlyMethod();
        production("acme/Unrestricted.java", """
                package acme;
                public @interface Unrestricted { }
                """);

        assertEquals(List.of("Annotate as @Unrestricted"),
                fixNamesFor("method Service.onlyTestsCallThis/0", offering("acme.Unrestricted")));
    }

    /**
     * Each candidate gets its own fix, and the names have to differ: the results view groups fixes by
     * family name and merges same-named ones, so a shared name would offer one button that writes a
     * different annotation depending on the row.
     */
    public void testOffersOneDistinctlyNamedFixPerResolvableCandidate() {
        subjectWithTestOnlyMethod();
        declareJdkAnnotationApi();
        production("acme/ForTesting.java", ANY_TARGET_ANNOTATION);
        production("acme/Unrestricted.java", """
                package acme;
                public @interface Unrestricted { }
                """);

        assertEquals(List.of("Annotate as @ForTesting", "Annotate as @Unrestricted"),
                fixNamesFor("method Service.onlyTestsCallThis/0",
                        offering("acme.ForTesting", "acme.Unrestricted")));
    }

    public void testOffersNothingWhenTheAnnotationIsAlreadyPresent() {
        declareJdkAnnotationApi();
        production("acme/ForTesting.java", ANY_TARGET_ANNOTATION);
        production("Service.java", """
                public class Service {
                    @acme.ForTesting
                    public int onlyTestsCallThis() { return 1; }
                    public int usedInProduction() { return 2; }
                }
                """);
        production("Caller.java", """
                public class Caller {
                    public int go() { return new Service().usedInProduction(); }
                }
                """);
        inTestSources("ServiceTest.java", """
                public class ServiceTest {
                    public void checks() { new Service().onlyTestsCallThis(); }
                }
                """);

        // Cleared, or the annotation would suppress the finding itself and there would be nothing to
        // offer a fix on.
        assertEquals(List.of(), fixNamesFor("method Service.onlyTestsCallThis/0", i -> {
            i.ignoredAnnotations = new ArrayList<>();
            i.quickFixAnnotations = new ArrayList<>(List.of("acme.ForTesting"));
        }));
    }

    // ---------------------------------------------------------------- harness

    private PsiFile subjectWithTestOnlyMethod() {
        PsiFile service = myFixture.addFileToProject("Service.java", """
                public class Service {
                    public int onlyTestsCallThis() { return 1; }
                    public int usedInProduction() { return 2; }
                }
                """);
        production("Caller.java", """
                public class Caller {
                    public int go() { return new Service().usedInProduction(); }
                }
                """);
        inTestSources("ServiceTest.java", """
                public class ServiceTest {
                    public void checks() { new Service().onlyTestsCallThis(); }
                }
                """);
        return service;
    }

    private static Consumer<TestOnlyMethodInspection> offering(String... annotations) {
        return inspection -> inspection.quickFixAnnotations = new ArrayList<>(List.of(annotations));
    }

    private List<String> fixNamesFor(String finding, Consumer<TestOnlyMethodInspection> configure) {
        return Arrays.stream(fixesFor(finding, configure))
                .map(QuickFix::getFamilyName)
                .sorted()
                .collect(Collectors.toList());
    }

    private QuickFix<?>[] fixesFor(String finding, Consumer<TestOnlyMethodInspection> configure) {
        return descriptorFor(finding, configure).getFixes();
    }

    private ProblemDescriptor descriptorFor(String finding, Consumer<TestOnlyMethodInspection> configure) {
        Map<String, CommonProblemDescriptor[]> reported = runInspectionForDescriptors(true, configure);
        CommonProblemDescriptor[] descriptors = reported.get(finding);
        assertNotNull("expected a finding for " + finding + ", got " + reported.keySet(), descriptors);
        assertEquals("expected exactly one descriptor for " + finding, 1, descriptors.length);
        assertTrue("a finding anchored on PSI is what carries quick fixes",
                descriptors[0] instanceof ProblemDescriptor);
        return (ProblemDescriptor) descriptors[0];
    }

    private void applyOnlyFix(String finding, Consumer<TestOnlyMethodInspection> configure) {
        ProblemDescriptor descriptor = descriptorFor(finding, configure);
        QuickFix<?>[] fixes = descriptor.getFixes();
        assertNotNull("no fixes were attached to " + finding, fixes);
        assertEquals("expected exactly one fix for " + finding + ", got "
                + Arrays.stream(fixes).map(QuickFix::getFamilyName).collect(Collectors.toSet()), 1, fixes.length);
        LocalQuickFix fix = (LocalQuickFix) fixes[0];
        WriteCommandAction.runWriteCommandAction(getProject(), () -> fix.applyFix(getProject(), descriptor));
    }

    /** Guards the set-based assertions above against a silently empty run. */
    public void testFindingIsProducedAtAll() {
        subjectWithTestOnlyMethod();
        assertEquals(Set.of("method Service.onlyTestsCallThis/0"), runInspection(true));
    }
}
