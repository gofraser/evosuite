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
package org.evosuite.intellij.maven;

import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;

import org.evosuite.ide.*;
import org.evosuite.intellij.run.EngineJars;
import org.jdom.Element;
import org.jetbrains.idea.maven.execution.*;
import org.jetbrains.idea.maven.model.*;
import org.jetbrains.idea.maven.project.*;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class MavenSupport implements MavenIntegration {
    private final Project project;

    public MavenSupport(Project project) {
        this.project = project;
    }

    private MavenProject model(Module module) {
        return MavenProjectsManager.getInstance(project).findProject(module);
    }

    @Override
    public Path directory(Module module) {
        MavenProject model = model(module);
        return model == null ? null : Path.of(model.getDirectory());
    }

    @Override
    public String runtimeVersion(Module module) {
        MavenProject model = model(module);
        if (model == null) return "";
        return model.findDependencies("org.evosuite", "evosuite-standalone-runtime").stream()
                .map(artifact -> artifact.getVersion())
                .findFirst()
                .orElse("");
    }

    @Override
    public PomSettings settings(Module module) {
        MavenProject model = model(module);
        return model == null ? null : read(model);
    }

    public static PomSettings read(MavenProject model) {
        MavenPlugin plugin = model.findPlugin(PomSettings.GROUP_ID, PomSettings.ARTIFACT_ID);
        if (plugin == null) return null;
        Map<String, String> config = collect(plugin.getConfigurationElement(), model),
                generate = new LinkedHashMap<>(),
                setup = new LinkedHashMap<>();
        for (MavenPlugin.Execution execution : plugin.getExecutions()) {
            if ("default-cli".equals(execution.getExecutionId()))
                generate.putAll(collect(execution.getConfigurationElement(), model));
            if (execution.getGoals().contains("setup"))
                setup.putAll(collect(execution.getConfigurationElement(), model));
        }
        Map<String, String> properties = new LinkedHashMap<>();
        model.getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
        boolean junit4 = model.hasDependency("junit", "junit"),
                jupiter = model.hasDependency("org.junit.jupiter", "junit-jupiter-api");
        return new PomSettings(config, generate, setup, properties, junit4 && !jupiter);
    }

    static Map<String, String> collect(Element element, MavenProject model) {
        Map<String, String> values = new LinkedHashMap<>();
        if (element == null) return values;
        for (Element child : element.getChildren()) {
            if (child.getChildren().isEmpty())
                values.put(child.getName(), interpolate(child.getTextTrim(), model));
            else if ("evosuiteProperties".equals(child.getName()))
                for (Element entry : child.getChildren())
                    values.put(
                            PomSettings.PROPERTY_PREFIX + entry.getName(),
                            interpolate(entry.getTextTrim(), model));
        }
        return values;
    }

    static String interpolate(String text, MavenProject model) {
        String value =
                text.replace("${project.basedir}", model.getDirectory())
                        .replace("${basedir}", model.getDirectory());
        for (int pass = 0; pass < 10 && value.contains("${"); pass++) {
            String before = value;
            for (String key : model.getProperties().stringPropertyNames())
                value = value.replace("${" + key + "}", model.getProperties().getProperty(key));
            if (value.equals(before)) break;
        }
        return value;
    }

    @Override
    public String serverPassword(String id, Consumer<String> console) {
        String configured =
                MavenProjectsManager.getInstance(project)
                        .getGeneralSettings()
                        .getUserSettingsFile();
        Path settings =
                configured == null || configured.isBlank()
                        ? Path.of(System.getProperty("user.home"), ".m2", "settings.xml")
                        : Path.of(configured);
        return MavenServerPassword.read(settings, id, console);
    }

    @Override
    public org.jetbrains.concurrency.Promise<Void> offerSetup(
            Module module, GenerationOptions options, List<TestDependency> dependencies) {
        MavenProject model = model(module);
        if (model == null) return org.jetbrains.concurrency.Promises.resolvedPromise();
        MavenPlugin plugin = model.findPlugin(PomSettings.GROUP_ID, PomSettings.ARTIFACT_ID);
        MavenPlugin.Execution setup = null;
        boolean configured = false;
        if (plugin != null) {
            for (var execution : plugin.getExecutions()) {
                if (!execution.getGoals().contains("setup")) continue;
                var values = collect(plugin.getConfigurationElement(), model);
                values.putAll(collect(execution.getConfigurationElement(), model));
                String folder =
                        values.getOrDefault(
                                "testFolder",
                                model.getProperties()
                                        .getProperty("evosuite.testFolder", "src/test/evosuite"));
                Path base = Path.of(model.getDirectory());
                if (base.resolve(folder)
                        .normalize()
                        .equals(base.resolve(options.testFolder).normalize()))
                    configured = true;
                if (setup == null) setup = execution;
            }
        }
        if (configured && dependencies.isEmpty())
            return org.jetbrains.concurrency.Promises.resolvedPromise();
        String dependencyNote = dependencies.isEmpty() ? "" :
                "Add these test dependencies:\n" + dependencies.stream()
                        .map(d -> "  " + d.group() + ":" + d.artifact() + ":" + d.version())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n\n";
        String setupNote = configured ? "" :
                (setup == null ? "Add" : "Update") + " the EvoSuite setup execution to keep "
                        + options.testFolder + " as a test source folder on reimport and configure"
                        + " the runtime agent for Maven tests.\n\n"
                        + "The setup goal requires the runtime dependency; it does not add it.\n\n";
        boolean combined = !configured && !dependencies.isEmpty();
        int choice = Messages.showDialog(project,
                "Configure " + model.getPath() + "?\n\n" + dependencyNote + setupNote
                        + "The pom will be saved and Maven reimported.",
                "EvoSuite Maven Setup",
                combined ? new String[] {"Configure Maven", "Dependencies Only", "Cancel"}
                        : new String[] {"Configure Maven", "Cancel"},
                0, Messages.getQuestionIcon());
        if (choice != 0 && !(combined && choice == 1))
            return org.jetbrains.concurrency.Promises.resolvedPromise();
        boolean configureExecution = !configured && choice == 0;
        PsiFile file = PsiManager.getInstance(project).findFile(model.getFile());
        if (!(file instanceof XmlFile xml) || xml.getRootTag() == null)
            return org.jetbrains.concurrency.Promises.resolvedPromise();
        String executionId = setup == null ? "evosuite-setup" : setup.getExecutionId();
        if (executionId == null || executionId.isBlank()) executionId = "default";
        String id = executionId;
        WriteCommandAction.runWriteCommandAction(
                project,
                "EvoSuite: configure Maven setup",
                null,
                () -> {
                    for (TestDependency dependency : dependencies)
                        configureTestDependency(xml, dependency.group(), dependency.artifact(), dependency.version());
                    if (configureExecution) configureSetup(xml, options, id);
                },
                file);
        return saveAndRefresh(model);
    }

    @Override
    public org.jetbrains.concurrency.Promise<Void> addTestDependency(
            Module module, String group, String artifact, String version) {
        MavenProject model = model(module);
        if (model == null)
            return org.jetbrains.concurrency.Promises.rejectedPromise("Not a Maven module");
        PsiFile file = PsiManager.getInstance(project).findFile(model.getFile());
        if (!(file instanceof XmlFile xml) || xml.getRootTag() == null)
            return org.jetbrains.concurrency.Promises.rejectedPromise("Cannot edit the module pom");
        WriteCommandAction.runWriteCommandAction(
                project,
                "EvoSuite: add test dependency",
                null,
                () -> configureTestDependency(xml, group, artifact, version),
                file);
        return saveAndRefresh(model);
    }

    /** Exact versions also work before Maven's repository index has discovered a local snapshot. */
    static void configureTestDependency(
            XmlFile xml, String group, String artifact, String version) {
        XmlTag dependencies = child(xml.getRootTag(), "dependencies");
        XmlTag dependency =
                Arrays.stream(dependencies.findSubTags("dependency"))
                        .filter(
                                d ->
                                        group.equals(d.getSubTagText("groupId"))
                                                && artifact.equals(d.getSubTagText("artifactId"))
                                                && (d.getSubTagText("type") == null
                                                        || "jar".equals(d.getSubTagText("type")))
                                                && (d.getSubTagText("classifier") == null
                                                        || d.getSubTagText("classifier").isBlank()))
                        .findFirst()
                        .orElse(null);
        if (dependency == null) {
            dependency =
                    dependencies.addSubTag(
                            dependencies.createChildTag(
                                    "dependency", dependencies.getNamespace(), "", false),
                            false);
            child(dependency, "groupId").getValue().setText(group);
            child(dependency, "artifactId").getValue().setText(artifact);
            child(dependency, "scope").getValue().setText("test");
        }
        child(dependency, "version").getValue().setText(version);
    }

    private org.jetbrains.concurrency.Promise<Void> saveAndRefresh(MavenProject model) {
        var documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
        var document = documents.getDocument(model.getFile());
        if (document != null) documents.saveDocument(document);
        var result = new org.jetbrains.concurrency.AsyncPromise<Void>();
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                org.evosuite.intellij.run.IdeThread.flushPendingVfsWrites();
                                if (project.isDisposed())
                                    throw new com.intellij.openapi.progress
                                            .ProcessCanceledException();
                                org.evosuite.intellij.run.IdeThread.edt(
                                                () ->
                                                        MavenProjectsManager.getInstance(project)
                                                                .forceUpdateProjects(
                                                                        List.of(model)))
                                        .onSuccess(result::setResult)
                                        .onError(result::setError);
                            } catch (Exception ex) {
                                result.setError(ex);
                            }
                        });
        return result;
    }

    /** Updates the matching execution, including inherited IDs, without discarding other goals. */
    static void configureSetup(XmlFile xml, GenerationOptions options, String executionId) {
        XmlTag build = child(xml.getRootTag(), "build"), plugins = child(build, "plugins");
        XmlTag target = null;
        for (XmlTag candidate : plugins.findSubTags("plugin"))
            if (PomSettings.ARTIFACT_ID.equals(candidate.getSubTagText("artifactId"))
                    && PomSettings.GROUP_ID.equals(candidate.getSubTagText("groupId")))
                target = candidate;
        if (target == null) {
            target =
                    plugins.addSubTag(
                            plugins.createChildTag("plugin", plugins.getNamespace(), "", false),
                            false);
            child(target, "groupId").getValue().setText(PomSettings.GROUP_ID);
            child(target, "artifactId").getValue().setText(PomSettings.ARTIFACT_ID);
            child(target, "version").getValue().setText(EngineJars.VERSION);
        }
        XmlTag executions = child(target, "executions");
        XmlTag execution =
                Arrays.stream(executions.findSubTags("execution"))
                        .filter(e -> executionId.equals(e.getSubTagText("id")))
                        .findFirst()
                        .orElse(null);
        if (execution == null)
            execution =
                    executions.addSubTag(
                            executions.createChildTag(
                                    "execution", executions.getNamespace(), "", false),
                            false);
        child(execution, "id").getValue().setText(executionId);
        XmlTag goals = child(execution, "goals");
        if (Arrays.stream(goals.findSubTags("goal"))
                .noneMatch(g -> "setup".equals(g.getValue().getTrimmedText())))
            goals.addSubTag(
                    goals.createChildTag("goal", goals.getNamespace(), "setup", false), false);
        child(child(execution, "configuration"), "testFolder")
                .getValue()
                .setText(options.testFolder);
    }

    private static XmlTag child(XmlTag parent, String name) {
        XmlTag child = parent.findFirstSubTag(name);
        return child == null
                ? parent.addSubTag(
                        parent.createChildTag(name, parent.getNamespace(), "", false), false)
                : child;
    }

    @Override
    public boolean delegate(
            Module module,
            List<GenerationTarget> targets,
            GenerationOptions options,
            Map<String, String> env,
            ProgressIndicator indicator) {
        MavenProject model = model(module);
        if (model == null) return false;
        List<String> goals =
                new ArrayList<>(
                        List.of(
                                "compile",
                                "org.evosuite.plugins:evosuite-maven-plugin:"
                                        + EngineJars.VERSION
                                        + ":generate"));
        goals.add(
                "-Devosuite.cuts="
                        + String.join(
                                ",", targets.stream().map(GenerationTarget::className).toList()));
        MavenRunner runner = MavenRunner.getInstance(project);
        MavenRunnerSettings settings = runner.getSettings().clone();
        Map<String, String> environment = new HashMap<>(settings.getEnvironmentProperties());
        environment.putAll(env);
        settings.setEnvironmentProperties(environment);
        MavenRunnerParameters parameters =
                new MavenRunnerParameters(
                        true,
                        model.getDirectory(),
                        model.getFile().getName(),
                        goals,
                        MavenProjectsManager.getInstance(project).getExplicitProfiles());
        return runner.runBatch(
                List.of(parameters),
                MavenProjectsManager.getInstance(project).getGeneralSettings(),
                settings,
                "EvoSuite",
                indicator);
    }
}
