package dev.onlyonce.testonly;

import com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase;
import com.intellij.codeInspection.ex.EntryPointsManager;
import com.intellij.codeInspection.reference.RefMethod;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.TestSourcesFilter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides whether a single {@link RefMethod} is a production method used only from test code.
 * <p>
 * Kept free of {@link com.intellij.codeInspection.GlobalInspectionContext} plumbing so the rules can
 * be exercised directly.
 */
final class TestOnlyMethodDetector {

    private TestOnlyMethodDetector() {
    }

    static boolean isUsedOnlyFromTests(@NotNull RefMethod refMethod, @NotNull CallerOrigins origins) {
        PsiMethod psiMethod = asPsiMethod(refMethod);
        if (psiMethod == null) {
            return false;
        }
        if (refMethod.isConstructor()
                || refMethod.isAppMain()
                || refMethod.isRecordAccessor()
                || refMethod.isExternalOverride()
                || refMethod.isTestMethod()) {
            return false;
        }
        if (isInTestSources(psiMethod)) {
            return false;
        }

        // An @Override is normally referenced through the symbol it overrides, not its own, so the
        // verdict has to be taken over the whole override family. Erring towards "used" here trades
        // recall for precision on purpose.
        boolean anyProductionCaller = false;
        boolean anyTestCaller = false;
        for (RefMethod member : collectOverrideFamily(refMethod)) {
            if (isEntryPoint(member)) {
                return false;
            }
            CallerOrigins.Origins memberOrigins = origins.get(member);
            if (memberOrigins == null) {
                continue;
            }
            anyProductionCaller |= memberOrigins.hasProductionCaller();
            anyTestCaller |= memberOrigins.hasTestCaller();
        }

        // No callers at all is plain dead code — that belongs to the Unused declaration inspection.
        return anyTestCaller && !anyProductionCaller;
    }

    @Nullable
    static PsiMethod asPsiMethod(@NotNull RefMethod refMethod) {
        PsiElement psi = refMethod.getPsiElement();
        return psi instanceof PsiMethod ? (PsiMethod) psi : null;
    }

    private static Set<RefMethod> collectOverrideFamily(@NotNull RefMethod root) {
        Set<RefMethod> family = new LinkedHashSet<>();
        Deque<RefMethod> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            RefMethod current = pending.poll();
            if (!family.add(current)) {
                continue;
            }
            pending.addAll(current.getSuperMethods());
            pending.addAll(current.getDerivedMethods());
        }
        return family;
    }

    /**
     * Delegates to the platform rather than hand-rolling an annotation list, so every registered
     * {@code EntryPoint} extension and everything configured under
     * <i>Settings | Editor | Inspections | Entry points</i> is honoured — Spring, JPA, Jackson and
     * the rest come for free.
     * <p>
     * Note {@link UnusedDeclarationInspectionBase#isEntryPoint(PsiElement)} is not subject to the
     * {@code TEST_ENTRY_POINTS} gate; that gate lives in {@code RefJavaManagerImpl.isEntryPoint}, so
     * calling this directly gives the unfiltered answer regardless of how the user configured
     * <i>Unused declaration</i>.
     */
    private static boolean isEntryPoint(@NotNull RefMethod refMethod) {
        if (refMethod.isEntry()) {
            return true;
        }
        PsiMethod psiMethod = asPsiMethod(refMethod);
        if (psiMethod == null) {
            return false;
        }
        Project project = psiMethod.getProject();
        if (EntryPointsManager.getInstance(project).isEntryPoint(psiMethod)) {
            return true;
        }
        UnusedDeclarationInspectionBase deadCodeTool =
                UnusedDeclarationInspectionBase.findUnusedDeclarationInspection(psiMethod);
        return deadCodeTool != null && deadCodeTool.isEntryPoint(psiMethod);
    }

    static boolean isInTestSources(@NotNull PsiMethod psiMethod) {
        VirtualFile file = PsiUtilCore.getVirtualFile(psiMethod);
        return file != null && TestSourcesFilter.isTestSources(file, psiMethod.getProject());
    }
}
