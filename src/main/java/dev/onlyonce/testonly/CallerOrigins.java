package dev.onlyonce.testonly;

import com.intellij.codeInspection.reference.RefElement;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.TestSourcesFilter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
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

    /** Mutable per-callee bits. Both fields are set-once-to-true, so volatile writes suffice. */
    static final class Origins {
        volatile boolean production;
        volatile boolean test;

        boolean hasProductionCaller() {
            return production;
        }

        boolean hasTestCaller() {
            return test;
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
        if (isInTestSources(caller, project)) {
            origins.test = true;
        } else {
            origins.production = true;
        }
    }

    @Nullable
    Origins get(@NotNull RefElement callee) {
        return byCallee.get(callee);
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
