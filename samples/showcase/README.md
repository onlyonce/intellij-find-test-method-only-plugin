# Showcase

A tiny Java project used as sample input for the inspection. It has a real production source root and
a real test source root, which is the minimum needed for the inspection to say anything at all.

It serves three purposes at once, from one set of files:

1. **Documentation** — a browsable, compilable answer to "what does this actually catch?"
2. **Validation** — `ShowcaseInspectionTest` loads *these files* and asserts the findings are exactly
   the methods annotated `@ExpectedFinding`. Adding a case needs no test change; a case that stops
   being detected, or an exclusion that starts being reported, fails the build.
3. **Screenshot source** — see below.

It is compiled by the root build so it cannot rot into invalid Java, and is never packaged into the
plugin distribution.

## Reported

Methods annotated [`@ExpectedFinding`](src/main/java/showcase/ExpectedFinding.java), all in
[`OrderService`](src/main/java/showcase/OrderService.java):

| Method | Case |
|---|---|
| `grossTotalWithLegacyVat(long)` | public method whose only caller is a test class |
| `roundToCents(double)` | package-private helper reached only from tests |
| `describeForDiagnostics()` | caller is a plain helper class in the test root, not a `@Test` method — the verdict follows the source root, not naming or annotations |
| `discount(long, int)` | one overload is test-only while its sibling `discount(long)` stays in production use |
| `toCents(long)` | static method reached only from tests |

## Not reported

| Method | Why not |
|---|---|
| `OrderService.netTotal(long, int)` | called from `ProductionCaller` — the control case |
| `OrderService.discount(long)` | in production use; its unused overload is reported instead |
| `Exclusions.neverCalledByAnyone()` | no callers at all. That is plain dead code and belongs to the built-in *Unused declaration* inspection — keeping the two distinct is the point of this plugin |
| `Exclusions()` constructor | construction is not the API this inspection is about; reporting it would flag most value types |
| `Exclusions.main(String[])` | application entry point, called by the JVM |
| `Money.cents()` | record accessor — generated from the component list, so "delete it" is not an available fix |
| `LoudGreeter.greet()` | production calls it through the `Greeter` interface. Without taking the verdict across the override family, every `@Override` in a codebase would be reported |

## Two exclusions this showcase deliberately does not demonstrate

**Framework entry points** — Spring `@Bean`, `@KafkaListener`, `@Scheduled`, JPA, Jackson. Showing
them would mean putting those frameworks on the sample classpath. They are handled by delegating to
the platform's registered entry points rather than a hand-rolled list, so they follow whatever the
host IDE knows about.

**Annotated methods** — a method marked with anything in the inspection's *Do not report methods
annotated with* list is skipped. Demonstrating it with the real `@TestOnly` would require
`org.jetbrains:annotations` here, and the test fixture resolves that annotation through
`MavenDependencyUtil.addFromMaven(...)` — a network dependency at test time. A flaky offline build is
not worth a demo case.

Both are covered instead by `TestOnlyMethodInspectionTest`, which asserts the annotation setting in
both directions using a project-local annotation — proving the behaviour is driven by the setting
rather than by any hard-coded name.

## The overload case earned its place

`discount(long, int)` was **not** detected when the showcase was first run. The confirmation search
used `strictSignatureSearch = false`, which looked like the cautious choice but let a production call
to `discount(long)` mask the unused two-argument overload. The showcase caught it immediately; the
search is now strict. That is the difference between a sample folder and a sample folder with an
assertion attached to it.

## Using it for a screenshot

```bash
./gradlew runIde
```

In the sandbox IDE, open this directory (`samples/showcase`) as a Gradle project, then:

1. **Settings → Editor → Inspections** → enable *Method used only from test code*
2. **Analyze → Inspect Code…** → scope **Whole project**, **Include test sources** ticked
3. Results appear under `Java → Declaration redundancy`

Expand the tree so all five findings are visible. Marketplace wants at least 1200 × 760.
