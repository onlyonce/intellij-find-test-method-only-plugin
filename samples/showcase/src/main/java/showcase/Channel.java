package showcase;

/**
 * Enum constants are never reported, and {@code WEB} is here to prove it: the only source reference
 * to it is in the test source root.
 * <p>
 * The reason is that naming a constant is not how production usually reaches one. {@code valueOf},
 * a JSON deserializer, a JPA enum column or a switch over {@code values()} all produce it without
 * mentioning it, so "no production reference" says nothing about whether production uses it — and a
 * finding the developer cannot disprove from the code in front of them is worse than no finding.
 * <p>
 * The enum type itself is referenced from production, so it is not a class finding either.
 */
public enum Channel {

    WEB,

    STORE
}
