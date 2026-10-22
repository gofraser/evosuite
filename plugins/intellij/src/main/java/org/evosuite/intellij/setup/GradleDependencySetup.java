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

import com.intellij.buildsystem.model.unified.UnifiedDependency;
import com.intellij.externalSystem.DependencyModifierService;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.util.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.*;

import org.evosuite.ide.GenerationOptions;
import org.evosuite.intellij.run.IdeThread;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * One approved, undoable build-file change and one sync for dependencies and test configuration.
 */
final class GradleDependencySetup {
    private static final String START = "// EvoSuite managed test configuration: start";
    private static final String END = "// EvoSuite managed test configuration: end";

    static boolean applies(Module module) {
        return "GRADLE"
                .equals(
                        ExternalSystemModulePropertyManager.getInstance(module)
                                .getExternalSystemId());
    }

    static String sourceSet(Module module) {
        String id = ExternalSystemApiUtil.getExternalProjectId(module);
        if (TestModuleProperties.getInstance(module).getProductionModule() != null && id != null) {
            String name = id.substring(id.lastIndexOf(':') + 1);
            if (name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) return name;
        }
        return "test";
    }

    static String configuration(String folder, String sourceSet, boolean kotlin, boolean jupiter) {
        String path =
                folder.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("$", "\\$")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r");
        String text =
                START
                        + "\nsourceSets.named(\""
                        + sourceSet
                        + "\") { java.srcDir(\""
                        + path
                        + "\") }\n";
        if (jupiter)
            text +=
                    kotlin
                            ? "tasks.withType<org.gradle.api.tasks.testing.Test>().matching {"
                                    + " it.name == \""
                                    + sourceSet
                                    + "\" }.configureEach { useJUnitPlatform() }\n"
                            : "tasks.withType(org.gradle.api.tasks.testing.Test).matching { it.name"
                                    + " == \""
                                    + sourceSet
                                    + "\" }.configureEach { useJUnitPlatform() }\n";
        return text + END;
    }

    static String update(String original, String block) {
        int start = original.indexOf(START), end = original.indexOf(END);
        if (start >= 0 && end >= start)
            return original.substring(0, start) + block + original.substring(end + END.length());
        return original.stripTrailing() + "\n\n" + block + "\n";
    }

