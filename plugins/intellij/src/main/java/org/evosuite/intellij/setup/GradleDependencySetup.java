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
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.util.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.*;

import org.evosuite.ide.GenerationOptions;
import org.evosuite.intellij.run.GenerationDialogs;
import org.evosuite.intellij.run.IdeThread;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class GradleDependencySetup {
    private static final String START = "// EvoSuite managed test configuration: start";
    private static final String END = "// EvoSuite managed test configuration: end";

    private static final String DEPENDENCIES_START = "// EvoSuite managed test dependencies: start";
    private static final String DEPENDENCIES_END = "// EvoSuite managed test dependencies: end";

    private GradleDependencySetup() {}

    static boolean applies(Module module) {
        return "GRADLE"
                .equals(
                        ExternalSystemModulePropertyManager.getInstance(module)
                                .getExternalSystemId());
    }

    static String sourceSet(Module module) {
        return com.intellij.openapi.application.ReadAction.compute(
                () -> sourceSetInReadAction(module));
    }

    private static String sourceSetInReadAction(Module module) {
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
        String testTasks =
                kotlin
                        ? "tasks.withType<org.gradle.api.tasks.testing.Test>()"
                        : "tasks.withType(org.gradle.api.tasks.testing.Test)";
        text +=
                testTasks
                        + ".matching { it.name == \""
                        + sourceSet
                        + "\" }.configureEach { "
                        + (jupiter ? "useJUnitPlatform()" : "useJUnit()")
                        + " }\n";
        return text + END;
    }

    static String update(String original, String block) {
        return replaceBlock(original, START, END, block);
    }

    private static String replaceBlock(String original, String start, String end, String block) {
        int from = original.indexOf(start);
        int to = original.indexOf(end);
        if (from == -1 && to == -1)
            return original.isBlank() ? block + "\n" : original.stripTrailing() + "\n\n" + block + "\n";
        if (from == -1 || to <= from || original.indexOf(start, from + start.length()) != -1
                || original.indexOf(end, to + end.length()) != -1)
            throw new IllegalStateException("Conflicting EvoSuite markers in Gradle build file");
        return original.substring(0, from) + block + original.substring(to + end.length());
    }

    /** Keep a build without a subproject script in its surrounding Kotlin/Groovy DSL. */
    static Path buildFile(Path base) {
        if (Files.isRegularFile(base.resolve("build.gradle.kts")))
            return base.resolve("build.gradle.kts");
        if (Files.isRegularFile(base.resolve("build.gradle"))) return base.resolve("build.gradle");
        for (Path parent = base.getParent(); parent != null; parent = parent.getParent()) {
            if (Files.isRegularFile(parent.resolve("build.gradle.kts"))
                    || Files.isRegularFile(parent.resolve("settings.gradle.kts")))
                return base.resolve("build.gradle.kts");
            if (Files.isRegularFile(parent.resolve("build.gradle"))
                    || Files.isRegularFile(parent.resolve("settings.gradle"))) break;
        }
        return base.resolve("build.gradle");
    }

    static String updateDependencies(String original, List<UnifiedDependency> dependencies) {
        if (dependencies.isEmpty()) return original;
        int from = original.indexOf(DEPENDENCIES_START);
        int to = original.indexOf(DEPENDENCIES_END);
        String previous = from >= 0 && to > from ? original.substring(from, to) : "";
        int closingBrace = previous.lastIndexOf('}');
        if (!previous.isEmpty() && closingBrace < 0)
            throw new IllegalStateException("Invalid EvoSuite dependency block in Gradle build file");

        Set<String> additions = new LinkedHashSet<>();
        for (UnifiedDependency dependency : dependencies) {
            var coordinates = dependency.getCoordinates();
            String declaration = "    " + dependency.getScope() + "(\"" + coordinates.getGroupId()
                    + ":" + coordinates.getArtifactId() + ":" + coordinates.getVersion() + "\")";
            if (!previous.lines().anyMatch(line -> line.strip().equals(declaration.strip())))
                additions.add(declaration);
        }
        if (additions.isEmpty()) return original;
        String block = previous.isEmpty() ? DEPENDENCIES_START + "\ndependencies {\n"
                : previous.substring(0, closingBrace).stripTrailing() + "\n";
        block += String.join("\n", additions) + "\n}\n" + DEPENDENCIES_END;
        return replaceBlock(original, DEPENDENCIES_START, DEPENDENCIES_END, block);
    }

    static boolean prepare(
            Module production,
            Module testModule,
            GenerationOptions options,
            List<ExternalLibraryDescriptor> missing,
            ProgressIndicator indicator,
            Consumer<String> console) {
        Path base = ProjectSetupService.baseDir(production);
        Path build = buildFile(base);
        boolean kotlin = build.toString().endsWith(".kts");
        String sourceSet = sourceSet(testModule);
        boolean junit5 = GenerationOptions.JUNIT5.equals(options.testFormat);
        String block = configuration(options.testFolder, sourceSet, kotlin, junit5);
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
            String scope = sourceSet;
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
        String message = "Configure " + build + "?\n\nRegister " + options.testFolder + " as test sources."
                + (junit5 ? "\nEnable JUnit Platform for the test task." : "");
        if (!dependencies.isEmpty()) message += "\n\nAdd test dependencies:\n"
                + dependencies.stream().map(d -> "  " + d.getCoordinates())
                        .collect(java.util.stream.Collectors.joining("\n"));
        String prompt = message + "\n\nThe build file will be saved and Gradle synced.";
        int choice = IdeThread.edt(() -> Messages.showDialog(production.getProject(),
                GenerationDialogs.message(prompt), "EvoSuite Gradle Setup",
                new String[] {"Configure Gradle", "Skip Setup", "Cancel"}, 0, Messages.getQuestionIcon()));
        if (choice < 0 || choice == 2) throw new ProcessCanceledException();
        if (choice == 1) {
            console.accept(
                    "Gradle setup skipped. Register the generated root and required test"
                            + " dependencies before running tests.");
            return false;
        }
        IdeThread.edt(() -> {
            indicator.checkCanceled();
            FileDocumentManager manager = FileDocumentManager.getInstance();
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(build);
            Document existing = file == null ? null : manager.getDocument(file);
            if (file != null && (existing == null || !file.isWritable() || !existing.isWritable()))
                throw new IllegalStateException("Cannot edit " + build);
            if (existing != null && manager.isDocumentUnsaved(existing))
                throw new IllegalStateException("Save your edits to " + build + " before configuring Gradle.");
            String current = existing == null ? "" : existing.getText();
            if (!current.equals(com.intellij.openapi.util.text.StringUtil.convertLineSeparators(original)))
                throw new IllegalStateException(build + " changed during setup. Start generation again.");
            String updated = updateDependencies(update(current, block), dependencies);
            Document[] edited = {existing};
            WriteCommandAction.runWriteCommandAction(production.getProject(), "EvoSuite: configure Gradle tests",
                    null, () -> {
                        try {
                            if (edited[0] == null) {
                                VirtualFile directory = VfsUtil.createDirectories(base.toString());
                                VirtualFile created = directory.createChildData(GradleDependencySetup.class,
                                        build.getFileName().toString());
                                edited[0] = manager.getDocument(created);
                                if (edited[0] == null) throw new IllegalStateException("Cannot edit " + build);
                            }
                            edited[0].setText(updated);
                            com.intellij.psi.PsiDocumentManager.getInstance(production.getProject())
                                    .commitDocument(edited[0]);
                        } catch (java.io.IOException ex) {
                            throw new IllegalStateException("Cannot update " + build, ex);
                        }
                    });
            manager.saveDocument(edited[0]);
            if (manager.isDocumentUnsaved(edited[0]))
                throw new IllegalStateException("Could not save " + build);
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
}
