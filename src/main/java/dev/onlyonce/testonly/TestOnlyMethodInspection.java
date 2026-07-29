package dev.onlyonce.testonly;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.GlobalInspectionTool;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionsBundle;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptionsProcessor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInsight.options.JavaClassValidator;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefGraphAnnotator;
import com.intellij.codeInspection.reference.RefManager;
import com.intellij.codeInspection.reference.RefMethod;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopesCore;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports production methods whose every caller lives in a test source root.
 * <p>
 * Two stages. Stage one is a cheap filter riding on the reference graph the platform builds once for
 * the whole analysis scope. Stage two re-checks only the survivors against the real index, which is
 * what catches references the Java reference graph never sees — Spring XML, SpEL, {@code .properties}
 * wiring.
 */
public final class TestOnlyMethodInspection extends GlobalInspectionTool {

    private static final Logger LOG = Logger.getInstance(TestOnlyMethodInspection.class);

    static final String SHORT_NAME = "MethodUsedOnlyFromTests";

    /**
     * Annotations that suppress reporting, on the method itself or on its containing class.
     * <p>
     * Two kinds are seeded. The first marks a method as knowingly test-facing: it has already been
     * acknowledged by whoever wrote it, so reporting it again is noise — and would be loudest on the
     * codebases most disciplined about marking such methods.
     * <p>
     * The second marks generated code. A finding there is not actionable, because deleting the method
     * only means the next build writes it back, and generators such as Avro emit large surfaces of
     * accessors and builders that typically only tests touch. Note this is not the same as a
     * <i>generated source root</i>: {@code AnalysisScope} already excludes those outright, so the
     * cases that reach this inspection are exactly the ones the project model does <em>not</em> know
     * are generated — which is why an annotation check is the mechanism that helps and a
     * {@code GeneratedSourcesFilter} check would be a no-op.
     * <p>
     * Editable in the inspection settings rather than hard-coded: teams have their own conventions
     * and their own generators, and the names below are only a starting point.
     * <p>
     * Public and non-final so the inspection profile can serialise it.
     */
    public List<String> ignoredAnnotations = new ArrayList<>(List.of(
            // knowingly test-facing
            "org.jetbrains.annotations.TestOnly",
            "org.jetbrains.annotations.VisibleForTesting",
            "com.google.common.annotations.VisibleForTesting",
            // generated code
            "javax.annotation.Generated",
            "javax.annotation.processing.Generated",
            "jakarta.annotation.Generated",
            "org.apache.avro.specific.AvroGenerated"
    ));

    private volatile CallerOrigins origins = new CallerOrigins();

    /**
     * Normally supplied by the {@code globalInspection} extension point. Overridden so the tool is
     * also self-describing when instantiated directly — without this, any code path that asks for the
     * group (Qodana's inspection reporter, for one) logs a plugin error.
     */
    @Override
    public @NotNull String getGroupDisplayName() {
        return InspectionsBundle.message("group.names.declaration.redundancy");
    }

