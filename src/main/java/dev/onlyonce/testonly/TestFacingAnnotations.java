package dev.onlyonce.testonly;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builds the {@link AnnotateAsTestFacingFix} offers for one reported declaration.
 * <p>
 * A fix is only offered for an annotation that would actually compile where it is about to be
 * written: it has to resolve from the declaration's own module, and its {@code @Target} has to permit
 * the kind of declaration being annotated. Offering {@code @VisibleForTesting} to a project without
 * Guava on the classpath would produce a red file, and a fix that breaks the build is worse than no
 * fix.
 * <p>
 * Which annotations are candidates is a separate setting from the ignore list, because the two lists
 * answer different questions. The ignore list says what suppresses a report — including generated-code
 * markers, which are a statement about who wrote the code. This list says what the plugin is willing
 * to write on your behalf, and annotating hand-written code as {@code @Generated} would be a lie.
 */
final class TestFacingAnnotations {

    private static final String TARGET_ANNOTATION = "java.lang.annotation.Target";

    private TestFacingAnnotations() {
    }

    static LocalQuickFix[] fixesFor(@NotNull PsiModifierListOwner declaration,
                                    @NotNull Collection<String> candidates) {
        if (candidates.isEmpty()) {
            return LocalQuickFix.EMPTY_ARRAY;
        }
        String requiredTarget = requiredTargetFor(declaration);
        if (requiredTarget == null) {
            return LocalQuickFix.EMPTY_ARRAY;
        }
        PsiModifierList modifierList = declaration.getModifierList();
        List<LocalQuickFix> fixes = new ArrayList<>();
        for (String fqn : candidates) {
            if (modifierList != null && modifierList.hasAnnotation(fqn)) {
                continue;
            }
            PsiClass annotationType = JavaPsiFacade.getInstance(declaration.getProject())
                    .findClass(fqn, declaration.getResolveScope());
            if (annotationType == null || !annotationType.isAnnotationType()) {
                continue;
            }
            if (!permitsTarget(annotationType, requiredTarget)) {
                continue;
            }
            fixes.add(new AnnotateAsTestFacingFix(fqn));
        }
        return fixes.isEmpty() ? LocalQuickFix.EMPTY_ARRAY : fixes.toArray(LocalQuickFix.EMPTY_ARRAY);
    }

    @Nullable
    private static String requiredTargetFor(@NotNull PsiModifierListOwner declaration) {
        if (declaration instanceof PsiMethod) {
            return "METHOD";
        }
        if (declaration instanceof PsiField) {
            return "FIELD";
        }
        if (declaration instanceof PsiClass) {
            return "TYPE";
        }
        return null;
    }

    /**
     * Reads {@code @Target} off the annotation type through plain PSI.
     * <p>
     * The platform has a helper for this, but it lives in the Java plugin's implementation jar and is
     * no longer there in current builds, which an open-ended {@code until-build} cannot afford.
     * Reading the meta-annotation directly costs a few lines and cannot move.
     * <p>
     * An annotation with no {@code @Target} is applicable to every declaration context, per JLS
     * 9.6.4.1 — so absence means yes, not no.
     */
    private static boolean permitsTarget(@NotNull PsiClass annotationType, @NotNull String requiredTarget) {
        PsiModifierList modifierList = annotationType.getModifierList();
        PsiAnnotation target = modifierList == null ? null : modifierList.findAnnotation(TARGET_ANNOTATION);
        if (target == null) {
            return true;
        }
        PsiAnnotationMemberValue value = target.findAttributeValue("value");
        if (value == null) {
            return true;
        }
        if (value instanceof PsiArrayInitializerMemberValue) {
            for (PsiAnnotationMemberValue element : ((PsiArrayInitializerMemberValue) value).getInitializers()) {
                if (requiredTarget.equals(elementTypeNameOf(element))) {
                    return true;
                }
            }
            return false;
        }
        return requiredTarget.equals(elementTypeNameOf(value));
    }

    /**
     * {@code ElementType.METHOD}, a bare {@code METHOD} under a static import and the fully qualified
     * form all have to yield the same name, which is what {@code getReferenceName} gives. The text
     * fallback covers anything that is not a reference expression at all.
     */
    @Nullable
    private static String elementTypeNameOf(@NotNull PsiAnnotationMemberValue value) {
        if (value instanceof PsiReferenceExpression) {
            return ((PsiReferenceExpression) value).getReferenceName();
        }
        String text = value.getText();
        int lastDot = text.lastIndexOf('.');
        return lastDot < 0 ? text : text.substring(lastDot + 1);
    }
}
