plugins {
    // Lets Gradle download the JDK the toolchain asks for, so a contributor does not need the
    // exact version installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "find-test-only-methods"

// Sample input for the inspection: compiled so it stays valid Java, never packaged into the plugin.
include("samples:showcase")
