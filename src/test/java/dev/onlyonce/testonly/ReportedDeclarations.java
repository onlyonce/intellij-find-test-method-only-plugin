package dev.onlyonce.testonly;

import com.intellij.codeInspection.reference.RefClass;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefField;
import com.intellij.codeInspection.reference.RefMethod;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.Nullable;

/**
 * Renders a reported declaration as a stable string, so assertions can be written as plain sets.
 * <p>
 * The kind is part of the key on purpose. A field and a no-argument method can share a name, and an
 * assertion that could not tell them apart would pass while the inspection reported the wrong thing —
 * which is exactly the failure the roll-up rule can cause.
 */
final class ReportedDeclarations {

    private ReportedDeclarations() {
    }

    @Nullable
    static String keyOf(RefEntity entity) {
        if (entity instanceof RefMethod) {
            PsiMethod method = TestOnlyMethodDetector.asPsiMethod((RefMethod) entity);
            return method == null ? null : methodKey(method);
        }
        if (entity instanceof RefField) {
            PsiField field = TestOnlyFieldDetector.asPsiField((RefField) entity);
            return field == null ? null : fieldKey(field);
        }
        if (entity instanceof RefClass) {
            PsiClass psiClass = TestOnlyClassDetector.asPsiClass((RefClass) entity);
            return psiClass == null ? null : classKey(psiClass);
        }
        return null;
    }

    /** The parameter count is what keeps the two {@code discount} overloads distinguishable. */
    static String methodKey(PsiMethod method) {
        return "method " + ownerOf(method) + "." + method.getName()
                + "/" + method.getParameterList().getParametersCount();
    }

    static String fieldKey(PsiField field) {
        return "field " + ownerOf(field) + "." + field.getName();
    }

    static String classKey(PsiClass psiClass) {
        return "class " + psiClass.getName();
    }

    private static String ownerOf(PsiMember member) {
        PsiClass owner = member.getContainingClass();
        return owner == null ? "?" : owner.getName();
    }
}
