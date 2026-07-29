package dev.onlyonce.testonly;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionToolResultExporter;
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.InspectionTestUtil;
import com.intellij.testFramework.InspectionsKt;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.VfsTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.testFramework.fixtures.impl.GlobalInspectionContextForTests;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Two source roots, a way to put files in either, and one run of the inspection over both.
 * <p>
 * Shared by every fixture test here because a test-only declaration is only distinguishable from a
 * dead one when production and test sources are separate roots, and setting that up is the bulk of
 * what these tests need.
 */
public abstract class InspectionFixtureTestCase extends LightJavaCodeInsightFixtureTestCase {

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

    protected void production(String fileName, String text) {
        myFixture.addFileToProject(fileName, text);
    }

    protected void inTestSources(String fileName, String text) {
        VfsTestUtil.createFile(testSourceRoot(), fileName, text);
    }

    protected Set<String> runInspection(boolean includeTestSource) {
        return runInspection(includeTestSource, inspection -> { });
    }

    /**
     * @param configure applied to a fresh inspection before the run, so a single test can assert both
     *                  directions of a setting and neither assertion can pass for the wrong reason.
     */
    protected Set<String> runInspection(boolean includeTestSource, Consumer<TestOnlyMethodInspection> configure) {
        return new TreeSet<>(runInspectionForDescriptors(includeTestSource, configure).keySet());
    }

    /**
     * The findings with their descriptors, which is what a test about quick fixes needs — the fixes
     * hang off the descriptor, not off the reported entity.
     *
     * @return findings keyed by {@link ReportedDeclarations}, in name order.
     */
    @SuppressWarnings("UnstableApiUsage")
    protected Map<String, CommonProblemDescriptor[]> runInspectionForDescriptors(
            boolean includeTestSource, Consumer<TestOnlyMethodInspection> configure) {
        TestOnlyMethodInspection inspection = new TestOnlyMethodInspection();
        configure.accept(inspection);
        GlobalInspectionToolWrapper wrapper = new GlobalInspectionToolWrapper(inspection);
        AnalysisScope scope = new AnalysisScope(getProject());
        scope.setIncludeTestSource(includeTestSource);

        GlobalInspectionContextForTests context =
                InspectionsKt.createGlobalContextForTool(scope, getProject(), List.of(wrapper));
        InspectionTestUtil.runTool(wrapper, scope, context);

        InspectionToolResultExporter presentation = context.getPresentation(wrapper);
        Map<String, CommonProblemDescriptor[]> reported = new TreeMap<>();
        for (RefEntity entity : presentation.getProblemElements().keys()) {
            String key = ReportedDeclarations.keyOf(entity);
            if (key != null) {
                reported.put(key, presentation.getProblemElements().get(entity));
            }
        }
        return reported;
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
                child.delete(InspectionFixtureTestCase.class);
            }
        });
    }
}
