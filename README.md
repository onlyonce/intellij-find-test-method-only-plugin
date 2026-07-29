# Test-Only Method Detector

> 🤖 **100% vibecoded with Claude Opus 5.** Every line here — Java, Gradle, tests, this README — was
> written by the model. See [Provenance](#provenance).

An IntelliJ IDEA plugin that reports **production Java methods whose every caller lives in a test
source root**.

Such a method is production API kept alive only by the tests that assert on it: it inflates the public
surface, blocks refactoring, and still counts as covered in coverage reports.

## Why not just use *Unused declaration*?

IntelliJ can already compute this — *Settings → Editor → Inspections → Java → Declaration redundancy →
Unused declaration → Entry points → "When entry points are in test sources, mark callees as: unused"*.

But it works by **stripping entry-point status from everything in a test source root**. Your `@Test`
methods stop being entry points too, so they get reported as unused alongside the production methods
you care about — with the identical "never used" message. The distinction is computed and then
discarded. It is also a profile-wide setting shared with the on-the-fly unused-symbol inspection, so
enabling it changes the daily editor experience.

This plugin reports the same finding as its own category, with its own severity and suppression id,
leaving *Unused declaration* untouched.

## What it reports

A method is reported when **all** of the following hold:

- it is declared in a production source root
- it has at least one caller (a method with *no* callers is plain dead code — that is
  *Unused declaration*'s job)
- every reference to it, across its whole override family, comes from a test source root
- no reference to it exists anywhere in production, including from XML, SpEL, `.properties` and
  javadoc

### Excluded

Constructors, `main`, record accessors, methods overriding library declarations, methods declared in
test sources, and anything the platform considers an entry point. Entry points are resolved through
`UnusedDeclarationInspectionBase.isEntryPoint` and `EntryPointsManager`, so every registered
`EntryPoint` extension plus your *Settings → Editor → Inspections → Entry points* configuration is
honoured — Spring `@Bean` / `@KafkaListener` / `@Scheduled` / `@EventListener`, JPA no-arg
constructors and Jackson accessors are left alone.

## Usage

1. `Settings → Plugins → ⚙ → Install Plugin from Disk…` → the built zip → restart
2. `Settings → Editor → Inspections` → enable **Method used only from test code** (off by default)
3. `Analyze → Inspect Code…` → scope **Whole project**, **Include test sources** ticked
4. Results appear under `Java → Declaration redundancy`

> This is a global inspection. It runs only via *Inspect Code* — it will never highlight as you type.

**The analysis scope must include test sources.** Without them every test-only method has zero callers
and is indistinguishable from fully dead code, so the inspection refuses to run rather than guess.

## How it works

Two stages, because the cheap one is imprecise and the precise one is expensive.

1. **Graph pass.** The platform builds its `RefManager` reference graph once for the analysis scope.
   A `RefGraphAnnotator` records, per method, whether it was referenced from production, from tests,
   or both. This is done *during* graph construction rather than by reading `getInReferences()`
   afterwards — reference building is lazy and iteration order is unspecified, so a post-hoc read can
   see an incomplete caller set.
2. **Index confirmation.** Only for methods stage one flagged, a `MethodReferencesSearch` over
   `projectProductionScope` confirms the verdict. This catches references the Java reference graph
   never records: Spring XML, SpEL, `.properties` wiring, javadoc `@link`.

Because stage two runs over a shortlist rather than every method, the per-method index search stays
affordable on a large codebase.

## Building

Requires JDK 21 (the toolchain target for IntelliJ branch 261) — the Gradle wrapper handles the rest.

```bash
./gradlew build          # compile + test
./gradlew verifyPlugin   # binary compatibility check
./gradlew runIde         # sandbox IDE with the plugin loaded
```

The distributable lands in `build/distributions/`.

## Status

Built and tested against IntelliJ IDEA 2026.1.4 (IU-261.26222.65); the plugin verifier reports
compatible with 2026.1 and the 2026.2 EAP. Ten fixture tests cover the reporting and exclusion rules.

Not yet exercised: a headless `inspect.sh` / Qodana run, and a large real-world Spring codebase — both
entry-point coverage at scale and runtime are unmeasured there.

## Provenance

This plugin is **100% vibecoded with Claude Opus 5**, via Claude Code. No line of Java, Gradle
configuration, test code or documentation in this repository was written by hand. The human role was
to state the goal — *"find Java methods that are only called from test code"* — decide two design
questions (direct-caller vs. transitive semantics; inspection-only vs. inspection plus a report UI),
and review the result.

That is worth stating plainly rather than hiding, because "vibecoded" usually implies unverified. Here
is what was actually done to earn confidence:

- **The design was derived from decompiled platform bytecode, not from recollection.** The claim that
  IntelliJ's existing option is unusable rests on reading `RefJavaManagerImpl.isEntryPoint` out of
  `intellij.java.analysis.impl.jar`, not on documentation or memory.
- **Two real bugs were caught before they shipped.** `ProblemDescriptionsProcessor.ignoreElement`
  turned out to be a `default` no-op on the headless path — the first design would have worked in the
  IDE and silently produced false positives in CI. Separately, reading `getInReferences()` after graph
  construction can observe an incomplete caller set, because reference building is lazy.
- **The tests were mutation-checked, not just run green.** Each guard was verified by deliberately
  breaking the code and confirming that exactly the intended test failed. This mattered: under a
  broken annotator, six of the tests still passed — a suite of only "not reported" assertions would
  have been theatre.
- Ten fixture tests, `verifyPlugin` compatibility against two IDE builds.

Known gaps are recorded honestly in [Status](#status) rather than glossed over.

## Licence

[MIT](LICENSE) — © 2026 onlyonce. Use it, fork it, ship it; just keep the copyright notice.
