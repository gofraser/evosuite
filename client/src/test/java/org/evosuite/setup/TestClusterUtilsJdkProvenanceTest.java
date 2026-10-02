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
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Pins a related provenance gap in {@code TestClusterUtils}'s private {@code isJdkClass(Class)},
 * which backs {@link TestClusterUtils#checkIfCanUse(String, Class)}'s ambiguous-prefix exception
 * for prefixes such as {@code com.sun.} that both the JDK and ordinary libraries use (see that
 * method's own Javadoc for why the exception exists at all).
 *
 * <p>{@code isJdkClass} classified a class as JDK-provenance whenever its module's name started
 * with {@code java.}/{@code jdk.}, with no check that the module actually came from the running
 * JDK. A module named {@code java.fake} that exports an unrelated, genuinely ambiguous package
 * (for example {@code com.sun.example}) made every class in that package look like JDK code,
 * purely because of the <em>enclosing module's</em> name -- not the class's own package -- which
 * wrongly rejected an otherwise-intended user-library exception for a reason unconnected to the
 * class's own package.
 *
 * <p>This is the same class of defect {@link TestUsageCheckerPlatformClassTest} pins for
 * {@link TestUsageChecker#isPlatformClass(Class)}, and the fix reuses the same signal
 * ({@link JavaModuleLookup#isSystemModule(String)}): a {@code java.}/{@code jdk.}-named module
 * must also be one the running JDK's own install image actually ships.
 */
class TestClusterUtilsJdkProvenanceTest {

    @Test
    void userModuleNamedLikeAJdkModuleDoesNotShadowAmbiguousPrefixException() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null,
                "no system Java compiler available to build the spoofed-module fixture "
                        + "(running on a JRE rather than a JDK)");

        String moduleName = "java.evosuitespooftest.clusterutils";
        String ambiguousPackage = "com.sun.evosuitespooftest";
        String userTypeClassName = ambiguousPackage + ".ClusterUtilsSpoofedUserType";

        Path moduleDir = Files.createTempDirectory("evosuite-module-spoof-clusterutils");
        try {
            Path moduleInfo = moduleDir.resolve("module-info.java");
            Files.writeString(moduleInfo, "module " + moduleName + " { exports " + ambiguousPackage + "; }");
            Path packageDir = moduleDir.resolve(ambiguousPackage.replace('.', '/'));
            Files.createDirectories(packageDir);
            Path userType = packageDir.resolve("ClusterUtilsSpoofedUserType.java");
            Files.writeString(userType, "package " + ambiguousPackage + ";"
                    + " public class ClusterUtilsSpoofedUserType {}");

            int compileResult = compiler.run(null, null, null,
                    "-d", moduleDir.toString(), moduleInfo.toString(), userType.toString());
            Assertions.assertEquals(0, compileResult, "spoofed-module fixture must compile");

            ModuleFinder finder = ModuleFinder.of(moduleDir);
            Configuration configuration = ModuleLayer.boot().configuration()
                    .resolve(finder, ModuleFinder.of(), Set.of(moduleName));
            ModuleLayer layer = ModuleLayer.defineModulesWithOneLoader(
                    configuration, List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader()).layer();
            Class<?> spoofed = layer.findLoader(moduleName).loadClass(userTypeClassName);

            // Confirm the fixture set up the situation the finding describes: a class in a
            // package ("com.sun.evosuitespooftest") that is itself genuinely ambiguous (unknown
            // to the JDK), sitting inside a module merely named like a JDK module.
            Assertions.assertTrue(spoofed.getModule().isNamed());
            Assertions.assertEquals(moduleName, spoofed.getModule().getName());
            Assertions.assertTrue(moduleName.startsWith("java."));

            // Without the module actually coming from the JDK, checkIfCanUse's ambiguous-prefix
            // exception must still apply: this class must be usable, exactly as a genuine
            // com.sun.* library class (e.g. rome's com.sun.syndication) would be.
            Assertions.assertTrue(TestClusterUtils.checkIfCanUse(userTypeClassName, spoofed),
                    "a user class in an ambiguous (non-JDK-reserved) package must remain usable "
                            + "even when it sits inside a module merely named like a JDK module");
        } finally {
            deleteRecursively(moduleDir);
        }
    }

    @Test
    void genuineJdkModuleStillShadowsAmbiguousPrefixException() throws Exception {
        // Baseline/control for the fix above: a class that actually comes from the JDK's own
        // jdk.jdi module, in the same ambiguous com.sun.* package family, must still be rejected
        // by the ambiguous-prefix exception -- the fix must not turn this into a blanket bypass.
        Class<?> jdiPermission = Class.forName("com.sun.jdi.JDIPermission");
        Assertions.assertTrue(jdiPermission.getModule().isNamed());
        Assertions.assertTrue(jdiPermission.getModule().getName().startsWith("jdk."));

        Assertions.assertFalse(TestClusterUtils.checkIfCanUse(jdiPermission.getName(), jdiPermission),
                "a genuine JDK module's class must still be excluded by the ambiguous-prefix check");
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
