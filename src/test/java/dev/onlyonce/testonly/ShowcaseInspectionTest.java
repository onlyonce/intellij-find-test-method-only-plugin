package dev.onlyonce.testonly;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.InspectionToolResultExporter;
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefMethod;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.InspectionTestUtil;
import com.intellij.testFramework.InspectionsKt;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.VfsTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.testFramework.fixtures.impl.GlobalInspectionContextForTests;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Runs the inspection over {@code samples/showcase} and asserts the findings are exactly the methods
 * annotated {@code @ExpectedFinding}.
 * <p>
 * This exists so the sample project cannot become decorative. The showcase is browsable Java used for
 * documentation and for the Marketplace screenshot; without an assertion tied to the same files it
 * would drift silently the first time behaviour changed. Because the expectation is read from the
 * sources themselves, adding a case requires no change here — and a case that stops being detected,
 * or an exclusion that starts being reported, fails the build.
 */
public class ShowcaseInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String EXPECTED_FINDING_ANNOTATION = "ExpectedFinding";

    private static final LightProjectDescriptor DESCRIPTOR = new TwoRootProjectDescriptor();

    private final List<PsiFile> productionFiles = new ArrayList<>();

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            productionFiles.clear();
            TestOnlyMethodInspectionTest.cleanTestSourceRoot();
        } catch (Throwable t) {
            addSuppressedException(t);
        } finally {
            super.tearDown();
        }
    }

    public void testShowcaseFindingsMatchTheAnnotatedMethods() throws IOException {
        Path showcase = showcaseRoot();
        loadProduction(showcase.resolve("src/main/java"));
        loadTests(showcase.resolve("src/test/java"));

        Set<String> expected = methodsAnnotatedExpectedFinding();
        assertFalse("no @ExpectedFinding methods found — the showcase did not load", expected.isEmpty());

        Set<String> reported = runInspection();

        assertEquals("showcase findings drifted from the @ExpectedFinding annotations",
                expected, reported);
    }

    // ---------------------------------------------------------------- loading

    private static Path showcaseRoot() {
        String configured = System.getProperty("showcase.dir");
        assertNotNull("showcase.dir system property is not set — see tasks.test in build.gradle.kts",
                configured);
        Path path = Path.of(configured);
        assertTrue("showcase directory does not exist: " + path, Files.isDirectory(path));
        return path;
    }

    private void loadProduction(Path sourceRoot) throws IOException {
        for (Path file : javaFilesUnder(sourceRoot)) {
            String relative = sourceRoot.relativize(file).toString();
            productionFiles.add(myFixture.addFileToProject(relative, Files.readString(file, StandardCharsets.UTF_8)));
        }
    }

    private void loadTests(Path sourceRoot) throws IOException {
        VirtualFile testRoot = VirtualFileManager.getInstance()
                .refreshAndFindFileByUrl("temp:///" + TwoRootProjectDescriptor.TEST_ROOT);
        assertNotNull("test source root missing", testRoot);
        for (Path file : javaFilesUnder(sourceRoot)) {
            String relative = sourceRoot.relativize(file).toString();
            VirtualFile created = VfsTestUtil.createFile(testRoot, relative,
                    Files.readString(file, StandardCharsets.UTF_8));
            PsiManager.getInstance(getProject()).findFile(created);
        }
    }

    private static List<Path> javaFilesUnder(Path root) throws IOException {
        assertTrue("expected source root does not exist: " + root, Files.isDirectory(root));
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    // ------------------------------------------------------------ expectation

    private Set<String> methodsAnnotatedExpectedFinding() {
        Set<String> expected = new TreeSet<>();
        for (PsiFile file : productionFiles) {
            for (PsiMethod method : PsiTreeUtil.findChildrenOfType(file, PsiMethod.class)) {
                if (hasExpectedFindingAnnotation(method)) {
                    expected.add(key(method));
                }
            }
        }
        return expected;
    }

    private static boolean hasExpectedFindingAnnotation(PsiMethod method) {
        PsiModifierList modifiers = method.getModifierList();
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null && qualifiedName.endsWith(EXPECTED_FINDING_ANNOTATION)) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------------- harness

    private Set<String> runInspection() {
        GlobalInspectionToolWrapper wrapper = new GlobalInspectionToolWrapper(new TestOnlyMethodInspection());
        AnalysisScope scope = new AnalysisScope(getProject());
        scope.setIncludeTestSource(true);

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
            if (method != null) {
                reported.add(key(method));
            }
        }
        return reported;
    }

    private static String key(PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        return (owner == null ? "?" : owner.getName()) + "." + method.getName()
                + "/" + method.getParameterList().getParametersCount();
    }
}
