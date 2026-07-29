package dev.onlyonce.testonly;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.options.JavaClassValidator;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.GlobalInspectionTool;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionsBundle;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptionsProcessor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.QuickFix;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.codeInspection.reference.RefClass;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefField;
import com.intellij.codeInspection.reference.RefGraphAnnotator;
import com.intellij.codeInspection.reference.RefManager;
import com.intellij.codeInspection.reference.RefMethod;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopesCore;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports production declarations whose every caller lives in a test source root.
 * <p>
 * Two stages. Stage one is a cheap filter riding on the reference graph the platform builds once for
 * the whole analysis scope. Stage two re-checks only the survivors against the real index, which is
 * what catches references the Java reference graph never sees — Spring XML, SpEL, {@code .properties}
 * wiring.
 * <p>
 * The class name still says "method" because the short name does, and the short name is the
 * suppression id and the key this tool is stored under in every existing inspection profile. Changing
 * it would silently reset the setting for everyone who had already enabled it.
 */
public final class TestOnlyMethodInspection extends GlobalInspectionTool {

    private static final Logger LOG = Logger.getInstance(TestOnlyMethodInspection.class);

    static final String SHORT_NAME = "MethodUsedOnlyFromTests";

    /**
     * Annotations that suppress reporting, on the declaration itself or on any class enclosing it.
     * <p>
     * Two kinds are seeded. The first marks a declaration as knowingly test-facing: it has already been
     * acknowledged by whoever wrote it, so reporting it again is noise — and would be loudest on the
     * codebases most disciplined about marking such declarations.
     * <p>
     * The second marks generated code. A finding there is not actionable, because deleting the code
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

    /**
     * Annotations the quick fix offers to write. A deliberate subset of {@link #ignoredAnnotations} —
     * see {@link TestFacingAnnotations} for why the two lists are not the same one.
     */
    public List<String> quickFixAnnotations = new ArrayList<>(List.of(
            "org.jetbrains.annotations.TestOnly",
            "org.jetbrains.annotations.VisibleForTesting",
            "com.google.common.annotations.VisibleForTesting"
    ));

    /** Public and non-final so the inspection profile can serialise them. */
    public boolean reportClasses = true;
    public boolean reportFields = true;

    private volatile RunState state = new RunState();

    /**
     * Everything derived from one analysis run, replaced as a unit.
     * <p>
     * The caller tally and the memo built on top of it are only meaningful together — a memo carried
     * over to a fresh graph would answer from the previous project state — so they are swapped in one
     * assignment rather than as two fields that could drift apart.
     */
    private static final class RunState {
        final CallerOrigins origins = new CallerOrigins();
        final Map<RefClass, Boolean> reportedClasses = new ConcurrentHashMap<>();
    }

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
                OptPane.checkbox("reportClasses", "Report whole classes used only from test code"),
                OptPane.checkbox("reportFields", "Report fields used only from test code"),
                OptPane.stringList("ignoredAnnotations",
                        "Do not report declarations annotated with:",
                        new JavaClassValidator().annotationsOnly().withTitle("Choose annotation")),
                OptPane.stringList("quickFixAnnotations",
                        "Offer a quick fix that annotates with:",
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
        RunState freshState = new RunState();
        state = freshState;
        Project project = refManager.getProject();
        return new RefGraphAnnotator() {
            @Override
            public void onMarkReferenced(RefElement refWhat, RefElement refFrom, boolean referencedFromClassInitializer) {
                freshState.origins.record(refWhat, refFrom, project);
            }
        };
    }

