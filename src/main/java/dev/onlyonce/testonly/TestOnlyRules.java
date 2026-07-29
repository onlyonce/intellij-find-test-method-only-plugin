package dev.onlyonce.testonly;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase;
import com.intellij.codeInspection.ex.EntryPointsManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.TestSourcesFilter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * The exclusion rules shared by every kind of declaration this inspection reports.
 * <p>
 * Methods, fields and classes differ only in how their callers are tallied. Whether a declaration
 * lives in test sources, has been acknowledged as test-facing, or is a framework entry point is the
 * same question in all three cases, and is answered here once.
 */
final class TestOnlyRules {

    private TestOnlyRules() {
    }

    static boolean isInTestSources(@NotNull PsiElement element) {
        VirtualFile file = PsiUtilCore.getVirtualFile(element);
        return file != null && TestSourcesFilter.isTestSources(file, element.getProject());
    }

    /**
     * Whether the author has already declared this test-facing, using one of the annotations
     * configured in the inspection settings.
     * <p>
     * Enclosing classes are checked too, because {@code @TestOnly} and friends target types as well as
     * members — a declaration inside a class marked test-only is covered by that declaration. The walk
     * goes all the way out, so annotating an outer class also covers what its nested classes declare.
     *
     * @param checkHierarchy when set, an annotation on an overridden declaration counts, so marking an
     *                       interface method once covers its implementations. Meaningless for fields
     *                       and misleading for classes — a {@code @TestOnly} superclass does not make
     *                       a subclass test-only — so only methods pass it.
     */
    static boolean isAcknowledgedAsTestFacing(@NotNull PsiModifierListOwner declaration,
                                              @NotNull Collection<String> ignoredAnnotations,
                                              boolean checkHierarchy) {
        if (ignoredAnnotations.isEmpty()) {
            return false;
        }
        int flags = checkHierarchy ? AnnotationUtil.CHECK_HIERARCHY : 0;
        if (AnnotationUtil.isAnnotated(declaration, ignoredAnnotations, flags)) {
            return true;
        }
        for (PsiClass enclosing = enclosingClassOf(declaration); enclosing != null;
             enclosing = enclosingClassOf(enclosing)) {
            if (AnnotationUtil.isAnnotated(enclosing, ignoredAnnotations, 0)) {
                return true;
            }
        }
        return false;
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
    static boolean isEntryPoint(@NotNull PsiElement declaration) {
        Project project = declaration.getProject();
        if (EntryPointsManager.getInstance(project).isEntryPoint(declaration)) {
            return true;
        }
        UnusedDeclarationInspectionBase deadCodeTool =
                UnusedDeclarationInspectionBase.findUnusedDeclarationInspection(declaration);
        return deadCodeTool != null && deadCodeTool.isEntryPoint(declaration);
    }

    @Nullable
    private static PsiClass enclosingClassOf(@NotNull PsiElement element) {
        for (PsiElement current = element.getParent(); current != null; current = current.getParent()) {
            if (current instanceof PsiClass) {
                return (PsiClass) current;
            }
        }
        return null;
    }
}