    @Override
    public @NotNull String getShortName() {
        return SHORT_NAME;
    }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return OptPane.pane(
                OptPane.stringList("ignoredAnnotations",
                        "Do not report methods annotated with:",
                        new JavaClassValidator().annotationsOnly().withTitle("Choose annotation"))
        );
    }

    @Override
    public boolean isGraphNeeded() {
        return true;
    }

    @Override
    public boolean isReadActionNeeded() {
        return true;
    }

    /**
     * The only place caller origins are collected.
     * <p>
     * {@code RefManagerImpl} dispatches the five- and six-argument {@code onMarkReferenced} overloads,
     * and {@link RefGraphAnnotator}'s own defaults chain those down to this three-argument one — so
     * this single override sees every reference. ({@code onMarkReferenced(PsiElement, PsiElement,
     * boolean)} is a separate branch and does not feed into it.)
     */
    @Override
    public @Nullable RefGraphAnnotator getAnnotator(@NotNull RefManager refManager) {
        CallerOrigins freshOrigins = new CallerOrigins();
        origins = freshOrigins;
        Project project = refManager.getProject();
        return new RefGraphAnnotator() {
            @Override
            public void onMarkReferenced(RefElement refWhat, RefElement refFrom, boolean referencedFromClassInitializer) {
                freshOrigins.record(refWhat, refFrom, project);
            }
        };
    }

    @Override
    public void runInspection(@NotNull AnalysisScope scope,
                              @NotNull InspectionManager manager,
                              @NotNull GlobalInspectionContext globalContext,
                              @NotNull ProblemDescriptionsProcessor problemDescriptionsProcessor) {
        if (!scope.isIncludeTestSource()) {
            // Without test sources in the graph every test-only method has zero callers and is
            // indistinguishable from fully dead code. Reporting nothing is correct; reporting
            // silently is not.
            LOG.info("'" + SHORT_NAME + "' skipped: analysis scope '" + scope.getDisplayName()
                    + "' excludes test sources, so test-only usage cannot be distinguished from no usage.");
            RefEntity refProject = globalContext.getRefManager().getRefProject();
            problemDescriptionsProcessor.addProblemElement(refProject, manager.createProblemDescriptor(
                    "Inspection 'Method used only from test code' was skipped: the analysis scope excludes "
                            + "test sources. Re-run Inspect Code on a scope that includes test sources."));
            return;
        }
        super.runInspection(scope, manager, globalContext, problemDescriptionsProcessor);
    }

    @Override
    public CommonProblemDescriptor @Nullable [] checkElement(@NotNull RefEntity refEntity,
                                                             @NotNull AnalysisScope scope,
                                                             @NotNull InspectionManager manager,
                                                             @NotNull GlobalInspectionContext globalContext) {
        if (!(refEntity instanceof RefMethod)) {
            return null;
        }
        RefMethod refMethod = (RefMethod) refEntity;
        if (!TestOnlyMethodDetector.isUsedOnlyFromTests(refMethod, origins, ignoredAnnotations)) {
            return null;
        }
        PsiMethod psiMethod = TestOnlyMethodDetector.asPsiMethod(refMethod);
        if (psiMethod == null || !psiMethod.isValid()) {
            return null;
        }
        if (hasProductionReference(psiMethod)) {
            return null;
        }

        PsiElement anchor = psiMethod.getNameIdentifier() != null ? psiMethod.getNameIdentifier() : psiMethod;
        String message = "Method '" + psiMethod.getName() + "()' is used only from test code";
        return new CommonProblemDescriptor[]{
                manager.createProblemDescriptor(anchor, message, (LocalQuickFix) null,
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING, false)
        };
    }

    /**
     * Stage two: confirm the graph's verdict against the real index.
     * <p>
     * This is what catches references the Java reference graph never records — Spring XML, SpEL,
     * {@code .properties} wiring, javadoc {@code @link}. It runs only for methods stage one already
     * flagged, so the per-method index search stays affordable on a large project.
     * <p>
     * Deliberately done here rather than in {@code queryExternalUsagesRequests} +
     * {@code ProblemDescriptionsProcessor.ignoreElement}: {@code ignoreElement} is a
     * <em>default no-op</em> on the interface, overridden by {@code DefaultInspectionToolPresentation}
     * (the UI path) but not by {@code DefaultInspectionToolResultExporter} (the headless path). Going
     * that way would have worked in the IDE and silently reported false positives under
     * {@code inspect.sh} and Qodana.
     */
    private static boolean hasProductionReference(@NotNull PsiMethod psiMethod) {
        GlobalSearchScope productionScope =
                GlobalSearchScopesCore.projectProductionScope(psiMethod.getProject());
        // strictSignatureSearch = true: match only references that resolve to *this* method.
        //
        // The looser setting is tempting as "conservative", but it suppresses overloads — a
        // production call to discount(long) hides an unused discount(long, int), and the finding is
        // silently lost. It is not needed for correctness either: references arriving through a
        // super or overriding declaration are already accounted for in stage one, which takes the
        // verdict across the whole override family. Stage two only has to catch references the
        // reference graph never saw, and those resolve to the exact method.
        return MethodReferencesSearch.search(psiMethod, productionScope, true).findFirst() != null;
    }

    @Override
    public void cleanup(@NotNull Project project) {
        origins = new CallerOrigins();
        super.cleanup(project);
    }
}
