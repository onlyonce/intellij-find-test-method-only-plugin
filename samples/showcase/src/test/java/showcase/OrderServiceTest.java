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

        // The only read of this constant anywhere.
        System.out.println(OrderService.LEGACY_VAT_PERCENT);

        // A whole class no production file names.
        System.out.println(LegacyPricingTable.createDefault().lookup(3));
    }

    public void exercisesTheFieldExclusions() {
        OrderService service = new OrderService();

        // The only read of this field, but netTotal writes it — production is using it.
        service.netTotal(100, 3);
        System.out.println(service.lastNetTotal);

        // ProductionCaller reads this one too, so it is in ordinary use.
        System.out.println(OrderService.MAX_QUANTITY);

        // Named only here — enum constants are never reported.
        System.out.println(Channel.WEB);
    }

    public void exercisesTheDeclarationExclusions() {
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
