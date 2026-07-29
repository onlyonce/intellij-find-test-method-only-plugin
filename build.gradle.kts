import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.onlyonce"
version = "0.3.2"

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
        // Branch 233 (2023.3), the lowest supported target, runs on Java 17. Building against the
        // floor rather than the newest SDK is what stops a newer API slipping in unnoticed.
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
    // Signing is optional: an unsigned plugin installs fine, the IDE just shows a warning dialog.
    // Both blocks read from the environment, so nothing secret ever lands in this file. The platform
    // plugin would pick these env vars up on its own — they are spelled out because an env var read by
    // a default is invisible to whoever next has to release this.
    //
    //   PRIVATE_KEY / PRIVATE_KEY_PASSWORD  openssl-generated key, see docs/RELEASING.md
    //   CERTIFICATE_CHAIN                   the matching self-signed certificate
    //   PUBLISH_TOKEN                       Marketplace permanent token
    //
    // publishPlugin only works for the *second* and later versions. Marketplace has no API to create a
    // plugin entry, so the first upload is manual — see docs/RELEASING.md.
    signing {
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A version carrying a pre-release suffix goes to a separate channel, so a "0.2.0-beta.1" can
        // never reach users who only subscribe to stable.
        channels = listOf(version.toString().substringAfter('-', "").substringBefore('.')
                .ifEmpty { "default" })
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
    // ShowcaseInspectionTest loads the sample sources from disk rather than embedding copies, so the
    // showcase, the documentation and the assertions cannot drift apart.
    systemProperty("showcase.dir", layout.projectDirectory.dir("samples/showcase").asFile.absolutePath)
    // ...which makes those sources a test input Gradle cannot see, because it tracks the value of the
    // system property and not what the directory contains. Without this, editing a showcase file and
    // re-running gives a cached pass against the previous contents — the one failure mode that would
    // let the showcase drift from its own assertions, which is the thing it exists to prevent.
    inputs.dir(layout.projectDirectory.dir("samples/showcase/src"))
            .withPropertyName("showcaseSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
