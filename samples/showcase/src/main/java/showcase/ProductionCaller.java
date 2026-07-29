package showcase;

/**
 * Production use of the control methods. Without this class the controls would have no callers at
 * all, which is a different verdict entirely — and the showcase would quietly stop proving anything.
 */
public class ProductionCaller {

    public long checkout(long unitPrice, int quantity) {
        OrderService service = new OrderService();
        long net = service.netTotal(Math.min(quantity, OrderService.MAX_QUANTITY) * unitPrice, 1);
        return net - service.discount(net);
    }

    public String greet(Greeter greeter) {
        return greeter.greet();
    }

    /**
     * Production names {@link LoudGreeter} here, which is what keeps it out of the class findings.
     * Without this the implementation would be constructed only by the test, and the honest verdict
     * would be that the whole class exists for the tests — a different case from the one
     * {@code LoudGreeter} is here to show.
     */
    public Greeter defaultGreeter() {
        return new LoudGreeter();
    }

    public Money price() {
        return new Money(1999);
    }

    public Channel defaultChannel() {
        return Channel.STORE;
    }
}
