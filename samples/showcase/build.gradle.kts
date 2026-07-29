plugins {
    id("java")
}

// Compiled by the root build so the showcase cannot rot into invalid Java, but never packaged into
// the plugin distribution — it is sample input, not plugin code.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.test {
    // The classes under src/test/java here are sample input for the inspection — they exist to be a
    // real test source root, not to be executed. The assertions about them live in the plugin's own
    // ShowcaseInspectionTest.
    enabled = false
}
