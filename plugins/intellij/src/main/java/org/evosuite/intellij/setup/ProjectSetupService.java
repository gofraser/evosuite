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

    public static Path baseDir(Module module) {
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
        if (!folder.startsWith(base) || folder.equals(base))
            throw new IllegalArgumentException("Test folder must be inside the module: " + folder);
        // Existing symlinks must not redirect output outside the module.
        try {
            Path ancestor = folder;
            while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
            if (!ancestor.toRealPath().startsWith(base.toRealPath()))
                throw new IllegalArgumentException(
                        "Test folder resolves outside the module: " + folder);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve test folder " + folder, ex);
        }
        return folder;
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
        registerTestRoot(module, folder);
        boolean junit =
                hasClass(
                        module,
                        options.testFormat.equals(GenerationOptions.JUNIT4)
                                ? "org.junit.Test"
                                : "org.junit.jupiter.api.Test");
        boolean runtime = hasClass(module, "org.evosuite.runtime.EvoRunnerParameters");
        MavenIntegration maven = MavenBridge.get(project);
        if (maven != null) {
            String declared = maven.runtimeVersion(module);
            if (!declared.isBlank() && !EngineJars.VERSION.equals(declared))
                console.accept(
                        "The project uses EvoSuite runtime "
                                + declared
                                + ", while the bundled engine is "
                                + EngineJars.VERSION
                                + ". Align these versions if generated tests do not compile.");
        }
        boolean imported =
                maven != null && maven.directory(module) != null
                        || com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
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
            boolean consent =
                    IdeThread.edt(
                            () ->
                                    Messages.showYesNoDialog(
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
                                                    Messages.getQuestionIcon())
                                            == Messages.YES);
            if (consent) {
                if (!junit)
                    add(
                            module,
                            options.testFormat.equals(GenerationOptions.JUNIT4)
                                    ? JUnitExternalLibraryDescriptor.JUNIT4
                                    : JUnitExternalLibraryDescriptor.JUNIT5,
                            indicator);
                if (!runtime && imported)
                    add(
                            module,
                            new ExternalLibraryDescriptor(
                                    "org.evosuite",
                                    "evosuite-standalone-runtime",
                                    EngineJars.VERSION,
                                    EngineJars.VERSION,
                                    EngineJars.VERSION),
                            indicator);
            } else
                console.accept(
                        "Dependencies were not added. Generated tests need JUnit and the EvoSuite"
                                + " runtime.");
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
                                                module,
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
        if (maven != null)
            IdeThread.await(IdeThread.edt(() -> maven.offerSetup(module, options)), indicator);
        if (imported && (maven == null || maven.directory(module) == null))
            console.accept(
                    "For Gradle re-sync, declare the generated root in build.gradle:"
                            + " sourceSets.test.java.srcDir(\""
                            + options.testFolder
                            + "\") (Kotlin DSL: sourceSets.test { java.srcDir(\""
                            + options.testFolder
                            + "\") }).");
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
                                    VirtualFile root = VfsUtil.createDirectories(folder.toString());
                                    if (!ProjectFileIndex.getInstance(project)
                                            .isInTestSourceContent(root))
                                        ModuleRootModificationUtil.updateModel(
                                                module,
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
            Module module, ExternalLibraryDescriptor dependency, ProgressIndicator indicator) {
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
