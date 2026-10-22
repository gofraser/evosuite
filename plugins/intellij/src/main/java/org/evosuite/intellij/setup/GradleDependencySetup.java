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
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.roots.ExternalLibraryDescriptor;
import com.intellij.openapi.roots.TestModuleProperties;

import org.evosuite.intellij.run.IdeThread;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Build-file edits, not transient IDEA libraries. No dependency on Gradle plugin classes. */
final class GradleDependencySetup {
    private GradleDependencySetup() {}

    static boolean applies(Module module) {
        return ReadAction.compute(
                () ->
                        "GRADLE"
                                .equals(
                                        ExternalSystemModulePropertyManager.getInstance(module)
                                                .getExternalSystemId()));
    }

    static void add(
            Module module,
            ExternalLibraryDescriptor descriptor,
            ProgressIndicator indicator,
            Consumer<String> console) {
        String version = descriptor.getPreferredVersion();
        if (version == null || version.isBlank()) version = descriptor.getMinVersion();
        String configuration = "test";
        String id = ExternalSystemApiUtil.getExternalProjectId(module);
        if (TestModuleProperties.getInstance(module).getProductionModule() != null && id != null) {
            String sourceSet = id.substring(id.lastIndexOf(':') + 1);
            if (sourceSet.matches("[A-Za-z_$][A-Za-z0-9_$]*")) configuration = sourceSet;
        }
        List<UnifiedDependency> dependencies = new ArrayList<>();
        boolean jupiter = "org.junit.jupiter".equals(descriptor.getLibraryGroupId());
        dependencies.add(
                new UnifiedDependency(
                        descriptor.getLibraryGroupId(),
                        jupiter ? "junit-jupiter" : descriptor.getLibraryArtifactId(),
                        version,
                        configuration + "Implementation"));
        if (jupiter) {
            // Gradle 9 no longer supplies the launcher implicitly. Jupiter's aggregate artifact
            // supplies the API and engine; the matching Platform launcher is a runtime dependency.
            String platform = version.startsWith("5.") ? "1." + version.substring(2) : version;
            dependencies.add(
                    new UnifiedDependency(
                            "org.junit.platform",
                            "junit-platform-launcher",
                            platform,
                            configuration + "RuntimeOnly"));
        }
        try {
            boolean supported =
                    IdeThread.edt(
                            () -> {
                                var modifier =
                                        DependencyModifierService.getInstance(module.getProject());
                                if (!modifier.supports(module)) return false;
                                var files = FileDocumentManager.getInstance();
                                Map<Document, Long> unchanged = new IdentityHashMap<>();
                                for (Document document : files.getUnsavedDocuments())
                                    unchanged.put(document, document.getModificationStamp());
                                for (UnifiedDependency dependency : dependencies)
                                    modifier.addDependency(module, dependency);
                                // Save only documents changed by the dependency edit, preserving
                                // unrelated buffers.
                                for (Document document : files.getUnsavedDocuments())
                                    if (!Objects.equals(
                                            unchanged.get(document),
                                            document.getModificationStamp()))
                                        files.saveDocument(document);
                                return true;
                            });
            if (!supported) {
                manual(dependencies, console);
                return;
            }
            IdeThread.flushPendingVfsWrites();
            String path = ExternalSystemApiUtil.getExternalRootProjectPath(module);
            if (path == null) path = ExternalSystemApiUtil.getExternalProjectPath(module);
            String root = path;
            CompletableFuture<Void> sync = new CompletableFuture<>();
            IdeThread.edt(
                    () -> {
                        ExternalSystemUtil.refreshProject(
                                root,
                                new ImportSpecBuilder(
                                                module.getProject(), new ProjectSystemId("GRADLE"))
                                        .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                                        .withImportProjectData(true)
                                        .withCallback(sync));
                        return null;
                    });
            IdeThread.await(sync, indicator);
        } catch (ProcessCanceledException ex) {
            throw ex;
        } catch (RuntimeException | IOException ex) {
            console.accept("Could not update/re-sync the Gradle dependencies: " + ex.getMessage());
            manual(dependencies, console);
        }
    }

    private static void manual(List<UnifiedDependency> dependencies, Consumer<String> console) {
        console.accept(
                "Add these test dependencies to the module's Gradle build file, then re-sync:");
        for (UnifiedDependency dependency : dependencies) {
            var c = dependency.getCoordinates();
            console.accept(
                    dependency.getScope()
                            + "(\""
                            + c.getGroupId()
                            + ":"
                            + c.getArtifactId()
                            + ":"
                            + c.getVersion()
                            + "\")");
        }
    }
}
