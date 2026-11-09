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
package org.evosuite.intellij.setup;

import com.intellij.execution.junit.JUnitExternalLibraryDescriptor;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import org.evosuite.ide.GenerationOptions;
import org.evosuite.intellij.maven.*;
import org.evosuite.intellij.run.*;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class ProjectSetupService {
    private ProjectSetupService() {}

    public static Module productionModule(Module module) {
        return ReadAction.compute(() -> {
            Module production = TestModuleProperties.getInstance(module).getProductionModule();
            return production == null ? module : production;
        });
    }

    public static Module testModule(Module module) {
        return ReadAction.compute(
                () -> testModuleInReadAction(module));
    }

    private static Module testModuleInReadAction(Module module) {
        if (TestModuleProperties.getInstance(module).getProductionModule() != null) return module;
        List<Module> tests =
                Arrays.stream(ModuleManager.getInstance(module.getProject()).getModules())
                        .filter(
                                candidate ->
                                        module.equals(
                                                TestModuleProperties.getInstance(candidate)
                                                        .getProductionModule()))
                        .toList();
        if (tests.size() == 1) return tests.getFirst();
        // Prefer Gradle's ordinary test source set over fixtures and integration tests.
        return tests.stream()
                .filter(candidate -> candidate.getName().endsWith(".test"))
                .findFirst()
                .orElse(module);
    }

    public static Path baseDir(Module module) {
        return ReadAction.compute(
                () -> baseDirInReadAction(module));
    }

    private static Path baseDirInReadAction(Module module) {
        MavenIntegration maven = MavenBridge.get(module.getProject());
        Path mavenDir = maven == null ? null : maven.directory(module);
        if (mavenDir != null) return mavenDir;
        String external = ExternalSystemApiUtil.getExternalProjectPath(module);
        if (external != null && !external.isBlank()) return Path.of(external);
        VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
        if (roots.length > 0) return roots[0].toNioPath();
        String base = module.getProject().getBasePath();
        if (base == null)
            throw new IllegalArgumentException(
                    "Module has no content directory: " + module.getName());
        return Path.of(base);
    }

    public static Path testFolder(Module module, GenerationOptions options) {
        Path base = baseDir(module).toAbsolutePath().normalize();
        Path folder = base.resolve(options.testFolder).normalize();
        verifyOutputPath(base, folder);
        if (Files.exists(folder) && !Files.isDirectory(folder))
            throw new IllegalArgumentException("Test folder is not a directory: " + folder);
        return folder;
    }

    public static void verifyOutputPath(Path base, Path output) {
        Path module = base.toAbsolutePath().normalize();
        Path path = output.toAbsolutePath().normalize();
        if (!path.startsWith(module) || path.equals(module))
            throw new IllegalArgumentException("Test output must be inside the module: " + path);
        // Check package directories and broken symlinks as well as the configured test folder.
        try {
            Path existing = path;
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            if (!existing.toRealPath().startsWith(module.toRealPath()))
                throw new IllegalArgumentException("Test output resolves outside the module: " + path);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve test output " + path, ex);
        }
    }

    public static void prepare(
            Module module,
            GenerationOptions options,
            ProgressIndicator indicator,
            Consumer<String> console)
            throws IOException {
        if (!options.configureBuildPath) return;
        Path folder = testFolder(module, options);
        Module tests = testModule(module);
        boolean junit4 = GenerationOptions.JUNIT4.equals(options.testFormat);
        var junit = junit4 ? JUnitExternalLibraryDescriptor.JUNIT4 : JUnitExternalLibraryDescriptor.JUNIT5;
        boolean needsJunit = !hasClass(tests, junit4 ? "org.junit.Test" : "org.junit.jupiter.api.Test");
        boolean runtimeFree = options.effectiveNoRuntimeDependency();
        boolean needsRuntime = !runtimeFree && !hasClass(tests, "org.evosuite.runtime.EvoRunnerParameters");
        boolean external = ExternalSystemApiUtil.getExternalProjectPath(module) != null;
        MavenIntegration maven = MavenBridge.get(module.getProject());
        boolean mavenModule = maven != null && maven.directory(module) != null;
        boolean gradleModule = !mavenModule && GradleDependencySetup.applies(module);

        List<ExternalLibraryDescriptor> dependencies = new ArrayList<>();
        if (needsJunit) dependencies.add(junit);
        if (needsRuntime && (mavenModule || gradleModule || external)) {
            String version = EngineJars.VERSION;
            dependencies.add(new ExternalLibraryDescriptor(
                    "org.evosuite", "evosuite-standalone-runtime", version, version, version));
        }
        if (mavenModule) {
            String declared = maven.runtimeVersion(module);
            if (!runtimeFree && !declared.isBlank() && !EngineJars.VERSION.equals(declared))
                console.accept("The project uses EvoSuite runtime " + declared + ", while the bundled engine is "
                        + EngineJars.VERSION + ". Align these versions if generated tests do not compile.");
            IdeThread.await(IdeThread.edt(() -> maven.offerSetup(module, options, dependencies)), indicator);
        } else if (gradleModule) {
            if (!GradleDependencySetup.prepare(module, tests, options, dependencies, indicator, console)) return;
        } else {
            if (!dependencies.isEmpty()) {
                List<String> labels = new ArrayList<>();
                if (needsJunit) labels.add(junit4 ? "JUnit 4" : "JUnit Jupiter");
                if (needsRuntime && external)
                    labels.add("org.evosuite:evosuite-standalone-runtime:" + EngineJars.VERSION);
                String message = "Add " + String.join(" and ", labels) + " as test dependencies for "
                        + module.getName() + "?" + (external ? " IntelliJ will update its build file."
                                : " IntelliJ will add a module library.");
                int choice = IdeThread.edt(() -> Messages.showDialog(module.getProject(), message,
                        "EvoSuite Dependencies", new String[] {"Add Dependencies", "Skip Setup", "Cancel"},
                        0, Messages.getQuestionIcon()));
                if (choice < 0 || choice == 2) throw new ProcessCanceledException();
                if (choice == 1) {
                    console.accept("Setup was skipped. Add the required dependencies and register the test source folder.");
                    return;
                }
                for (var dependency : dependencies) add(tests, dependency, indicator);
            }
            if (needsRuntime && !external) addBundledRuntime(tests);
        }
        // Reimport can reset transient roots, so register against the final model.
        registerTestRoot(module, folder);
    }

    private static void addBundledRuntime(Module module) throws IOException {
        String url = VfsUtil.getUrlForLibraryRoot(EngineJars.runtime().toFile());
        IdeThread.writeCommand(module.getProject(), "EvoSuite: add runtime library",
                () -> ModuleRootModificationUtil.updateModel(module, model -> {
                    var libraries = model.getModuleLibraryTable();
                    Library runtime = libraries.getLibraryByName("EvoSuite Runtime");
                    if (runtime == null) runtime = libraries.createLibrary("EvoSuite Runtime");

                    var library = runtime.getModifiableModel();
                    for (String root : library.getUrls(OrderRootType.CLASSES))
                        library.removeRoot(root, OrderRootType.CLASSES);
                    library.addRoot(url, OrderRootType.CLASSES);
                    library.commit();
                    for (OrderEntry entry : model.getOrderEntries())
                        if (entry instanceof LibraryOrderEntry order && runtime.equals(order.getLibrary()))
                            order.setScope(DependencyScope.TEST);
                }));
    }

    public static void registerTestRoot(Module module, Path folder) {
        Project project = module.getProject();
        IdeThread.writeCommand(project, "EvoSuite: add test source root", () -> {
            try {
                VirtualFile root = VfsUtil.createDirectories(folder.toString());
                if (ProjectFileIndex.getInstance(project).isInTestSourceContent(root)) return;
                Module tests = testModule(module);
                ModuleRootModificationUtil.updateModel(tests, model -> {
                    ContentEntry owner = Arrays.stream(model.getContentEntries())
                            .filter(entry -> entry.getFile() != null && VfsUtilCore.isAncestor(entry.getFile(), root, false))
                            .max(Comparator.comparingInt(entry -> entry.getUrl().length()))
                            .orElse(null);
                    if (owner == null && TestModuleProperties.getInstance(tests).getProductionModule() != null)
                        owner = model.addContentEntry(root);
                    if (owner == null)
                        throw new IllegalStateException("Test folder has no module content root: " + folder);

                    // Earlier plugin versions registered this root as production sources.
                    String prefix = "";
                    for (SourceFolder source : owner.getSourceFolders()) {
                        if (source.getUrl().equals(root.getUrl())) {
                            prefix = source.getPackagePrefix();
                            owner.removeSourceFolder(source);
                        }
                    }
                    owner.addSourceFolder(root, JavaSourceRootType.TEST_SOURCE).setPackagePrefix(prefix);
                });
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private static void add(
            Module module,
            ExternalLibraryDescriptor dependency,
            ProgressIndicator indicator) {
        var promise =
                IdeThread.edt(
                        () ->
                                JavaProjectModelModificationService.getInstance(module.getProject())
                                        .addDependency(module, dependency, DependencyScope.TEST));
        IdeThread.await(promise, indicator);
    }

    public static boolean hasClass(Module module, String name) {
        return IdeThread.smartRead(
                module.getProject(),
                () ->
                        JavaPsiFacade.getInstance(module.getProject())
                                        .findClass(
                                                name,
                                                GlobalSearchScope
                                                        .moduleWithDependenciesAndLibrariesScope(
                                                                module, true))
                                != null);
    }
}