    static boolean prepare(
            Module production,
            Module testModule,
            GenerationOptions options,
            List<ExternalLibraryDescriptor> missing,
            ProgressIndicator indicator,
            Consumer<String> console) {
        Path base = ProjectSetupService.baseDir(production);
        Path build =
                Files.isRegularFile(base.resolve("build.gradle.kts"))
                        ? base.resolve("build.gradle.kts")
                        : base.resolve("build.gradle");
        // A subproject may inherit all configuration from its root and have no own script.
        boolean kotlin = build.toString().endsWith(".kts");
        String block =
                configuration(
                        options.testFolder,
                        sourceSet(testModule),
                        kotlin,
                        "JUNIT5".equals(options.testFormat));
        String original;
        try {
            original = Files.exists(build) ? Files.readString(build) : "";
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read Gradle build file " + build, ex);
        }
        if (missing.isEmpty() && original.contains(block)) return true;
        List<UnifiedDependency> dependencies = new ArrayList<>();
        for (var descriptor : missing) {
            String version = descriptor.getPreferredVersion();
            if (version == null || version.isBlank()) version = descriptor.getMinVersion();
            boolean jupiter = "org.junit.jupiter".equals(descriptor.getLibraryGroupId());
            String scope = sourceSet(testModule);
            dependencies.add(
                    new UnifiedDependency(
                            descriptor.getLibraryGroupId(),
                            jupiter ? "junit-jupiter" : descriptor.getLibraryArtifactId(),
                            version,
                            scope + "Implementation"));
            if (jupiter)
                dependencies.add(
                        new UnifiedDependency(
                                "org.junit.platform",
                                "junit-platform-launcher",
                                version.startsWith("5.") ? "1." + version.substring(2) : version,
                                scope + "RuntimeOnly"));
        }
        int choice =
                IdeThread.edt(
                        () ->
                                Messages.showDialog(
                                        production.getProject(),
                                        org.evosuite.intellij.run.GenerationDialogs.message(
                                                "Configure "
                                                        + build
                                                        + "?\n\nRegister "
                                                        + options.testFolder
                                                        + " as test sources."
                                                        + ("JUNIT5".equals(options.testFormat)
                                                                ? "\n"
                                                                      + "Enable JUnit Platform for"
                                                                      + " the test task."
                                                                : "")
                                                        + (dependencies.isEmpty()
                                                                ? ""
                                                                : "\n"
                                                                        + "Add missing test"
                                                                        + " dependencies:\n"
                                                                        + dependencies.stream()
                                                                                .map(
                                                                                        d ->
                                                                                                "  "
                                                                                                        + d
                                                                                                                .getCoordinates())
                                                                                .collect(
                                                                                        java.util
                                                                                                .stream
                                                                                                .Collectors
                                                                                                .joining(
                                                                                                        "\n")))
                                                        + "\n\n"
                                                        + "The build file will be saved and Gradle"
                                                        + " synced once."),
                                        "EvoSuite Gradle Setup",
                                        new String[] {"Configure Gradle", "Skip Setup", "Cancel"},
                                        0,
                                        Messages.getQuestionIcon()));
        if (choice < 0 || choice == 2) throw new ProcessCanceledException();
        if (choice == 1) {
            console.accept(
                    "Gradle setup skipped. Register the generated root and required test"
                            + " dependencies before running tests.");
            return false;
        }
        IdeThread.edt(
                () -> {
                    var manager = FileDocumentManager.getInstance();
                    Map<Document, Long> before = new IdentityHashMap<>();
                    for (var doc : manager.getUnsavedDocuments())
                        before.put(doc, doc.getModificationStamp());
                    var modifier = DependencyModifierService.getInstance(production.getProject());
                    if (!dependencies.isEmpty() && !modifier.supports(testModule))
                        throw new IllegalStateException(
                                "IntelliJ cannot edit this Gradle module's dependencies. Configure "
                                        + build
                                        + " manually.");
                    WriteCommandAction.runWriteCommandAction(
                            production.getProject(),
                            "EvoSuite: configure Gradle tests",
                            null,
                            () -> {
                                try {
                                    VirtualFile directory =
                                            VfsUtil.createDirectories(base.toString());
                                    VirtualFile file =
                                            directory.findChild(build.getFileName().toString());
                                    if (file == null)
                                        file =
                                                directory.createChildData(
                                                        GradleDependencySetup.class,
                                                        build.getFileName().toString());
                                    Document doc = manager.getDocument(file);
                                    if (doc == null)
                                        throw new IllegalStateException("Cannot edit " + build);
                                    doc.setText(update(doc.getText(), block));
                                    var psi =
                                            com.intellij.psi.PsiDocumentManager.getInstance(
                                                    production.getProject());
                                    psi.commitDocument(doc);
                                    for (var dependency : dependencies) {
                                        modifier.addDependency(testModule, dependency);
                                        psi.commitAllDocuments();
                                    }
                                } catch (java.io.IOException ex) {
                                    throw new IllegalStateException("Cannot update " + build, ex);
                                }
                            });
                    for (var doc : manager.getUnsavedDocuments())
                        if (!Objects.equals(before.get(doc), doc.getModificationStamp()))
                            manager.saveDocument(doc);
                    return null;
                });
        try {
            IdeThread.flushPendingVfsWrites();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        String root = ExternalSystemApiUtil.getExternalRootProjectPath(production);
        if (root == null) root = ExternalSystemApiUtil.getExternalProjectPath(production);
        String path = root;
        CompletableFuture<Void> sync = new CompletableFuture<>();
        IdeThread.edt(
                () -> {
                    ExternalSystemUtil.refreshProject(
                            path,
                            new ImportSpecBuilder(
                                            production.getProject(), new ProjectSystemId("GRADLE"))
                                    .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                                    .withImportProjectData(true)
                                    .withCallback(sync));
                    return null;
                });
        IdeThread.await(sync, indicator);
        console.accept("Configured Gradle test sources and dependencies: " + build);
        return true;
    }

    // Legacy callers are routed through the combined setup by ProjectSetupService.
    static void add(
            Module module,
            ExternalLibraryDescriptor dependency,
            ProgressIndicator indicator,
            Consumer<String> console) {
        prepare(module, module, new GenerationOptions(), List.of(dependency), indicator, console);
    }
}
