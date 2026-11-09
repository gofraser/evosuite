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
package org.evosuite.intellij.run;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;

import org.evosuite.ide.GenerationOptions;
import org.evosuite.ide.GenerationTarget;
import org.evosuite.ide.JavaInstallation;
import org.evosuite.intellij.setup.ProjectSetupService;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ClasspathResolver {
    private ClasspathResolver() {}

    public static List<String> resolve(Module module, boolean extension) {
        return classpath(extension ? ProjectSetupService.testModule(module) : module, extension);
    }

    private static List<String> classpath(Module module, boolean extension) {
        OrderEnumerator entries =
                OrderEnumerator.orderEntries(module).recursively().runtimeOnly().withoutSdk();
        if (!extension) entries.productionOnly();
        return entries.classes().getPathsList().getPathList().stream()
                .filter(
                        path -> {
                            String name =
                                    Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
                            if (name.contains("evosuite-standalone-runtime")
                                    || name.contains("evosuite-runtime")) return false;
                            return extension
                                    || !(name.startsWith("junit-")
                                            || name.startsWith("junit4-")
                                            || name.startsWith("junit-jupiter-")
                                            || name.startsWith("junit-platform-")
                                            || name.startsWith("junit-vintage-"));
                        })
                .distinct()
                .toList();
    }

    /**
     * Test roots may belong to integrationTest/testFixtures rather than the ordinary test module.
     */
    public static List<Module> seedModules(Module production, List<GenerationTarget> targets) {
        Set<Module> modules = new LinkedHashSet<>();
        var index =
                com.intellij.openapi.roots.ProjectFileIndex.getInstance(production.getProject());
        for (var target : targets)
            for (var seed : target.seeds()) {
                var root =
                        com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                                .findFileByNioFile(seed.sourceRoot().toPath());
                Module module = root == null ? null : index.getModuleForFile(root);
                if (module != null) modules.add(module);
            }
        if (modules.isEmpty() && targets.stream().anyMatch(GenerationTarget::extendsTests))
            modules.add(ProjectSetupService.testModule(production));
        return List.copyOf(modules);
    }

    public static List<String> resolve(Module production, GenerationTarget target) {
        if (!target.extendsTests()) return resolve(production, false);
        Set<String> paths = new LinkedHashSet<>();
        for (Module module : seedModules(production, List.of(target)))
            paths.addAll(classpath(module, true));
        paths.addAll(resolve(production, false));
        return List.copyOf(paths);
    }

    public static JavaInstallation java(Module module, GenerationOptions options)
            throws IOException {
        String home = options.javaHome.isBlank() ? ReadAction.compute(() -> sdkHome(module)) : options.javaHome;
        if (home == null) throw new IOException("Configure a module JDK or set Java home in Tools > EvoSuite.");
        JavaInstallation java = JavaInstallation.at(Path.of(home));
        if (!java.hasCompiler())
            throw new IOException("EvoSuite needs a full JDK with javac: " + home);
        return java;
    }
    private static String sdkHome(Module module) {
        Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
        if (sdk == null) sdk = ProjectRootManager.getInstance(module.getProject()).getProjectSdk();
        return sdk == null ? null : sdk.getHomePath();
    }
}
