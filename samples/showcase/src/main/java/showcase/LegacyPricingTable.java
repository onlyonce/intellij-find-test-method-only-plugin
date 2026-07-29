package showcase;

/**
 * A whole class nothing in production refers to. Only the test source root names it, so the finding
 * is the file, not any one method in it.
 * <p>
 * The static factory is the point of this sample. It returns the class's own type, which is a
 * reference to {@code LegacyPricingTable} from inside {@code LegacyPricingTable} — and if that
 * counted as production use, no class would ever be reported, because builders, factories and
 * fluent setters all do it. References that stay inside the class are ignored.
 * <p>
 * Neither {@code lookup} nor {@code createDefault} carries {@code @ExpectedFinding}: when the class
 * itself is reported its members must not be, or the one finding that matters — delete the file —
 * would be buried under its own methods.
 */
@ExpectedFinding("whole class kept alive only by the test source root")
public class LegacyPricingTable {

    private final long baseCents;

    private LegacyPricingTable(long baseCents) {
        this.baseCents = baseCents;
    }

    public static LegacyPricingTable createDefault() {
        return new LegacyPricingTable(100);
    }

    public long lookup(int tier) {
        return baseCents * tier;
    }
}
