package dev.onlyonce.testonly;

import com.intellij.codeInspection.reference.RefField;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Decides whether a single {@link RefField} is a production field read or written only from test
 * code — the classic field widened from {@code private} so a test could reach it, whose production
 * justification has since gone.
 * <p>
 * Fields have no override family, so unlike a method the tally is taken on the declaration alone.
 * References from inside the declaring class count as production use, exactly as they do for
 * methods: a field maintained by a production method <em>is</em> used in production, whatever else
 * reads it.
 * <p>
 * That last rule is enforced twice. Any intra-class reference from a production file is also a
 * reference within the production search scope, so the index confirmation in
 * {@code TestOnlyMethodInspection} would catch it even if the tally here did not — and the two can
 * never disagree. The tally is still taken this way rather than on the external bits, because it is
 * the cheaper of the two and shortlisting correctly saves an index search per field. Breaking either
 * one alone leaves the tests green; that is the redundancy, not a gap.
 */
final class TestOnlyFieldDetector {

    private TestOnlyFieldDetector() {
    }

    static boolean isUsedOnlyFromTests(@NotNull RefField refField,
                                       @NotNull CallerOrigins origins,
                                       @NotNull Collection<String> ignoredAnnotations) {
        PsiField psiField = asPsiField(refField);
        if (psiField == null || isExcluded(psiField)) {
            return false;
        }
        if (TestOnlyRules.isInTestSources(psiField)) {
            return false;
        }
        if (TestOnlyRules.isAcknowledgedAsTestFacing(psiField, ignoredAnnotations, false)) {
            return false;
        }
        if (refField.isEntry() || TestOnlyRules.isEntryPoint(psiField)) {
            return false;
        }

        CallerOrigins.Origins fieldOrigins = origins.get(refField);
        if (fieldOrigins == null) {
            return false;
        }
        // No callers at all is plain dead code — that belongs to the Unused declaration inspection.
        return fieldOrigins.hasTestCaller() && !fieldOrigins.hasProductionCaller();
    }

    /**
     * Two kinds of field are skipped outright.
     * <p>
     * An <b>enum constant</b> can be produced at runtime without ever being named — by
     * {@code valueOf}, by a deserializer, by a database column mapping — while the only source
     * reference to it is the test that asserts on it. Reporting those would be a false positive the
     * user cannot disprove from the code in front of them.
     * <p>
     * A <b>record component's backing field</b> is not a declaration anyone can act on; its accessor
     * is already excluded for the same reason.
     */
    private static boolean isExcluded(@NotNull PsiField psiField) {
        if (psiField instanceof PsiEnumConstant) {
            return true;
        }
        PsiClass owner = psiField.getContainingClass();
        return owner != null && owner.isRecord() && !psiField.hasModifierProperty(PsiModifier.STATIC);
    }

    @Nullable
    static PsiField asPsiField(@NotNull RefField refField) {
        PsiElement psi = refField.getPsiElement();
        return psi instanceof PsiField ? (PsiField) psi : null;
    }
}
