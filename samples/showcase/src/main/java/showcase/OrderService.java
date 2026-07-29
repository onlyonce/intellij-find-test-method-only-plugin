package showcase;

/**
 * Cases the inspection is expected to report, alongside the production-used methods that must stay
 * clean. Every reported case lives here so the screenshot shows them together.
 */
public class OrderService {

    /** Control: called from {@link ProductionCaller}, so it is genuinely in use. */
    public long netTotal(long unitPrice, int quantity) {
        return unitPrice * quantity;
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
