package showcase;

/**
 * Not a test class by any naming or annotation convention — just a helper that happens to live in
 * the test source root. It is the only caller of {@link OrderService#describeForDiagnostics()},
 * which is still reported: the verdict follows the source root, not what the class is called.
 */
public class DiagnosticsHelper {

    public String dump(OrderService service) {
        return service.describeForDiagnostics();
    }
}
