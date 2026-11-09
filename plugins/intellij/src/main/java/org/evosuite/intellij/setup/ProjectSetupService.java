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
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
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

    /** Canonical owner for settings/history/builds; test classpaths still use testModule. */
    public static Module productionModule(Module module) {
        return com.intellij.openapi.application.ReadAction.compute(
                () -> productionModuleInReadAction(module));
    }

    private static Module productionModuleInReadAction(Module module) {
        Module production = TestModuleProperties.getInstance(module).getProductionModule();
        return production == null ? module : production;
    }

    /** A source-set import keeps test roots and test dependencies in a separate module. */
    public static Module testModule(Module module) {
        return com.intellij.openapi.application.ReadAction.compute(
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
        // Gradle can also link testFixtures/integrationTest to main. Prefer its ordinary test
        // source set; do not choose an unrelated/custom test module arbitrarily.
        return tests.stream()
                .filter(candidate -> candidate.getName().endsWith(".test"))
                .findFirst()
                .orElse(module);
    }

    public static Path baseDir(Module module) {
        return com.intellij.openapi.application.ReadAction.compute(
                () -> baseDirInReadAction(module));
    }

    private static Path baseDirInReadAction(Module module) {
        MavenIntegration maven = MavenBridge.get(module.getProject());
        Path mavenDir = maven == null ? null : maven.directory(module);
        if (mavenDir != null) return mavenDir;
        String external =
                com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
                        .getExternalProjectPath(module);
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
        Project project = module.getProject();
        Path folder = testFolder(module, options);
        Module testModule = IdeThread.smartRead(project, () -> testModule(module));
        boolean junit =
                hasClass(
                        testModule,
                        options.testFormat.equals(GenerationOptions.JUNIT4)
                                ? "org.junit.Test"
                                : "org.junit.jupiter.api.Test");
        boolean runtime =
                options.effectiveNoRuntimeDependency()
                        || hasClass(testModule, "org.evosuite.runtime.EvoRunnerParameters");
        MavenIntegration maven = MavenBridge.get(project);
        if (maven != null) {
            String declared = maven.runtimeVersion(module);
            if (!options.effectiveNoRuntimeDependency()
                    && !declared.isBlank() && !EngineJars.VERSION.equals(declared))
                console.accept(
                        "The project uses EvoSuite runtime "
                                + declared
                                + ", while the bundled engine is "
                                + EngineJars.VERSION
                                + ". Align these versions if generated tests do not compile.");
        }
        boolean mavenModule = maven != null && maven.directory(module) != null;
        if (mavenModule) {
            List<MavenIntegration.TestDependency> dependencies = new ArrayList<>();
            if (!junit) {
                ExternalLibraryDescriptor descriptor =
                        options.testFormat.equals(GenerationOptions.JUNIT4)
                                ? JUnitExternalLibraryDescriptor.JUNIT4
                                : JUnitExternalLibraryDescriptor.JUNIT5;
                dependencies.add(
                        new MavenIntegration.TestDependency(
                                descriptor.getLibraryGroupId(),
                                options.testFormat.equals(GenerationOptions.JUNIT4)
                                        ? descriptor.getLibraryArtifactId()
                                        : "junit-jupiter",
                                descriptor.getPreferredVersion()));
            }
            if (!runtime)
                dependencies.add(
                        new MavenIntegration.TestDependency(
                                "org.evosuite", "evosuite-standalone-runtime", EngineJars.VERSION));
            IdeThread.await(
                    IdeThread.edt(() -> maven.offerSetup(module, options, dependencies)), indicator);
            registerTestRoot(module, folder);
            return;
        }
        if (GradleDependencySetup.applies(module)) {
            List<ExternalLibraryDescriptor> missing = new ArrayList<>();
            if (!junit)
                missing.add(
                        options.testFormat.equals(GenerationOptions.JUNIT4)
                                ? JUnitExternalLibraryDescriptor.JUNIT4
                                : JUnitExternalLibraryDescriptor.JUNIT5);
            if (!runtime)
                missing.add(
                        new ExternalLibraryDescriptor(
                                "org.evosuite",
                                "evosuite-standalone-runtime",
                                EngineJars.VERSION,
                                EngineJars.VERSION,
                                EngineJars.VERSION));
            if (GradleDependencySetup.prepare(
                    module, testModule, options, missing, indicator, console))
                registerTestRoot(module, folder);
            return;
        }
        boolean imported =
                com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
                                .getExternalProjectPath(module)
                        != null;
        if (!junit || !runtime && imported) {
            String dependencies =
                    (!junit
                                    ? (options.testFormat.equals(GenerationOptions.JUNIT4)
                                            ? "JUnit 4"
                                            : "JUnit Jupiter")
                                    : "")
                            + (!runtime && imported
                                    ? (!junit ? " and " : "")
                                            + "org.evosuite:evosuite-standalone-runtime:"
                                            + EngineJars.VERSION
                                    : "");
            int choice =
                    IdeThread.edt(
                            () ->
                                    Messages.showDialog(
                                            project,
                                            "Add "
                                                    + dependencies
                                                    + " as test dependencies for "
                                                    + module.getName()
                                                    + "?"
                                                    + (imported
                                                            ? " IntelliJ will update its"
                                                                    + " build file."
                                                            : " IntelliJ will add a module"
                                                                    + " library."),
                                            "EvoSuite Dependencies",
                                            new String[] {
                                                "Add Dependencies", "Skip Setup", "Cancel"
                                            },
                                            0,
                                            Messages.getQuestionIcon()));
            if (choice < 0 || choice == 2) throw new com.intellij.openapi.progress.ProcessCanceledException();
            if (choice == 0) {
                if (!junit)
                    add(
                            testModule,
                            options.testFormat.equals(GenerationOptions.JUNIT4)
                                    ? JUnitExternalLibraryDescriptor.JUNIT4
                                    : JUnitExternalLibraryDescriptor.JUNIT5,
                            indicator);
                if (!runtime && imported)
                    add(
                            testModule,
                            new ExternalLibraryDescriptor(
                                    "org.evosuite",
                                    "evosuite-standalone-runtime",
                                    EngineJars.VERSION,
                                    EngineJars.VERSION,
                                    EngineJars.VERSION),
                            indicator);
            } else {
                console.accept(
                        "Setup was skipped. Add JUnit and the EvoSuite runtime and mark the"
                            + " generated folder as test sources before running generated tests.");
                return;
            }
        }
        if (!runtime && !imported) {
            Path jar = EngineJars.runtime();
            IdeThread.edt(
                    () -> {
                        WriteCommandAction.runWriteCommandAction(
                                project,
                                "EvoSuite: add runtime library",
                                null,
                                () ->
                                        ModuleRootModificationUtil.updateModel(
                                                testModule,
                                                model -> {
                                                    Library library =
                                                            model.getModuleLibraryTable()
                                                                    .getLibraryByName(
                                                                            "EvoSuite Runtime");
                                                    if (library == null)
                                                        library =
                                                                model.getModuleLibraryTable()
                                                                        .createLibrary(
                                                                                "EvoSuite Runtime");
                                                    Library.ModifiableModel lib =
                                                            library.getModifiableModel();
                                                    for (String url :
                                                            lib.getUrls(OrderRootType.CLASSES))
                                                        lib.removeRoot(url, OrderRootType.CLASSES);
                                                    lib.addRoot(
                                                            VfsUtil.getUrlForLibraryRoot(
                                                                    jar.toFile()),
                                                            OrderRootType.CLASSES);
                                                    lib.commit();
                                                    for (OrderEntry entry : model.getOrderEntries())
                                                        if (entry instanceof LibraryOrderEntry order
                                                                && library.equals(
                                                                        order.getLibrary()))
                                                            order.setScope(DependencyScope.TEST);
                                                }));
                        return null;
                    });
        }
        // A dependency edit can reimport the module and reset its transient source roots.
        // Register against the final model, even when Maven setup was declined.
        registerTestRoot(module, folder);
    }

    public static void registerTestRoot(Module module, Path folder) {
        Project project = module.getProject();
        IdeThread.edt(
                () -> {
                    WriteCommandAction.runWriteCommandAction(
                            project,
                            "EvoSuite: add test source root",
                            null,
                            () -> {
                                try {
                                    Module ownerModule = testModule(module);
                                    VirtualFile root = VfsUtil.createDirectories(folder.toString());
                                    if (!ProjectFileIndex.getInstance(project)
                                            .isInTestSourceContent(root))
                                        ModuleRootModificationUtil.updateModel(
                                                ownerModule,
                                                model -> {
                                                    ContentEntry owner =
                                                            Arrays.stream(model.getContentEntries())
                                                                    .filter(
                                                                            e ->
                                                                                    e.getFile()
                                                                                                    != null
                                                                                            && VfsUtilCore
                                                                                                    .isAncestor(
                                                                                                            e
                                                                                                                    .getFile(),
                                                                                                            root,
                                                                                                            false))
                                                                    .max(
                                                                            Comparator.comparingInt(
                                                                                    e ->
                                                                                            e.getUrl()
                                                                                                    .length()))
                                                                    .orElse(null);
                                                    if (owner == null
                                                            && TestModuleProperties.getInstance(
                                                                                    ownerModule)
                                                                            .getProductionModule()
                                                                    != null)
                                                        owner = model.addContentEntry(root);
                                                    if (owner == null)
                                                        throw new IllegalStateException(
                                                                "Test folder has no module content"
                                                                        + " root: "
                                                                        + folder);
                                                    // IntelliJ must not retain a MAIN and TEST
                                                    // entry for the same URL (legacy plugin roots
                                                    // were registered as production sources).
                                                    String prefix = "";
                                                    for (SourceFolder source :
                                                            owner.getSourceFolders())
                                                        if (source.getUrl().equals(root.getUrl())) {
                                                            prefix = source.getPackagePrefix();
                                                            owner.removeSourceFolder(source);
                                                        }
                                                    SourceFolder added =
                                                            owner.addSourceFolder(
                                                                    root,
                                                                    JavaSourceRootType.TEST_SOURCE);
                                                    added.setPackagePrefix(prefix);
                                                });
                                } catch (IOException ex) {
                                    throw new IllegalStateException(ex);
                                }
                            });
                    return null;
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
