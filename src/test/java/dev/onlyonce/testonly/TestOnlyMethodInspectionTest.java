package dev.onlyonce.testonly;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.InspectionToolResultExporter;
import com.intellij.codeInspection.ex.EntryPointsManagerBase;
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefMethod;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.InspectionTestUtil;
import com.intellij.testFramework.InspectionsKt;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.VfsTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.testFramework.fixtures.impl.GlobalInspectionContextForTests;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every case here is one of the exclusion rules in {@link TestOnlyMethodDetector}.
 * <p>
 * Note {@link #testReportsMethodCalledOnlyFromTestSources()} is the load-bearing one: it is the only
 * assertion that fails if the {@code RefGraphAnnotator} never fires. A suite made only of
 * "not reported" assertions would pass with the annotator wired to the wrong overload.
 */
public class TestOnlyMethodInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private static final LightProjectDescriptor DESCRIPTOR = new TwoRootProjectDescriptor();

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            cleanTestSourceRoot();
        } catch (Throwable t) {
            addSuppressedException(t);
        } finally {
            super.tearDown();
        }
    }

    // ------------------------------------------------------------------ cases

    public void testReportsMethodCalledOnlyFromTestSources() {
        production("Service.java", """
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
        testSource("ServiceTest.java", """
                public class ServiceTest {
                    public void checks() {
                        new Service().onlyTestsCallThis();
                        new Service().usedInProduction();
                    }
                }
                """);

        assertEquals(Set.of("Service.onlyTestsCallThis"), runInspection(true));
    }

    /**
     * Ordering probe. {@code RefManager.iterate} visits entities in unspecified order, and
     * {@code onMarkReferenced} only fires once a caller's references are actually built — a separate
     * phase from declaration building. If the callee were visited before the test class's references
     * existed, {@code hasTestCaller} would still be false and the method would go silently
     * unreported.
     * <p>
     * The names put the callee first and the test last alphabetically, with filler in between, so any
     * naive ordering dependency has room to show itself.
     */
    public void testReportsRegardlessOfIterationOrder() {
        production("AService.java", """
                public class AService {
                    public int onlyTestsCallThis() { return 1; }
                }
                """);
        for (int i = 0; i < 12; i++) {
            production("Filler" + i + ".java", """
                    public class Filler%d {
                        public int compute() { return %d; }
                        public int chain() { return compute(); }
                    }
                    """.formatted(i, i));
        }
        testSource("ZzzServiceTest.java", """
                public class ZzzServiceTest {
                    public void checks() { new AService().onlyTestsCallThis(); }
                }
                """);

        assertEquals(Set.of("AService.onlyTestsCallThis"), runInspection(true));
    }

    public void testDoesNotReportMethodWithoutAnyCaller() {
        production("Orphan.java", """
                public class Orphan {
                    public int neverCalledAtAll() { return 1; }
                }
                """);
        testSource("OrphanTest.java", """
                public class OrphanTest {
                    public void checks() { }
                }
                """);

        // No callers at all is plain dead code and belongs to the Unused declaration inspection.
        assertEquals(Set.of(), runInspection(true));
    }

    public void testDoesNotReportMethodDeclaredInTestSources() {
        production("Plain.java", """
                public class Plain {
                    public int value() { return 1; }
                }
                """);
        production("PlainUser.java", """
                public class PlainUser {
                    public int go() { return new Plain().value(); }
                }
                """);
        testSource("Helper.java", """
                public class Helper {
                    public int helperOnlyUsedByTests() { return 1; }
                }
                """);
        testSource("HelperTest.java", """
                public class HelperTest {
                    public void checks() { new Helper().helperOnlyUsedByTests(); }
                }
                """);

        assertEquals(Set.of(), runInspection(true));
    }

    public void testDoesNotReportConstructorsOrMain() {
        production("Boot.java", """
                public class Boot {
                    public Boot() { }
                    public static void main(String[] args) { }
                }
                """);
        testSource("BootTest.java", """
                public class BootTest {
                    public void checks() {
                        new Boot();
                        Boot.main(new String[0]);
                    }
                }
                """);

        assertEquals(Set.of(), runInspection(true));
    }

    public void testDoesNotReportRecordAccessor() {
        production("Point.java", """
                public record Point(int x, int y) { }
                """);
        production("PointUser.java", """
                public class PointUser {
                    public Point make() { return new Point(1, 2); }
                }
                """);
        testSource("PointTest.java", """
                public class PointTest {
                    public void checks() { new Point(1, 2).x(); }
                }
                """);

        assertEquals(Set.of(), runInspection(true));
    }

    public void testDoesNotReportOverrideOfInterfaceUsedFromProduction() {
        production("Greeter.java", """
                public interface Greeter {
                    String greet();
                }
                """);
        production("LoudGreeter.java", """
                public class LoudGreeter implements Greeter {
                    @Override public String greet() { return "HI"; }
                }
                """);
        production("GreeterUser.java", """
                public class GreeterUser {
                    public String go(Greeter g) { return g.greet(); }
                }
                """);
        testSource("GreeterTest.java", """
                public class GreeterTest {
                    public void checks() { new LoudGreeter().greet(); }
                }
                """);

        // The production call goes through the interface symbol; the override family must absorb it.
        assertEquals(Set.of(), runInspection(true));
    }

    /**
     * Stage two exists to catch references the Java reference graph never records. A javadoc
     * {@code @link} from production is the one such reference reproducible without a framework
     * plugin, so it stands in here for the Spring XML / SpEL / properties cases that motivate the
     * stage. Remove stage two and this is the only test that fails.
     */
    public void testDoesNotReportMethodReferencedFromProductionJavadoc() {
        production("Api.java", """
                public class Api {
                    public int onlyTestsCallThis() { return 1; }
                }
                """);
        production("Docs.java", """
                /** See {@link Api#onlyTestsCallThis()} for the legacy behaviour. */
                public class Docs { }
                """);
        testSource("ApiTest.java", """
                public class ApiTest {
                    public void checks() { new Api().onlyTestsCallThis(); }
                }
                """);

        assertEquals(Set.of(), runInspection(true));
    }

    /**
     * The exclusion that decides whether this is usable on a real Spring/Kafka codebase. Spring's
     * {@code @Bean}, {@code @KafkaListener} and friends reach the inspection through the platform's
     * entry-point machinery, and a custom annotation registered in
     * {@code EntryPointsManagerBase.ADDITIONAL_ANNOTATIONS} exercises the same path without needing
     * the framework on the fixture classpath.
     * <p>
     * Self-validating: the first assertion proves the method really is a candidate, so the second
     * cannot pass for the wrong reason.
     */
    public void testEntryPointAnnotationSuppressesReport() {
        production("Wired.java", """
                public @interface Wired { }
                """);
        production("Beans.java", """
                public class Beans {
                    @Wired
                    public int frameworkCallsThis() { return 1; }
                }
                """);
        testSource("BeansTest.java", """
                public class BeansTest {
                    public void checks() { new Beans().frameworkCallsThis(); }
                }
                """);

        assertEquals("without the annotation registered this must be a candidate",
                Set.of("Beans.frameworkCallsThis"), runInspection(true));

        EntryPointsManagerBase entryPoints = EntryPointsManagerBase.getInstance(getProject());
        entryPoints.ADDITIONAL_ANNOTATIONS.add("Wired");
        try {
            assertEquals(Set.of(), runInspection(true));
        } finally {
            entryPoints.ADDITIONAL_ANNOTATIONS.remove("Wired");
        }
    }

    public void testReportsNothingWhenScopeExcludesTestSources() {
        production("Service.java", """
                public class Service {
                    public int onlyTestsCallThis() { return 1; }
                }
                """);
        testSource("ServiceTest.java", """
                public class ServiceTest {
                    public void checks() { new Service().onlyTestsCallThis(); }
                }
                """);

        // Without test sources in the graph a test-only method is indistinguishable from a fully
        // unused one, so the inspection must refuse to guess.
        assertEquals(Set.of(), runInspection(false));
    }

    // ---------------------------------------------------------------- harness

    private Set<String> runInspection(boolean includeTestSource) {
        GlobalInspectionToolWrapper wrapper = new GlobalInspectionToolWrapper(new TestOnlyMethodInspection());
        AnalysisScope scope = new AnalysisScope(getProject());
        scope.setIncludeTestSource(includeTestSource);

        GlobalInspectionContextForTests context =
                InspectionsKt.createGlobalContextForTool(scope, getProject(), List.of(wrapper));
        InspectionTestUtil.runTool(wrapper, scope, context);

        InspectionToolResultExporter presentation = context.getPresentation(wrapper);
        Set<String> reported = new TreeSet<>();
        for (RefEntity entity : presentation.getProblemElements().keys()) {
            if (!(entity instanceof RefMethod)) {
                continue;
            }
            PsiMethod method = TestOnlyMethodDetector.asPsiMethod((RefMethod) entity);
            if (method == null) {
                continue;
            }
            PsiClass owner = method.getContainingClass();
            reported.add((owner == null ? "?" : owner.getName()) + "." + method.getName());
        }
        return reported;
    }

    private void production(String fileName, String text) {
        myFixture.addFileToProject(fileName, text);
    }

    private void testSource(String fileName, String text) {
        VfsTestUtil.createFile(testSourceRoot(), fileName, text);
    }

    private static VirtualFile testSourceRoot() {
        VirtualFile root = VirtualFileManager.getInstance()
                .refreshAndFindFileByUrl("temp:///" + TwoRootProjectDescriptor.TEST_ROOT);
        assertNotNull("test source root was not created by " + TwoRootProjectDescriptor.class.getSimpleName(), root);
        return root;
    }

    /**
     * The light project is cached and reused across test methods, so {@code configureModule} — and
     * with it the source-root cleanup — runs only once. Files left in the test root would leak into
     * the next test.
     */
    static void cleanTestSourceRoot() throws IOException {
        VirtualFile root = VirtualFileManager.getInstance()
                .refreshAndFindFileByUrl("temp:///" + TwoRootProjectDescriptor.TEST_ROOT);
        if (root == null) {
            return;
        }
        WriteAction.runAndWait(() -> {
            for (VirtualFile child : root.getChildren()) {
                child.delete(TestOnlyMethodInspectionTest.class);
            }
        });
    }
}
