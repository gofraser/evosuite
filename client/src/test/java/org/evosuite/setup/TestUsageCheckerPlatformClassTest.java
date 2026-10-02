/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.setup;

import com.examples.with.different.packagename.deprecated.LiveAbstractBase;
import jdk.evosuitetestfixture.UserClassInJdkPrefixedPackage;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLPermission;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Pins the fix for {@link TestUsageChecker#isPlatformClass(Class)}: before this fix it
 * recognised only bootstrap-loaded classes ({@code getClassLoader() == null}) as JDK
 * types, which misses every class the module system moved onto the <em>platform</em>
 * loader since Java 9 (for example {@code java.sql.SQLPermission}). With
 * {@code USE_DEPRECATED=false}, a platform-loaded class the running JDK happens to
 * annotate {@code @Deprecated} was then wrongly treated as ordinary (non-JDK)
 * deprecated API and dropped from the test cluster.
 *
 * <p>Also pins the follow-up fix for the same method: classifying by named-module prefix
 * alone accepted any module merely <em>named</em> {@code java.*}/{@code jdk.*}, regardless
 * of who built or loaded it. {@code testUserModuleNamedLikeAJdkModuleIsNotPlatformClass}
 * compiles and loads such a module through a real {@link ModuleLayer} to confirm the fix
 * rejects it.
 *
 * <p>Also pins the counter-regression that fix introduced: it checked the defining
 * loader's identity (boot or platform loader), which rejected genuine JDK tool modules --
 * {@code jdk.jdi} among them -- that the JDK itself defines with the ordinary
 * system/application class loader. {@code testAppLoadedGenuineJdkModuleIsPlatformClass}
 * pins that such a module is still recognised as platform code.
 *
 * <p>The loader-identity check was then replaced with a check that the class's module is
 * resolved into {@code ModuleLayer.boot()} -- which fixed the above but is <em>also</em>
 * wrong: the boot layer is not exclusively populated from the JDK's own image. A module
 * placed on {@code --module-path} and named in {@code --add-modules} at JVM
 * <em>startup</em> (as opposed to one an application assembles into a new layer at run
 * time) is resolved into {@code ModuleLayer.boot()} too, the same as a genuine JDK
 * module. {@code testStartupResolvedUserModuleNamedLikeAJdkModuleIsNotPlatformClass}
 * launches exactly such a module in a child JVM -- an in-process {@link ModuleLayer}
 * cannot reproduce startup resolution -- to confirm the current check, which instead
 * traces the module's own reference through its own layer's {@code Configuration} to
 * confirm it was actually resolved from the running JDK's own system image (see
 * {@link JavaModuleLookup#isFromSystemImage(JavaModuleLookup.Result)}), rejects it too.
 */
class TestUsageCheckerPlatformClassTest {

    private final String defaultClassPrefix = Properties.CLASS_PREFIX;
    private final String defaultTargetClass = Properties.TARGET_CLASS;
    private final boolean defaultUseDeprecated = Properties.USE_DEPRECATED;

    @AfterEach
    void restoreProperties() {
        Properties.CLASS_PREFIX = defaultClassPrefix;
        Properties.TARGET_CLASS = defaultTargetClass;
        Properties.USE_DEPRECATED = defaultUseDeprecated;
        Properties.resetTargetClass();
    }

    /**
     * Sets up {@code target} as the loaded target class, so that
     * {@code isExemptFromDeprecationFilter} does not short-circuit to "exempt" just
     * because no target has been loaded yet (its behaviour when nothing is loaded).
     */
    private static void loadTarget(Class<?> target) {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        Properties.CLASS_PREFIX = target.getPackage().getName();
        Properties.TARGET_CLASS = target.getName();
        Properties.resetTargetClass();
        Class<?> loaded = Properties.getTargetClassAndDontInitialise();
        Assertions.assertNotNull(loaded, "fixture should be loadable");
        Assertions.assertTrue(Properties.hasTargetClassBeenLoaded(), "fixture should be loadable");
    }

    @Test
    void testBootstrapLoadedJdkClassIsPlatformClass() {
        // java.lang.String: classLoader == null, module java.base. True under both
        // the old loader check and the new module check; a baseline sanity check.
        Assertions.assertNull(String.class.getClassLoader());
        Assertions.assertTrue(TestUsageChecker.isPlatformClass(String.class));
    }

    @Test
    void testPlatformLoaderLoadedJdkClassIsPlatformClass() {
        // java.sql.SQLPermission is defined by the platform loader (a non-null,
        // non-bootstrap loader), not the bootstrap loader, on every JDK since
        // modularization. The old getClassLoader() == null check misclassified it
        // as non-JDK; it belongs to the named module "java.sql" instead, which the
        // fixed check recognises regardless of which of the two loaders defined it.
        Assertions.assertNotNull(SQLPermission.class.getClassLoader(),
                "SQLPermission is expected to be platform-loaded, not bootstrap-loaded");
        Assertions.assertTrue(SQLPermission.class.getModule().isNamed());
        Assertions.assertTrue(SQLPermission.class.getModule().getName().startsWith("java."));

        Assertions.assertTrue(TestUsageChecker.isPlatformClass(SQLPermission.class));
    }

    @Test
    void testUserClassInJdkPrefixedPackageIsNotPlatformClass() {
        // Unlike "java.", the JVM does not reserve "jdk." packages for the platform,
        // so a user class can genuinely sit in one (see the fixture's own package).
        // It must not be classified as a platform class merely by that name: it is
        // loaded via the ordinary application class loader into an *unnamed* module,
        // which the module-based check correctly rejects.
        Assertions.assertFalse(UserClassInJdkPrefixedPackage.class.getModule().isNamed());

        Assertions.assertFalse(TestUsageChecker.isPlatformClass(UserClassInJdkPrefixedPackage.class));
    }

    @Test
    void testAppLoadedGenuineJdkModuleIsPlatformClass() throws Exception {
        // com.sun.jdi.JDIPermission belongs to the real JDK module jdk.jdi, but unlike
        // java.sql.SQLPermission above, the JDK defines this module's classes with the
        // ordinary system/application class loader, not the platform loader -- the same
        // loader an application's own classpath classes use. A check that trusted loader
        // identity instead of boot-layer membership would misclassify it as non-JDK,
        // exactly like the user-module spoof it was meant to catch.
        Class<?> jdiPermission = Class.forName("com.sun.jdi.JDIPermission");
        Assertions.assertTrue(jdiPermission.getModule().isNamed());
        Assertions.assertTrue(jdiPermission.getModule().getName().startsWith("jdk."));
        Assertions.assertNotNull(jdiPermission.getClassLoader(), "jdk.jdi is not boot-loaded");
        Assertions.assertNotEquals(ClassLoader.getPlatformClassLoader(), jdiPermission.getClassLoader(),
                "jdk.jdi classes are defined by the system/application loader, not the platform loader");

        Assertions.assertTrue(TestUsageChecker.isPlatformClass(jdiPermission),
                "a genuine JDK module must be classified as platform code even when its "
                        + "defining loader is the ordinary system/application class loader");
    }

    @Test
    void testDeprecatedPlatformLoaderClassExemptFromDeprecationFilterWhenAnnotated() {
        // Whether java.sql.SQLPermission actually carries @Deprecated depends on the
        // running JDK: Corretto 17.0.5 platform-loads it without the annotation, while
        // OpenJDK 27 annotates it deprecated (both platform-load it either way, which
        // the two tests above already pin unconditionally). This test exercises the
        // USE_DEPRECATED interaction itself, so it self-documents as skipped rather
        // than silently passing when the running JDK's copy is not annotated.
        Assumptions.assumeTrue(SQLPermission.class.isAnnotationPresent(Deprecated.class),
                "running JDK's java.sql.SQLPermission is not annotated @Deprecated; "
                        + "classification is covered unconditionally by the tests above");

        loadTarget(LiveAbstractBase.class);
        Properties.USE_DEPRECATED = false;

        // Before the fix, isPlatformClass(SQLPermission.class) was false, so this
        // platform-loaded-but-annotated type was treated as ordinary deprecated API
        // and excluded here, even though LiveAbstractBase (the loaded target) does
        // not itself exempt it from the filter.
        Assertions.assertTrue(TestUsageChecker.canUse(SQLPermission.class));
    }

    @Test
    void testUserModuleNamedLikeAJdkModuleIsNotPlatformClass() throws Exception {
        // A module's name is a string whoever builds it chooses; nothing stops a user
        // module from calling itself "java.whatever" and defining a class inside it. This
        // compiles and loads exactly such a module through a real ModuleLayer -- the same
        // way a dependency assembled with a custom module descriptor could -- to confirm
        // isPlatformClass() is not fooled by the name prefix alone.
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null,
                "no system Java compiler available to build the spoofed-module fixture "
                        + "(running on a JRE rather than a JDK)");

        String moduleName = "java.evosuitespooftest";
        String packageName = "org.evosuite.setup.spooftest";
        String className = packageName + ".SpoofedUserType";

        Path moduleDir = Files.createTempDirectory("evosuite-module-spoof");
        try {
            Path moduleInfo = moduleDir.resolve("module-info.java");
            Files.writeString(moduleInfo, "module " + moduleName + " { exports " + packageName + "; }");
            Path packageDir = moduleDir.resolve(packageName.replace('.', '/'));
            Files.createDirectories(packageDir);
            Path userType = packageDir.resolve("SpoofedUserType.java");
            Files.writeString(userType, "package " + packageName + "; public class SpoofedUserType {}");

            int compileResult = compiler.run(null, null, null,
                    "-d", moduleDir.toString(), moduleInfo.toString(), userType.toString());
            Assertions.assertEquals(0, compileResult, "spoofed-module fixture must compile");

            ModuleFinder finder = ModuleFinder.of(moduleDir);
            Configuration configuration = ModuleLayer.boot().configuration()
                    .resolve(finder, ModuleFinder.of(), Set.of(moduleName));
            ModuleLayer layer = ModuleLayer.defineModulesWithOneLoader(
                    configuration, List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader()).layer();
            Class<?> spoofed = layer.findLoader(moduleName).loadClass(className);

            // Confirm the fixture actually set up the situation the finding describes,
            // before relying on it to exercise isPlatformClass(). The defining loader is
            // deliberately not asserted here beyond non-boot/non-platform: the real
            // distinguishing signal isPlatformClass() relies on is the module's own traced
            // reference origin (see JavaModuleLookup#isFromSystemImage) -- see
            // testAppLoadedGenuineJdkModuleIsPlatformClass for why loader identity alone
            // would also reject a genuine JDK tool module, and
            // testStartupResolvedUserModuleNamedLikeAJdkModuleIsNotPlatformClass for why
            // boot-layer membership alone is not sufficient either.
            Assertions.assertTrue(spoofed.getModule().isNamed());
            Assertions.assertEquals(moduleName, spoofed.getModule().getName());
            Assertions.assertTrue(moduleName.startsWith("java."));
            Assertions.assertNotNull(spoofed.getClassLoader(), "a spoofed module is not boot-loaded");
            Assertions.assertNotEquals(ClassLoader.getPlatformClassLoader(), spoofed.getClassLoader(),
                    "a spoofed module is not platform-loaded either");
            Assertions.assertNotEquals(ModuleLayer.boot(), spoofed.getModule().getLayer(),
                    "a dynamically defined layer must not equal the boot layer (this alone would "
                            + "already have failed the now-retired boot-layer check; the startup-resolved "
                            + "case below is the one that check missed)");

            Assertions.assertFalse(TestUsageChecker.isPlatformClass(spoofed),
                    "a java.*-named module must not be classified as JDK platform code merely "
                            + "because of its name");
        } finally {
            deleteRecursively(moduleDir);
        }
    }

    /**
     * The fourth and last direction the module-provenance history requires: a module named like
     * a JDK module but <em>resolved at JVM startup</em> via {@code --module-path}/
     * {@code --add-modules}, rather than assembled into a new {@link ModuleLayer} at run time as
     * {@link #testUserModuleNamedLikeAJdkModuleIsNotPlatformClass} does. The two are not
     * interchangeable: a startup-resolved module lands in {@code ModuleLayer.boot()}, so it would
     * have passed the previous (boot-layer-membership) version of this check even though it is
     * ordinary application code with no connection to the running JDK -- see the corrected class
     * and method Javadoc on {@link TestUsageChecker#isPlatformClass(Class)} and
     * {@link JavaModuleLookup#isFromSystemImage(JavaModuleLookup.Result)} for the measured proof. An in-process test
     * cannot create this situation: {@code ModuleLayer.boot()} is fixed before any test method
     * runs, so this launches a real child JVM instead, with the spoofed module pre-declared on
     * its command line exactly the way a dependency assembled that way would be, and reads back
     * what {@link TestUsageChecker#isPlatformClass(Class)} reports for a class it exports.
     */
    @Test
    void testStartupResolvedUserModuleNamedLikeAJdkModuleIsNotPlatformClass() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null,
                "no system Java compiler available to build the startup-module fixture "
                        + "(running on a JRE rather than a JDK)");

        String moduleName = "java.evosuitespooftest.startup";
        String packageName = "org.evosuite.setup.spooftest.startup";
        String userTypeClassName = packageName + ".StartupSpoofedUserType";
        String runnerClassName = "org.evosuite.setup.StartupModulePlatformClassRunner";

        Path moduleDir = Files.createTempDirectory("evosuite-module-spoof-startup");
        Path runnerDir = Files.createTempDirectory("evosuite-module-spoof-startup-runner");
        try {
            Path moduleInfo = moduleDir.resolve("module-info.java");
            Files.writeString(moduleInfo, "module " + moduleName + " { exports " + packageName + "; }");
            Path packageDir = moduleDir.resolve(packageName.replace('.', '/'));
            Files.createDirectories(packageDir);
            Path userType = packageDir.resolve("StartupSpoofedUserType.java");
            Files.writeString(userType, "package " + packageName + ";"
                    + " public class StartupSpoofedUserType {}");

            int moduleCompileResult = compiler.run(null, null, null,
                    "-d", moduleDir.toString(), moduleInfo.toString(), userType.toString());
            Assertions.assertEquals(0, moduleCompileResult, "startup-module fixture must compile");

            // The child JVM's runner must live in the same package as TestUsageChecker to call
            // its package-private isPlatformClass(Class); compile it against this test's own
            // classpath so it can resolve that class.
            Path runnerSource = runnerDir.resolve("StartupModulePlatformClassRunner.java");
            Files.writeString(runnerSource, "package org.evosuite.setup;"
                    + " public class StartupModulePlatformClassRunner {"
                    + "   public static void main(String[] args) throws Exception {"
                    + "     Class<?> target = Class.forName(args[0]);"
                    + "     System.out.println(TestUsageChecker.isPlatformClass(target));"
                    + "   }"
                    + " }");
            String testClasspath = System.getProperty("java.class.path");
            int runnerCompileResult = compiler.run(null, null, null,
                    "-cp", testClasspath, "-d", runnerDir.toString(), runnerSource.toString());
            Assertions.assertEquals(0, runnerCompileResult, "runner fixture must compile");

            String javaBinary = System.getProperty("java.home") + File.separator + "bin"
                    + File.separator + "java";
            List<String> command = Arrays.asList(javaBinary,
                    "--module-path", moduleDir.toString(),
                    "--add-modules", moduleName,
                    "-cp", testClasspath + File.pathSeparator + runnerDir,
                    runnerClassName, userTypeClassName);

            // Redirect the child's output to a file instead of a pipe: reading a pipe to EOF
            // before waitFor() blocks forever if the child stays alive with the stream open, and
            // waiting on a bounded pipe first can just as easily block the child if its output
            // overflows the pipe buffer before anything drains it. A file avoids both failure
            // modes, so the bounded wait below is the only thing that can block.
            Path childOutputFile = runnerDir.resolve("child-output.txt");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(childOutputFile.toFile())
                    .start();
            try {
                boolean exited = process.waitFor(60, TimeUnit.SECONDS);
                if (!exited) {
                    Assertions.fail("child JVM did not terminate within the timeout");
                }
                String output = Files.readString(childOutputFile, StandardCharsets.UTF_8);
                Assertions.assertEquals(0, process.exitValue(),
                        "child JVM must run the classification cleanly; output was:"
                                + System.lineSeparator() + output);

                // A module placed on --module-path and named in --add-modules at JVM startup is
                // resolved into ModuleLayer.boot() -- unlike the runtime-created layer above,
                // there is no way to construct this situation in-process. Exactly this case
                // passed the previous (boot-layer-membership) version of isPlatformClass(): the
                // measured output there was "true", with the module's layer reported as
                // ModuleLayer.boot().
                Assertions.assertEquals("false", output.trim(),
                        "a startup-resolved java.*-named module must not be classified as JDK "
                                + "platform code; child JVM output was:" + System.lineSeparator()
                                + output);
            } finally {
                // No-op if the child already exited; bounds the child's lifetime to this test
                // even when an assertion above fails or the timeout above fired.
                process.destroyForcibly();
            }
        } finally {
            deleteRecursively(moduleDir);
            deleteRecursively(runnerDir);
        }
    }

    /**
     * The sixth direction, on top of the four above: a module that reuses the <em>name of a real
     * image module</em> -- one {@code java.lang.module.ModuleFinder#ofSystem()} actually enumerates
     * -- but is resolved from somewhere other than the image. This is not the same situation as
     * {@link #testUserModuleNamedLikeAJdkModuleIsNotPlatformClass}, whose module name
     * ({@code java.evosuitespooftest}) matches no real module anywhere: mere image-wide name
     * membership (an enumeration {@link JavaModuleLookup#isFromSystemImage(JavaModuleLookup.Result)}
     * deliberately does not rely on) would not distinguish this case, because it does not say that
     * <em>this particular class's own module</em> is the image's copy. A module name can exist in
     * the image while being
     * absent from the default boot layer -- {@code jdk.hotspot.agent} is such a name on both
     * Corretto 17 and OpenJDK 27 -- which lets a user module resolve its own, separately compiled
     * copy of that exact name into a layer of its own, the same way the fixture below does, without
     * colliding with the JVM's own copy. The module name to reuse is discovered from the running
     * JDK itself, rather than hard-coded, so the test self-documents as skipped rather than passing
     * vacuously if a future JDK's image has no such name.
     */
    @Test
    void testSameNameAsImageModuleButDifferentOriginIsNotPlatformClass() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null,
                "no system Java compiler available to build the duplicate-name module fixture "
                        + "(running on a JRE rather than a JDK)");

        String moduleName = findImageModuleNameAbsentFromBootLayer();
        Assumptions.assumeTrue(moduleName != null,
                "no module in the running JDK's own image (ModuleFinder.ofSystem()) is absent "
                        + "from the default boot layer; cannot reproduce a same-name, "
                        + "different-origin module on this JDK");

        String packageName = "org.evosuite.setup.spooftest.dupename";
        String className = packageName + ".DuplicateNameUserType";

        Path moduleDir = Files.createTempDirectory("evosuite-module-dupe-name");
        try {
            Path moduleInfo = moduleDir.resolve("module-info.java");
            Files.writeString(moduleInfo, "module " + moduleName + " { exports " + packageName + "; }");
            Path packageDir = moduleDir.resolve(packageName.replace('.', '/'));
            Files.createDirectories(packageDir);
            Path userType = packageDir.resolve("DuplicateNameUserType.java");
            Files.writeString(userType, "package " + packageName + "; public class DuplicateNameUserType {}");

            int compileResult = compiler.run(null, null, null,
                    "-d", moduleDir.toString(), moduleInfo.toString(), userType.toString());
            Assertions.assertEquals(0, compileResult, "duplicate-name module fixture must compile");

            ModuleFinder finder = ModuleFinder.of(moduleDir);
            Configuration configuration = ModuleLayer.boot().configuration()
                    .resolve(finder, ModuleFinder.of(), Set.of(moduleName));
            ModuleLayer layer = ModuleLayer.defineModulesWithOneLoader(
                    configuration, List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader()).layer();
            Class<?> duplicate = layer.findLoader(moduleName).loadClass(className);

            // Confirm the fixture reproduces the finding: a module that reuses the name of a real
            // image module, resolved here from a temp directory rather than the image, in a layer
            // this JVM can actually construct precisely because that name is absent from its own
            // boot layer.
            Assertions.assertTrue(duplicate.getModule().isNamed());
            Assertions.assertEquals(moduleName, duplicate.getModule().getName());
            Assertions.assertTrue(ModuleFinder.ofSystem().find(moduleName).isPresent(),
                    moduleName + " must be a name the running JDK's own image actually ships");
            Assertions.assertFalse(ModuleLayer.boot().findModule(moduleName).isPresent(),
                    moduleName + " must be absent from the default boot layer, or this fixture "
                            + "could not coexist with the JVM's own copy of that name");
            Assertions.assertNotEquals(ModuleFinder.ofSystem().find(moduleName).get(),
                    duplicate.getModule().getLayer().configuration().findModule(moduleName)
                            .get().reference(),
                    "the fixture's module reference must differ from the image's own, or this "
                            + "is not the same-name/different-origin situation the finding describes");

            Assertions.assertFalse(TestUsageChecker.isPlatformClass(duplicate),
                    "a module merely reusing the name of a real image module, resolved from "
                            + "somewhere other than the image itself, must not be classified as "
                            + "JDK platform code");
        } finally {
            deleteRecursively(moduleDir);
        }
    }

    /**
     * A name the running JDK's own image ships (per {@code ModuleFinder.ofSystem()}) but that is
     * not part of the default boot layer, so a user module can safely resolve its own copy of that
     * name into a layer of its own without colliding with the JVM's.
     */
    private static String findImageModuleNameAbsentFromBootLayer() {
        Set<String> bootNames = ModuleLayer.boot().modules().stream()
                .map(Module::getName)
                .collect(Collectors.toSet());
        return ModuleFinder.ofSystem().findAll().stream()
                .map(ref -> ref.descriptor().name())
                .filter(name -> !bootNames.contains(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Baseline/control: an ordinary non-modular classpath library class -- unaffected by either the
     * module-name-prefix check or the image-origin check above, since it has no named module at all
     * -- must still classify as non-JDK.
     */
    @Test
    void testOrdinaryClasspathLibraryClassIsNotPlatformClass() {
        Assertions.assertFalse(org.apache.commons.lang3.ClassUtils.class.getModule().isNamed(),
                "commons-lang3 is expected to be an ordinary, non-modular classpath dependency here");
        Assertions.assertFalse(TestUsageChecker.isPlatformClass(org.apache.commons.lang3.ClassUtils.class));
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
