package dev.onlyonce.testonly;

import com.intellij.codeInspection.reference.RefClass;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decides whether a whole production class exists only for the tests — the bigger finding, because
 * the answer is "delete the file", not "delete a method".
 * <p>
 * Two things make this different from the member rules.
 * <p>
 * First, the tally runs over the class <em>and everything it declares</em>, nested classes included.
 * A class is used if anything in it is used, and asking only about references to the class name
 * would miss a static constant reached through a static import.
 * <p>
 * Second, references that stay inside the class do not count. A static factory returning its own
 * type, a builder whose methods return {@code this}, an inner class the outer one instantiates —
 * these are the class existing, not the class being used, and counting them would mean no class is
 * ever reported. {@link CallerOrigins} draws that boundary at the top-level class, which is why only
 * top-level classes are considered here: for a nested class the same reference is internal to the
 * outer class and external to the inner one, and one bit cannot answer both.
 */
final class TestOnlyClassDetector {

    private TestOnlyClassDetector() {
    }

    static boolean isUsedOnlyFromTests(@NotNull RefClass refClass,
                                       @NotNull CallerOrigins origins,
                                       @NotNull Collection<String> ignoredAnnotations) {
        PsiClass psiClass = asPsiClass(refClass);
        if (psiClass == null || refClass.isAnonymous() || !isTopLevel(psiClass)) {
            return false;
        }
        if (TestOnlyRules.isInTestSources(psiClass)) {
            return false;
        }
        if (TestOnlyRules.isAcknowledgedAsTestFacing(psiClass, ignoredAnnotations, false)) {
            return false;
        }

        boolean anyExternalProductionCaller = false;
        boolean anyExternalTestCaller = false;
        for (RefElement member : collectSelfAndMembers(refClass)) {
            // One framework-owned member is enough to keep the class: something outside the source
            // constructs it, and no caller will ever appear in the graph to say so.
            PsiElement memberPsi = member.getPsiElement();
            if (member.isEntry() || (memberPsi != null && TestOnlyRules.isEntryPoint(memberPsi))) {
                return false;
            }
            CallerOrigins.Origins memberOrigins = origins.get(member);
            if (memberOrigins == null) {
                continue;
            }
            anyExternalProductionCaller |= memberOrigins.hasExternalProductionCaller();
            anyExternalTestCaller |= memberOrigins.hasExternalTestCaller();
        }

        // No callers at all is plain dead code — that belongs to the Unused declaration inspection.
        return anyExternalTestCaller && !anyExternalProductionCaller;
    }

    /**
     * The class itself plus every declaration nested inside it, at any depth.
     * <p>
     * {@code RefClass} owns its members as graph children, and a nested class is a child of the class
     * that declares it, so the recursion covers the whole file-level unit the boundary is drawn
     * around.
     */
    static List<RefElement> collectSelfAndMembers(@NotNull RefClass refClass) {
        List<RefElement> collected = new ArrayList<>();
        collect(refClass, collected);
        return collected;
    }

    private static void collect(@NotNull RefEntity entity, @NotNull List<RefElement> into) {
        if (entity instanceof RefElement) {
            into.add((RefElement) entity);
        }
        for (RefEntity child : entity.getChildren()) {
            collect(child, into);
        }
    }

    /**
     * Local and anonymous classes have no qualified name; nested ones have a containing class. Both
     * are excluded — see the class comment for why the boundary has to be the top-level class.
     */
    static boolean isTopLevel(@NotNull PsiClass psiClass) {
        return psiClass.getQualifiedName() != null && psiClass.getContainingClass() == null;
    }

    @Nullable
    static PsiClass asPsiClass(@NotNull RefClass refClass) {
        PsiElement psi = refClass.getPsiElement();
        return psi instanceof PsiClass ? (PsiClass) psi : null;
    }
}
