# Showcase

A tiny Java project used as sample input for the inspection. It has a real production source root and
a real test source root, which is the minimum needed for the inspection to say anything at all.

It serves three purposes at once, from one set of files:

1. **Documentation** — a browsable, compilable answer to "what does this actually catch?"
2. **Validation** — `ShowcaseInspectionTest` loads *these files* and asserts the findings are exactly
   the declarations annotated `@ExpectedFinding`. Adding a case needs no test change; a case that
   stops being detected, or an exclusion that starts being reported, fails the build. "Exactly" also
   pins the roll-up rule: the members of a reported class carry no annotation of their own, so
   reporting them alongside their class fails here too.
3. **Screenshot source** — see below.

It is compiled by the root build so it cannot rot into invalid Java, and is never packaged into the
plugin distribution.

## Reported

Declarations annotated [`@ExpectedFinding`](src/main/java/showcase/ExpectedFinding.java):

| Declaration | Case |
|---|---|
| `OrderService.grossTotalWithLegacyVat(long)` | public method whose only caller is a test class |
| `OrderService.roundToCents(double)` | package-private helper reached only from tests |
| `OrderService.describeForDiagnostics()` | caller is a plain helper class in the test root, not a `@Test` method — the verdict follows the source root, not naming or annotations |
| `OrderService.discount(long, int)` | one overload is test-only while its sibling `discount(long)` stays in production use |
| `OrderService.toCents(long)` | static method reached only from tests |
| `OrderService.LEGACY_VAT_PERCENT` | constant read only from the test source root |
| `LegacyPricingTable` | a whole class nothing in production names. Its static factory returns its own type — an internal reference, which does not count, or no class would ever be reported |

## Not reported

| Declaration | Why not |
|---|---|
| `OrderService.netTotal(long, int)` | called from `ProductionCaller` — the control case |
| `OrderService.discount(long)` | in production use; its unused overload is reported instead |
| `OrderService.MAX_QUANTITY` | read from `ProductionCaller` — the control case for fields |
| `OrderService.lastNetTotal` | the test is its only reader, but `netTotal` writes it, and `netTotal` is production code. This is where fields differ from classes: a field's own class counts as production use |
| `LegacyPricingTable.lookup(int)`, `.createDefault()` | the class itself is reported, so its members are not — otherwise "delete the file" would be buried under its own methods |
| `Channel.WEB` | enum constant, named only in the test. `valueOf`, a deserializer or a column mapping can produce one without naming it, so "no production reference" proves nothing |
| `Exclusions.neverCalledByAnyone()` | no callers at all. That is plain dead code and belongs to the built-in *Unused declaration* inspection — keeping the two distinct is the point of this plugin |
| `Exclusions()` constructor | construction is not the API this inspection is about; reporting it would flag most value types |
| `Exclusions.main(String[])` | application entry point, called by the JVM. It also keeps the whole `Exclusions` class out of the class findings, since something outside the sources calls into it |
| `Money.cents()` | record accessor — generated from the component list, so "delete it" is not an available fix |
| `LoudGreeter.greet()` | production calls it through the `Greeter` interface. Without taking the verdict across the override family, every `@Override` in a codebase would be reported |
| `LoudGreeter` the class | `ProductionCaller.defaultGreeter()` constructs it. Without that line the honest verdict would be that the whole implementation exists for the tests — a different case from the one this file is here to show |

## Two exclusions this showcase deliberately does not demonstrate

**Framework entry points** — Spring `@Bean`, `@KafkaListener`, `@Scheduled`, JPA, Jackson. Showing
them would mean putting those frameworks on the sample classpath. They are handled by delegating to
the platform's registered entry points rather than a hand-rolled list, so they follow whatever the
host IDE knows about.

**Annotated declarations, and the quick fix that writes them** — a declaration marked with anything
in the inspection's *Do not report declarations annotated with* list is skipped, and the quick fix
offers to add one. Demonstrating either with the real `@TestOnly` would require
`org.jetbrains:annotations` here, and the test fixture resolves that annotation through
`MavenDependencyUtil.addFromMaven(...)` — a network dependency at test time. A flaky offline build is
not worth a demo case.

Both are covered instead by `TestOnlyMethodInspectionTest` and `AnnotateAsTestFacingFixTest`, which
assert the settings in both directions using project-local annotations — proving the behaviour is
driven by the setting rather than by any hard-coded name.

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

1. **Settings → Editor → Inspections** → enable *Declaration used only from test code*
2. **Analyze → Inspect Code…** → scope **Whole project**, **Include test sources** ticked
3. Results appear under `Java → Declaration redundancy`

Expand the tree so all seven findings are visible. Marketplace wants at least 1200 × 760.
