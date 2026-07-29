package dev.onlyonce.testonly;

import com.intellij.codeInspection.reference.RefMethod;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Collection;
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

    static boolean isUsedOnlyFromTests(@NotNull RefMethod refMethod,
                                       @NotNull CallerOrigins origins,
                                       @NotNull Collection<String> ignoredAnnotations) {
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
        if (TestOnlyRules.isInTestSources(psiMethod)) {
            return false;
        }
        if (TestOnlyRules.isAcknowledgedAsTestFacing(psiMethod, ignoredAnnotations, true)) {
            return false;
        }

        // An @Override is normally referenced through the symbol it overrides, not its own, so the
        // verdict has to be taken over the whole override family. Erring towards "used" here trades
        // recall for precision on purpose.
        boolean anyProductionCaller = false;
        boolean anyTestCaller = false;
        for (RefMethod member : collectOverrideFamily(refMethod)) {
            // isEntry first: it is a field read, where the delegating check walks every registered
            // EntryPoint extension. On a wide override family that ordering is the difference.
            if (member.isEntry()) {
                return false;
            }
            PsiMethod memberPsi = asPsiMethod(member);
            if (memberPsi != null && TestOnlyRules.isEntryPoint(memberPsi)) {
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
}
