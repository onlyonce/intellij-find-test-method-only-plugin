package showcase;

/**
 * Production use of the control methods. Without this class the controls would have no callers at
 * all, which is a different verdict entirely — and the showcase would quietly stop proving anything.
 */
public class ProductionCaller {

    public long checkout(long unitPrice, int quantity) {
        OrderService service = new OrderService();
        long net = service.netTotal(unitPrice, quantity);
        return net - service.discount(net);
    }

    public String greet(Greeter greeter) {
        return greeter.greet();
    }

    public Money price() {
        return new Money(1999);
    }
}
