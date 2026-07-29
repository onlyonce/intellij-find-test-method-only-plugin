package dev.onlyonce.testonly;

import com.intellij.codeInspection.reference.RefElement;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.TestSourcesFilter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records, per referenced element, whether it was referenced from production sources, from test
 * sources, or both.
 * <p>
 * Populated from {@link com.intellij.codeInspection.reference.RefGraphAnnotator#onMarkReferenced} so
 * that every reference is seen <em>as the graph is built</em>. Reading
 * {@link RefElement#getInReferences()} after the fact is not equivalent: reference building is lazy
 * (hence {@link RefElement#areReferencesBuilt()}), and the graph is iterated in unspecified order, so
 * a caller whose references have not been built yet would be invisible and the callee would look
 * test-only when it is not.
 */
final class CallerOrigins {

    /**
     * Mutable per-callee bits. Every field is set-once-to-true, so volatile writes suffice.
     * <p>
     * Two pairs, because members and whole classes ask different questions. A member asks "does
     * anything in production use me", and a call from a sibling method is a real production use. A
     * class asks "does anything <em>outside me</em> use me" — a static factory returning its own type
     * is the class existing, not the class being used, and counting it would mean no class is ever
     * reported. The {@code external} pair is the same tally with same-top-level-class references
     * dropped.
     */
    static final class Origins {
        volatile boolean production;
        volatile boolean test;
        volatile boolean externalProduction;
        volatile boolean externalTest;

        boolean hasProductionCaller() {
            return production;
        }

        boolean hasTestCaller() {
            return test;
        }

        boolean hasExternalProductionCaller() {
            return externalProduction;
        }

        boolean hasExternalTestCaller() {
            return externalTest;
        }
    }

    private final Map<RefElement, Origins> byCallee = new ConcurrentHashMap<>();

    void record(@Nullable RefElement callee, @Nullable RefElement caller, @NotNull Project project) {
        if (callee == null || caller == null) {
            return;
        }
        // Direct recursion is not a caller: a method calling itself must not count as production use.
        if (callee.equals(caller)) {
            return;
        }
        Origins origins = byCallee.computeIfAbsent(callee, key -> new Origins());
        boolean fromTest = isInTestSources(caller, project);
        boolean external = isExternal(callee, caller);
        if (fromTest) {
            origins.test = true;
            if (external) {
                origins.externalTest = true;
            }
        } else {
            origins.production = true;
            if (external) {
                origins.externalProduction = true;
            }
        }
    }

    @Nullable
    Origins get(@NotNull RefElement callee) {
        return byCallee.get(callee);
    }

    /**
     * Whether the reference crosses a top-level class boundary.
     * <p>
     * The boundary is the <em>top-level</em> class rather than the immediately declaring one, so that
     * a nested class and its outer class count as one unit. That is what makes the bit meaningful
     * without knowing which class will later be asked about: for any top-level class {@code C}, a
     * reference is internal exactly when both ends sit inside {@code C}.
     * <p>
     * Anything that cannot be placed in a class is treated as external, which can only make a finding
     * less likely.
     */
    private static boolean isExternal(@NotNull RefElement callee, @NotNull RefElement caller) {
        PsiClass calleeOwner = topLevelClassOf(callee.getPsiElement());
        PsiClass callerOwner = topLevelClassOf(caller.getPsiElement());
        return calleeOwner == null || callerOwner == null || !calleeOwner.equals(callerOwner);
    }

    /**
     * The outermost class enclosing {@code psi}, or {@code psi} itself when it is already one.
     * <p>
     * Walks parents directly rather than going through {@code PsiUtil}, which keeps this to
     * {@link PsiElement#getParent()} — the one part of the PSI surface that cannot move.
     */
    @Nullable
    static PsiClass topLevelClassOf(@Nullable PsiElement psi) {
        if (psi == null) {
            return null;
        }
        PsiClass outermost = psi instanceof PsiClass ? (PsiClass) psi : null;
        for (PsiElement current = psi.getParent(); current != null && !(current instanceof PsiFile);
             current = current.getParent()) {
            if (current instanceof PsiClass) {
                outermost = (PsiClass) current;
            }
        }
        return outermost;
    }

    /**
     * Anything we cannot place is treated as production. That is the conservative direction: it can
     * only suppress a report, never manufacture one.
     */
    private static boolean isInTestSources(@NotNull RefElement caller, @NotNull Project project) {
        PsiElement psi = caller.getPsiElement();
        if (psi == null) {
            return false;
        }
        VirtualFile file = PsiUtilCore.getVirtualFile(psi);
        if (file == null) {
            return false;
        }
        return TestSourcesFilter.isTestSources(file, project);
    }
}
