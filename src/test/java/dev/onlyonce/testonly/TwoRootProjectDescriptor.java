package dev.onlyonce.testonly;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.LanguageLevelModuleExtension;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaSourceRootType;

/**
 * A light project with a real second source root marked as test sources.
 * <p>
 * The stock descriptors give a single {@code /src} root, which makes this inspection untestable:
 * without a genuine test source root every caller looks like production and nothing is ever
 * reported.
 * <p>
 * {@code LightJavaCodeInsightFixtureTestCase.ProjectDescriptor} would have supplied the language
 * level for free, but it is a protected member type and cannot be extended from here — hence the
 * explicit mock JDK and {@link LanguageLevelModuleExtension} wiring, which is what that class does
 * internally anyway.
 */
public final class TwoRootProjectDescriptor extends DefaultLightProjectDescriptor {

    /** Directory name of the test source root, a sibling of {@code /src} under {@code temp:///}. */
    static final String TEST_ROOT = "testSrc";

    // JDK 17 rather than 21: records (the only modern construct the fixtures need) are final since
    // Java 16, and getMockJdk17() has a stable signature across the whole supported IDE range —
    // getMockJdk(LanguageLevel) does not exist before 2024.x.
    private static final LanguageLevel LANGUAGE_LEVEL = LanguageLevel.JDK_17;

    public TwoRootProjectDescriptor() {
        super(IdeaTestUtil::getMockJdk17);
    }

    @Override
    public void configureModule(@NotNull Module module,
                                @NotNull ModifiableRootModel model,
                                @NotNull ContentEntry contentEntry) {
        super.configureModule(module, model, contentEntry);
        model.getModuleExtension(LanguageLevelModuleExtension.class).setLanguageLevel(LANGUAGE_LEVEL);

        // createSourceRoot places the directory at temp:///<name> — a sibling of temp:///src, not a
        // child — so it needs its own content entry rather than a source folder inside the existing one.
        VirtualFile testRoot = createSourceRoot(module, TEST_ROOT);
        model.addContentEntry(testRoot).addSourceFolder(testRoot, JavaSourceRootType.TEST_SOURCE);
    }
}
