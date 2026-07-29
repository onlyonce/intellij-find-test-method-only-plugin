package dev.onlyonce.testonly;

import java.util.Set;

/**
 * The field rule. Unlike a class, a field's own declaring class counts as production use — a field a
 * production method maintains is in use however else it is read — so these tests pin the difference
 * as much as the rule.
 */
public class TestOnlyFieldInspectionTest extends InspectionFixtureTestCase {

    public void testReportsFieldReadOnlyFromTestSources() {
        production("Config.java", """
                public class Config {
                    public static final int LEGACY_LIMIT = 3;
                    public int usedInProduction() { return 1; }
                }
                """);
        production("ConfigCaller.java", """
                public class ConfigCaller {
                    public int go() { return new Config().usedInProduction(); }
                }
                """);
        inTestSources("ConfigTest.java", """
                public class ConfigTest {
                    public void checks() { System.out.println(Config.LEGACY_LIMIT); }
                }
                """);

        assertEquals(Set.of("field Config.LEGACY_LIMIT"), runInspection(true));
    }

    /**
     * The rule that separates fields from classes. Nothing outside {@code Counter} names
     * {@code count} except the test, but a production method writes it, so it is in use. A rule that
     * only looked outside the declaring class — which is what the class verdict does — would report
     * this, and would be wrong.
     * <p>
     * Two guards enforce it and either one alone is enough, so breaking just one leaves this test
     * green. That redundancy is real rather than an oversight — see {@link TestOnlyFieldDetector} —
     * and it is recorded here so the next person to mutation-check this does not read a survivor as
     * dead code.
     */
    public void testDoesNotReportFieldWrittenFromItsOwnProductionMethod() {
        production("Counter.java", """
                public class Counter {
                    int count;
                    public void increment() { count++; }
                }
                """);
        production("CounterCaller.java", """
                public class CounterCaller {
                    public void go() { new Counter().increment(); }
                }
                """);
        inTestSources("CounterTest.java", """
                public class CounterTest {
                    public void checks() {
                        Counter c = new Counter();
                        c.increment();
                        System.out.println(c.count);
                    }
                }
                """);

        assertEquals(Set.of(), runInspection(true));
    }

    /**
     * Stage two for fields. A javadoc {@code @link} is invisible to the reference graph, so the index
     * check is the only thing standing between this and a false positive — and unlike the intra-class
     * case above, nothing else covers it.
     */
    public void testDoesNotReportFieldReferencedFromProductionJavadoc() {
        production("Config.java", """
                public class Config {
                    public static final int LEGACY_LIMIT = 3;
                    public int usedInProduction() { return 1; }
                }
                """);
        production("Docs.java", """
                /** Superseded by the new limit, see {@link Config#LEGACY_LIMIT}. */
                public class Docs {
                    public int go() { return new Config().usedInProduction(); }
                }
                """);
        inTestSources("ConfigTest.java", """
                public class ConfigTest {
                    public void checks() { System.out.println(Config.LEGACY_LIMIT); }
                }
                """);

        assertEquals(Set.of(), runInspection(true));
    }

    /**
     * An enum constant can be produced without ever being named — by {@code valueOf}, a deserializer,
     * a column mapping — so "no production reference" says nothing about whether production uses it.
     * A finding the developer cannot disprove from the code in front of them is worse than none.
     */
    public void testDoesNotReportEnumConstant() {
        production("Channel.java", """
                public enum Channel { WEB, STORE }
                """);
        production("ChannelUser.java", """
                public class ChannelUser {
                    public Channel pick() { return Channel.STORE; }
                }
                """);
        inTestSources("ChannelTest.java", """
                public class ChannelTest {
                    public void checks() { System.out.println(Channel.WEB); }
                }
                """);

        assertEquals(Set.of(), runInspection(true));
    }

    public void testDoesNotReportRecordComponentBackingField() {
        production("Money.java", """
                public record Money(long cents) { }
                """);
        production("MoneyUser.java", """
                public class MoneyUser {
                    public Money make() { return new Money(1999); }
                }
                """);
        inTestSources("MoneyTest.java", """
                public class MoneyTest {
                    public void checks() { System.out.println(new Money(1).cents()); }
                }
                """);

        assertEquals(Set.of(), runInspection(true));
    }

    public void testDoesNotReportFieldWithoutAnyReader() {
        production("Config.java", """
                public class Config {
                    public static final int NEVER_READ = 3;
                    public int usedInProduction() { return 1; }
                }
                """);
        production("ConfigCaller.java", """
                public class ConfigCaller {
                    public int go() { return new Config().usedInProduction(); }
                }
                """);
        inTestSources("ConfigTest.java", """
                public class ConfigTest {
                    public void checks() { }
                }
                """);

        // No readers at all is plain dead code and belongs to the Unused declaration inspection.
        assertEquals(Set.of(), runInspection(true));
    }

    public void testReportFieldsOptionSwitchesTheRuleOff() {
        production("Config.java", """
                public class Config {
                    public static final int LEGACY_LIMIT = 3;
                    public int usedInProduction() { return 1; }
                }
                """);
        production("ConfigCaller.java", """
                public class ConfigCaller {
                    public int go() { return new Config().usedInProduction(); }
                }
                """);
        inTestSources("ConfigTest.java", """
                public class ConfigTest {
                    public void checks() { System.out.println(Config.LEGACY_LIMIT); }
                }
                """);

        assertEquals(Set.of("field Config.LEGACY_LIMIT"), runInspection(true));
        assertEquals(Set.of(), runInspection(true, i -> i.reportFields = false));
    }
}
