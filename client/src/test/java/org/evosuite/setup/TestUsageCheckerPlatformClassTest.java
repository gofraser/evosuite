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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLPermission;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

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
 * also requires the defining loader to be the boot or platform loader.
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
            // before relying on it to exercise isPlatformClass().
            Assertions.assertTrue(spoofed.getModule().isNamed());
            Assertions.assertEquals(moduleName, spoofed.getModule().getName());
            Assertions.assertTrue(moduleName.startsWith("java."));
            Assertions.assertNotNull(spoofed.getClassLoader(), "a spoofed module is not boot-loaded");
            Assertions.assertNotEquals(ClassLoader.getPlatformClassLoader(), spoofed.getClassLoader(),
                    "a spoofed module is not platform-loaded either");

            Assertions.assertFalse(TestUsageChecker.isPlatformClass(spoofed),
                    "a java.*-named module must not be classified as JDK platform code "
                            + "unless its defining loader is actually the boot or platform loader");
        } finally {
            deleteRecursively(moduleDir);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
