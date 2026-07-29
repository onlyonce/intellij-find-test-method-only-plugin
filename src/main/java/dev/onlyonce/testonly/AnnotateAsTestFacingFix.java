package dev.onlyonce.testonly;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Marks the reported declaration as knowingly test-facing.
 * <p>
 * This is the action the finding is actually asking for. A test-only method is not automatically a
 * mistake — sometimes the honest answer is "yes, this exists for the tests, and that is fine". Saying
 * so in the source both silences this inspection (the annotation lands in its ignore list) and turns
 * on the built-in <i>Test-only usage in production code</i>, which then stops production code from
 * calling it. Deleting is the other answer, and IntelliJ's own <i>Safe delete</i> already does that
 * better than a fix here could, because it shows the test callers first.
 * <p>
 * The target is taken from the descriptor rather than held in a field, so the fix carries nothing but
 * the annotation name. That is what lets it be rebuilt from a hint when an offline result file is
 * reopened — see {@code TestOnlyMethodInspection.getQuickFix}.
 */
final class AnnotateAsTestFacingFix implements LocalQuickFix {

    private final String annotationFqn;

    AnnotateAsTestFacingFix(@NotNull String annotationFqn) {
        this.annotationFqn = annotationFqn;
    }

    @NotNull
    String getAnnotationFqn() {
        return annotationFqn;
    }

    /**
     * Distinct per annotation on purpose. The inspection results view groups fixes by family name and
     * merges same-named ones from different descriptors into a single action, so a shared name such as
     * "Annotate as test-facing" would offer one button that writes a different annotation depending on
     * the row.
     */
    @Override
    public @NotNull String getFamilyName() {
        return "Annotate as @" + shortNameOf(annotationFqn);
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiModifierListOwner owner = ownerOf(descriptor.getPsiElement());
        if (owner == null) {
            return;
        }
        PsiModifierList modifierList = owner.getModifierList();
        if (modifierList == null || modifierList.hasAnnotation(annotationFqn)) {
            return;
        }
        PsiAnnotation added = modifierList.addAnnotation(annotationFqn);
        // addAnnotation writes the qualified name verbatim; this is what turns it into an import.
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
    }

    /**
     * The descriptor is anchored on the name identifier, so the declaration is its nearest owning
     * ancestor. Walking up rather than assuming a fixed depth keeps this correct for all three
     * reported kinds.
     */
    @Nullable
    private static PsiModifierListOwner ownerOf(@Nullable PsiElement anchor) {
        for (PsiElement current = anchor; current != null; current = current.getParent()) {
            if (current instanceof PsiModifierListOwner) {
                return (PsiModifierListOwner) current;
            }
        }
        return null;
    }

    private static String shortNameOf(@NotNull String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }
}
