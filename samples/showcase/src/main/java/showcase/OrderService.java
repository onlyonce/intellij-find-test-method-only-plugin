package showcase;

/**
 * Cases the inspection is expected to report, alongside the production-used methods that must stay
 * clean. Every reported case lives here so the screenshot shows them together.
 */
public class OrderService {

    /**
     * A constant kept because a test asserts against it. Nothing in production reads it — the rate is
     * written out again as a literal below — so it is dead weight that still looks like public API.
     * <p>
     * Note the wording: a javadoc {@code @link} to a declaration is a production reference like any
     * other, and naming the method here would suppress its finding. That is the point of stage two,
     * and it applies to this file too.
     */
    @ExpectedFinding("constant read only from the test source root")
    public static final long LEGACY_VAT_PERCENT = 19;

    /** Control: read from {@link ProductionCaller}, so it is genuinely in use. */
    public static final long MAX_QUANTITY = 999;

    /**
     * Control for the rule that a field's own class counts as production use. The test reads this
     * one, so a rule that only looked outside the class would report it — but {@link #netTotal}
     * writes it, and {@code netTotal} is production code. A field a production method maintains is in
     * use, whatever else reads it.
     * <p>
     * This is where fields differ from classes: for a class, a reference from inside itself is the
     * class existing rather than the class being used, and is ignored.
     */
    long lastNetTotal;

    /** Control: called from {@link ProductionCaller}, so it is genuinely in use. */
    public long netTotal(long unitPrice, int quantity) {
        lastNetTotal = unitPrice * quantity;
        return lastNetTotal;
    }

    @ExpectedFinding("public method whose only caller is a test class")
    public long grossTotalWithLegacyVat(long net) {
        return net + net * 19 / 100;
    }

    @ExpectedFinding("package-private helper reached only from tests")
    long roundToCents(double amount) {
        return Math.round(amount * 100);
    }

    /**
     * The caller lives in a test source root but is an ordinary helper class, not a JUnit test.
     * The verdict follows the source root, not annotations or naming.
     */
    @ExpectedFinding("caller is a plain helper class in the test source root, not a @Test method")
    public String describeForDiagnostics() {
        return "OrderService";
    }

    /** Control: this overload is used in production. */
    public long discount(long amount) {
        return amount / 10;
    }

    /**
     * Same name, different signature. Overloads are resolved independently, so the unused one is
     * reported while the one above is not.
     */
    @ExpectedFinding("one overload is test-only while its sibling stays in production use")
    public long discount(long amount, int percent) {
        return amount * percent / 100;
    }

    @ExpectedFinding("static method reached only from tests")
    public static long toCents(long euros) {
        return euros * 100;
    }
}
