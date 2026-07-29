package showcase;

/**
 * Plain classes, no test framework on the classpath — the inspection keys off the source root, not
 * off JUnit annotations, and this keeps the sample buildable anywhere.
 */
public class OrderServiceTest {

    public void exercisesTheTestOnlyApi() {
        OrderService service = new OrderService();

        // These calls are the only reason the methods below still exist.
        service.grossTotalWithLegacyVat(1000);
        service.roundToCents(12.345);
        service.discount(1000, 15);
        OrderService.toCents(20);

        // Production also uses these two, so they must stay unreported.
        service.netTotal(100, 3);
        service.discount(1000);
    }

    public void exercisesTheExclusions() {
        // Constructor: only tests instantiate it.
        Exclusions exclusions = new Exclusions();
        System.out.println(exclusions);

        // Record accessor: only tests read it.
        Money money = new Money(1999);
        System.out.println(money.cents());

        // Implementation named directly, but production calls it through the interface.
        System.out.println(new LoudGreeter().greet());
    }
}
