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
    public void offerSetup(Module module, GenerationOptions options) {
        MavenProject model = model(module);
        if (model == null) return;
        MavenPlugin plugin = model.findPlugin(PomSettings.GROUP_ID, PomSettings.ARTIFACT_ID);
        if (plugin != null
                && plugin.getExecutions().stream().anyMatch(e -> e.getGoals().contains("setup")))
            return;
        if (Messages.showYesNoDialog(
                        project,
                        "Add the EvoSuite setup execution to "
                                + model.getPath()
                                + "? Maven will keep "
                                + options.testFolder
                                + " as a test source folder on reimport.",
                        "EvoSuite Maven Setup",
                        Messages.getQuestionIcon())
                != Messages.YES) return;
        PsiFile file = PsiManager.getInstance(project).findFile(model.getFile());
        if (!(file instanceof XmlFile xml) || xml.getRootTag() == null) return;
        WriteCommandAction.runWriteCommandAction(
                project,
                "EvoSuite: configure Maven setup",
                null,
                () -> {
                    XmlTag build = child(xml.getRootTag(), "build"),
                            plugins = child(build, "plugins");
                    XmlTag target = null;
                    for (XmlTag candidate : plugins.findSubTags("plugin"))
                        if (PomSettings.ARTIFACT_ID.equals(candidate.getSubTagText("artifactId"))
                                && PomSettings.GROUP_ID.equals(candidate.getSubTagText("groupId")))
                            target = candidate;
                    if (target == null) {
                        target =
                                plugins.addSubTag(
                                        plugins.createChildTag(
                                                "plugin", plugins.getNamespace(), "", false),
                                        false);
                        child(target, "groupId").getValue().setText(PomSettings.GROUP_ID);
                        child(target, "artifactId").getValue().setText(PomSettings.ARTIFACT_ID);
                        child(target, "version").getValue().setText(EngineJars.VERSION);
                    }
                    XmlTag executions = child(target, "executions");
                    XmlTag execution =
                            executions.addSubTag(
                                    executions.createChildTag(
                                            "execution", executions.getNamespace(), "", false),
                                    false);
                    child(execution, "id").getValue().setText("evosuite-setup");
                    child(child(execution, "goals"), "goal").getValue().setText("setup");
                    child(child(execution, "configuration"), "testFolder")
                            .getValue()
                            .setText(options.testFolder);
                },
                file);
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        MavenProjectsManager.getInstance(project).forceUpdateProjects(List.of(model));
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
                                        + ":generate",
                                "org.evosuite.plugins:evosuite-maven-plugin:"
                                        + EngineJars.VERSION
                                        + ":export"));
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
