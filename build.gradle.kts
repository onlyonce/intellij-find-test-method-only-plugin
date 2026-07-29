import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.onlyonce"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2023.3.8")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        // DefaultLightProjectDescriptor / LightJavaCodeInsightFixtureTestCase live in the Java
        // plugin's test framework, not the platform one.
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
    // The platform test framework runs on JUnit 4 but resolves JUnit 5 engine classes at startup.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

java {
    toolchain {
        // Branch 261 (2026.1) targets Java 21 — not the local JDK 25.
        languageVersion = JavaLanguageVersion.of(17)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // Verify every release in the declared compatibility range, not just the latest —
            // since-build is a promise and this is the only thing that checks it.
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaCommunity)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "233"
                // No untilBuild: since-build is open-ended, so verify every release up to the newest.
            }
            // The release selector currently tops out at 2025.2. Point this at a locally installed
            // newer IDE to also prove the upper end of the open-ended range.
            providers.gradleProperty("localIdePath").orNull?.let { local(it) }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnit()
}