    @Override
    public void runInspection(@NotNull AnalysisScope scope,
                              @NotNull InspectionManager manager,
                              @NotNull GlobalInspectionContext globalContext,
                              @NotNull ProblemDescriptionsProcessor problemDescriptionsProcessor) {
        if (!scope.isIncludeTestSource()) {
            // Without test sources in the graph every test-only declaration has zero callers and is
            // indistinguishable from fully dead code. Reporting nothing is correct; reporting
            // silently is not.
            LOG.info("'" + SHORT_NAME + "' skipped: analysis scope '" + scope.getDisplayName()
                    + "' excludes test sources, so test-only usage cannot be distinguished from no usage.");
            RefEntity refProject = globalContext.getRefManager().getRefProject();
            problemDescriptionsProcessor.addProblemElement(refProject, manager.createProblemDescriptor(
                    "Inspection 'Declaration used only from test code' was skipped: the analysis scope excludes "
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
        RunState currentState = state;
        if (refEntity instanceof RefClass) {
            return checkClass((RefClass) refEntity, manager, currentState);
        }
        if (refEntity instanceof RefField) {
            return checkField((RefField) refEntity, manager, currentState);
        }
        if (refEntity instanceof RefMethod) {
            return checkMethod((RefMethod) refEntity, manager, currentState);
        }
        return null;
    }

    private CommonProblemDescriptor @Nullable [] checkClass(@NotNull RefClass refClass,
                                                            @NotNull InspectionManager manager,
                                                            @NotNull RunState currentState) {
        if (!reportClasses || !isReportedClass(refClass, currentState)) {
            return null;
        }
        PsiClass psiClass = TestOnlyClassDetector.asPsiClass(refClass);
        if (psiClass == null) {
            return null;
        }
        return describe(manager, psiClass.getNameIdentifier(), psiClass,
                "Class '" + psiClass.getName() + "' is used only from test code");
    }

    private CommonProblemDescriptor @Nullable [] checkField(@NotNull RefField refField,
                                                            @NotNull InspectionManager manager,
                                                            @NotNull RunState currentState) {
        if (!reportFields
                || !TestOnlyFieldDetector.isUsedOnlyFromTests(refField, currentState.origins, ignoredAnnotations)) {
            return null;
        }
        PsiField psiField = TestOnlyFieldDetector.asPsiField(refField);
        if (psiField == null || !psiField.isValid()) {
            return null;
        }
        if (isCoveredByReportedClass(refField.getOwnerClass(), currentState)) {
            return null;
        }
        if (hasProductionReference(psiField)) {
            return null;
        }
        return describe(manager, psiField.getNameIdentifier(), psiField,
                "Field '" + psiField.getName() + "' is used only from test code");
    }

    private CommonProblemDescriptor @Nullable [] checkMethod(@NotNull RefMethod refMethod,
                                                             @NotNull InspectionManager manager,
                                                             @NotNull RunState currentState) {
        if (!TestOnlyMethodDetector.isUsedOnlyFromTests(refMethod, currentState.origins, ignoredAnnotations)) {
            return null;
        }
        PsiMethod psiMethod = TestOnlyMethodDetector.asPsiMethod(refMethod);
        if (psiMethod == null || !psiMethod.isValid()) {
            return null;
        }
        if (isCoveredByReportedClass(refMethod.getOwnerClass(), currentState)) {
            return null;
        }
        if (hasProductionReference(psiMethod)) {
            return null;
        }
        return describe(manager, psiMethod.getNameIdentifier(), psiMethod,
                "Method '" + psiMethod.getName() + "()' is used only from test code");
    }

    /**
     * @param anchor      the name identifier, so the finding highlights the name rather than the whole
     *                    declaration; falls back to the declaration when there is none.
     * @param declaration what the quick fix will annotate.
     */
    private CommonProblemDescriptor[] describe(@NotNull InspectionManager manager,
                                               @Nullable PsiElement anchor,
                                               @NotNull PsiModifierListOwner declaration,
                                               @NotNull String message) {
        LocalQuickFix[] fixes = TestFacingAnnotations.fixesFor(declaration, quickFixAnnotations);
        return new CommonProblemDescriptor[]{
                manager.createProblemDescriptor(anchor != null ? anchor : declaration, message, false,
                        fixes, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        };
    }

    /**
     * Whether a member sits inside a class that is itself being reported.
     * <p>
     * Without this, a class kept alive only by its tests produces one finding per method and field it
     * declares, burying the one finding that matters. The verdict is recomputed rather than read from
     * what has already been reported, because {@code RefManager} visits entities in unspecified order
     * and the class may not have been reached yet.
     */
    private boolean isCoveredByReportedClass(@Nullable RefClass owner, @NotNull RunState currentState) {
        if (!reportClasses || owner == null) {
            return false;
        }
        RefClass topLevel = owner;
        for (RefEntity enclosing = owner.getOwner(); enclosing instanceof RefClass;
             enclosing = enclosing.getOwner()) {
            topLevel = (RefClass) enclosing;
        }
        return isReportedClass(topLevel, currentState);
    }

    /**
     * Memoised because every member of a candidate class asks the same question, and the answer costs
     * an index search per member of that class.
     * <p>
     * Deliberately not {@code computeIfAbsent}: that holds the map's bin lock for the whole mapping
     * function, and this one fans out to a search per member. Blocking other threads on an index
     * search under a lock is a stall no fixture here could surface, because they run single-threaded
     * while {@code checkElement} in the IDE does not. Losing a race costs one duplicate search and
     * both answers agree, which is the cheaper end of the trade.
     */
    private boolean isReportedClass(@NotNull RefClass refClass, @NotNull RunState currentState) {
        Boolean memoised = currentState.reportedClasses.get(refClass);
        if (memoised != null) {
            return memoised;
        }
        boolean verdict = computeIsReportedClass(refClass, currentState);
        Boolean raced = currentState.reportedClasses.putIfAbsent(refClass, verdict);
        return raced != null ? raced : verdict;
    }

    private boolean computeIsReportedClass(@NotNull RefClass refClass, @NotNull RunState currentState) {
        if (!TestOnlyClassDetector.isUsedOnlyFromTests(refClass, currentState.origins, ignoredAnnotations)) {
            return false;
        }
        PsiClass psiClass = TestOnlyClassDetector.asPsiClass(refClass);
        if (psiClass == null || !psiClass.isValid()) {
            return false;
        }
        return !hasExternalProductionReference(psiClass);
    }

    /**
     * Stage two: confirm the graph's verdict against the real index.
     * <p>
     * This is what catches references the Java reference graph never records — Spring XML, SpEL,
     * {@code .properties} wiring, javadoc {@code @link}. It runs only for declarations stage one
     * already flagged, so the per-declaration index search stays affordable on a large project.
     * <p>
     * Deliberately done here rather than in {@code queryExternalUsagesRequests} +
     * {@code ProblemDescriptionsProcessor.ignoreElement}: {@code ignoreElement} is a
     * <em>default no-op</em> on the interface, overridden by {@code DefaultInspectionToolPresentation}
     * (the UI path) but not by {@code DefaultInspectionToolResultExporter} (the headless path). Going
     * that way would have worked in the IDE and silently reported false positives under
     * {@code inspect.sh} and Qodana.
     */
    private static boolean hasProductionReference(@NotNull PsiMethod psiMethod) {
        // strictSignatureSearch = true: match only references that resolve to *this* method.
        //
        // The looser setting is tempting as "conservative", but it suppresses overloads — a
        // production call to discount(long) hides an unused discount(long, int), and the finding is
        // silently lost. It is not needed for correctness either: references arriving through a
        // super or overriding declaration are already accounted for in stage one, which takes the
        // verdict across the whole override family. Stage two only has to catch references the
        // reference graph never saw, and those resolve to the exact method.
        return MethodReferencesSearch.search(psiMethod, productionScope(psiMethod), true).findFirst() != null;
    }

    private static boolean hasProductionReference(@NotNull PsiField psiField) {
        return ReferencesSearch.search(psiField, productionScope(psiField)).findFirst() != null;
    }

    /**
     * Stage two for a class, which has to ask a wider question than a member does: is there any
     * production reference to the class <em>or to anything it declares</em>, from outside the class
     * itself.
     * <p>
     * The members matter because a constant reached through a static import, or an inherited method
     * called on a subclass, is a use of this class that never names it at the reference site. The
     * "outside itself" part matters for the reason given in {@link TestOnlyClassDetector}: a class
     * referring to its own members is the class existing, not the class being used.
     */
    private static boolean hasExternalProductionReference(@NotNull PsiClass psiClass) {
        GlobalSearchScope scope = productionScope(psiClass);
        if (hasReferenceOutside(psiClass, psiClass, scope)) {
            return true;
        }
        for (PsiMember member : membersOf(psiClass)) {
            if (hasReferenceOutside(member, psiClass, scope)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasReferenceOutside(@NotNull PsiElement target,
                                               @NotNull PsiClass boundary,
                                               @NotNull GlobalSearchScope scope) {
        // forEach stops — and returns false — as soon as the processor does. Continuing means the
        // reference was inside the boundary and therefore does not count.
        //
        // The processor is a named local rather than an inline lambda because Query inherits
        // Iterable.forEach as well, and the two overloads are ambiguous for a lambda argument.
        Processor<PsiReference> outsideBoundary =
                reference -> PsiTreeUtil.isAncestor(boundary, reference.getElement(), false);
        return !ReferencesSearch.search(target, scope).forEach(outsideBoundary);
    }

    /** Every method, field and nested class declared anywhere inside {@code psiClass}. */
    private static List<PsiMember> membersOf(@NotNull PsiClass psiClass) {
        List<PsiMember> members = new ArrayList<>();
        collectMembers(psiClass, members);
        return members;
    }

    private static void collectMembers(@NotNull PsiClass psiClass, @NotNull List<PsiMember> into) {
        Collections.addAll(into, psiClass.getMethods());
        Collections.addAll(into, psiClass.getFields());
        for (PsiClass nested : psiClass.getInnerClasses()) {
            into.add(nested);
            collectMembers(nested, into);
        }
    }

    private static @NotNull GlobalSearchScope productionScope(@NotNull PsiElement element) {
        return GlobalSearchScopesCore.projectProductionScope(element.getProject());
    }

    /**
     * Paired with {@link #getQuickFix(String)} so a fix survives a round trip through an offline
     * result file: the exporter writes this hint into the XML, and the viewer rebuilds the fix from it.
     * The annotation name is the fix's entire state, which is what makes that possible.
     */
    @Override
    public @Nullable String getHint(@NotNull QuickFix fix) {
        return fix instanceof AnnotateAsTestFacingFix ? ((AnnotateAsTestFacingFix) fix).getAnnotationFqn() : null;
    }

    @Override
    public @Nullable QuickFix getQuickFix(String hint) {
        return hint == null || hint.isEmpty() ? null : new AnnotateAsTestFacingFix(hint);
    }

    @Override
    public void cleanup(@NotNull Project project) {
        state = new RunState();
        super.cleanup(project);
    }
}
