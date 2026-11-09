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
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ExternalLibraryDescriptor;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;

import org.evosuite.ide.*;
import org.evosuite.intellij.run.EngineJars;
import org.evosuite.intellij.run.IdeThread;
import org.jdom.Element;
import org.jetbrains.idea.maven.execution.*;
import org.jetbrains.idea.maven.model.*;
import org.jetbrains.idea.maven.project.*;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MavenSupport implements MavenIntegration {
    private final Project project;

    public MavenSupport(Project project) {
        this.project = project;
    }

    private MavenProject model(Module module) {
        return ReadAction.compute(
                () -> MavenProjectsManager.getInstance(project).findProject(module));
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

    private static Map<String, String> collect(Element element, MavenProject model) {
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

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    public static String interpolate(String text, MavenProject model) {
        if (!text.contains("${")) return text;
        Map<String, String> properties = new HashMap<>();
        model.getProperties().forEach((key, value) -> properties.put(key.toString(), value.toString()));
        model.getModelMap().forEach((key, value) -> {
            properties.putIfAbsent(key, value);
            if (!key.startsWith("project.") && !key.startsWith("pom.")) {
                properties.put("project." + key, value);
                properties.put("pom." + key, value);
            }
        });
        properties.put("basedir", model.getDirectory());
        for (int pass = 0; pass < 10 && text.contains("${"); pass++) {
            String resolved = PROPERTY.matcher(text).replaceAll(match -> {
                String replacement = property(match.group(1), model, properties);
                return Matcher.quoteReplacement(replacement == null ? match.group() : replacement);
            });
            if (text.equals(resolved)) break;
            text = resolved;
        }
        return text;
    }

    private static String property(String key, MavenProject model, Map<String, String> properties) {
        int prefix = key.startsWith("project.") ? 8 : key.startsWith("pom.") ? 4 : 0;
        if (prefix == 0) return properties.get(key);
        return switch (key.substring(prefix)) {
            case "basedir" -> model.getDirectory();
            case "build.directory" -> model.getBuildDirectory();
            case "build.outputDirectory" -> model.getOutputDirectory();
            case "build.testOutputDirectory" -> model.getTestOutputDirectory();
            case "build.finalName" -> model.getFinalName();
            case "build.sourceDirectory" -> model.getSources().stream().findFirst()
                    .orElse(model.getDirectory() + "/src/main/java");
            case "build.testSourceDirectory" -> model.getTestSources().stream().findFirst()
                    .orElse(model.getDirectory() + "/src/test/java");
            case "groupId" -> model.getMavenId().getGroupId();
            case "artifactId" -> model.getMavenId().getArtifactId();
            case "version" -> model.getMavenId().getVersion();
            default -> properties.get(key);
        };
    }

    static List<MavenPlugin.Execution> setupExecutions(MavenPlugin plugin) {
        return plugin == null ? List.of() : plugin.getExecutions().stream()
                .filter(execution -> execution.getGoals().contains("setup")).toList();
    }

    static Path setupFolder(MavenProject model, MavenPlugin plugin, MavenPlugin.Execution execution) {
        var values = collect(plugin.getConfigurationElement(), model);
        values.putAll(collect(execution.getConfigurationElement(), model));
        String folder = values.getOrDefault("testFolder",
                model.getProperties().getProperty("evosuite.testFolder", "src/test/evosuite"));
        return Path.of(model.getDirectory()).resolve(folder).normalize();
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
    public Promise<Void> offerSetup(Module module, GenerationOptions options,
                                    List<ExternalLibraryDescriptor> dependencies) {
        MavenProject model = model(module);
        if (model == null) return Promises.resolvedPromise();
        MavenPlugin plugin = model.findPlugin(PomSettings.GROUP_ID, PomSettings.ARTIFACT_ID);
        var executions = setupExecutions(plugin);
        Path output = Path.of(model.getDirectory()).resolve(options.testFolder).normalize();
        boolean configured = executions.stream()
                .anyMatch(execution -> setupFolder(model, plugin, execution).equals(output));
        if (configured && dependencies.isEmpty()) return Promises.resolvedPromise();

        String dependencyNote = dependencies.isEmpty() ? "" :
                "Add these test dependencies:\n" + dependencies.stream()
                        .map(d -> "  " + d.getLibraryGroupId() + ":" + artifact(d) + ":" + d.getPreferredVersion())
                        .collect(Collectors.joining("\n")) + "\n\n";
        String setupNote = configured ? "" :
                (executions.isEmpty() ? "Add" : "Update") + " the EvoSuite setup execution to keep "
                        + options.testFolder + " as a test source folder on reimport and configure"
                        + " the runtime agent for Maven tests.\n\n"
                        + (options.effectiveNoRuntimeDependency()
                                        ? "Runtime-free tests do not require the EvoSuite"
                                                + " runtime.\n\n"
                                        : "The runtime dependency is needed only by"
                                                + " runtime-dependent generated tests.\n\n");
        boolean combined = !configured && !dependencies.isEmpty();
        int choice = Messages.showDialog(project,
                "Configure " + model.getPath() + "?\n\n" + dependencyNote + setupNote
                        + "The pom will be saved and Maven reimported.",
                "EvoSuite Maven Setup",
                combined ? new String[] {"Configure Maven", "Dependencies Only", "Cancel"}
                        : configured ? new String[] {"Add Dependencies", "Cancel"}
                        : new String[] {"Configure Maven", "Skip Maven Setup", "Cancel"},
                0, Messages.getQuestionIcon());
        if (choice < 0 || choice == (configured ? 1 : 2))
            throw new ProcessCanceledException();
        if (!combined && !configured && choice == 1)
            return Promises.resolvedPromise();
        boolean configureExecution = !configured && choice == 0;
        PsiFile file = PsiManager.getInstance(project).findFile(model.getFile());
        if (!(file instanceof XmlFile xml) || xml.getRootTag() == null)
            throw new IllegalStateException(
                    "Cannot update "
                            + model.getPath()
                            + ". Check that the pom is valid and writable.");
        var documents = FileDocumentManager.getInstance();
        var document = documents.getDocument(model.getFile());
        if (document == null || !document.isWritable() || !model.getFile().isWritable())
            throw new IllegalStateException("Cannot edit " + model.getPath());
        if (documents.isDocumentUnsaved(document))
            throw new IllegalStateException("Save your edits to " + model.getPath() + " before configuring Maven.");
        String executionId = executions.isEmpty() ? "evosuite-setup" : executions.getFirst().getExecutionId();
        if (executionId == null || executionId.isBlank()) executionId = "default";
        String id = executionId;
        WriteCommandAction.runWriteCommandAction(
                project,
                "EvoSuite: configure Maven setup",
                null,
                () -> {
                    for (var dependency : dependencies)
                        configureTestDependency(xml, dependency.getLibraryGroupId(),
                                artifact(dependency), dependency.getPreferredVersion());
                    if (configureExecution) configureSetup(xml, options, id);
                },
                file);
        documents.saveDocument(document);
        if (documents.isDocumentUnsaved(document))
            throw new IllegalStateException("Could not save " + model.getPath());
        return refresh(model);
    }

    private static String artifact(ExternalLibraryDescriptor dependency) {
        return "org.junit.jupiter".equals(dependency.getLibraryGroupId())
                ? "junit-jupiter" : dependency.getLibraryArtifactId();
    }

    // Local snapshots might not be indexed yet.
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
                    append(dependencies, "dependency", "");
            child(dependency, "groupId").getValue().setText(group);
            child(dependency, "artifactId").getValue().setText(artifact);
            child(dependency, "scope").getValue().setText("test");
        }
        child(dependency, "version").getValue().setText(version);
    }

    private Promise<Void> refresh(MavenProject model) {
        var result = new AsyncPromise<Void>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                IdeThread.flushPendingVfsWrites();
                if (project.isDisposed()) throw new ProcessCanceledException();
                IdeThread.edt(() -> MavenProjectsManager.getInstance(project).forceUpdateProjects(List.of(model)))
                        .onSuccess(result::setResult).onError(result::setError);
            } catch (Exception ex) {
                result.setError(ex);
            }
        });
        return result;
    }

    static void configureSetup(XmlFile xml, GenerationOptions options, String executionId) {
        XmlTag build = child(xml.getRootTag(), "build"), plugins = child(build, "plugins");
        XmlTag target = null;
        for (XmlTag candidate : plugins.findSubTags("plugin"))
            if (PomSettings.ARTIFACT_ID.equals(candidate.getSubTagText("artifactId"))
                    && PomSettings.GROUP_ID.equals(candidate.getSubTagText("groupId")))
                target = candidate;
        if (target == null) {
            target = append(plugins, "plugin", "");
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
        if (execution == null) execution = append(executions, "execution", "");
        child(execution, "id").getValue().setText(executionId);
        XmlTag goals = child(execution, "goals");
        if (Arrays.stream(goals.findSubTags("goal"))
                .noneMatch(g -> "setup".equals(g.getValue().getTrimmedText())))
            append(goals, "goal", "setup");
        child(child(execution, "configuration"), "testFolder").getValue().setText(options.testFolder);
    }

    private static XmlTag child(XmlTag parent, String name) {
        XmlTag child = parent.findFirstSubTag(name);
        return child == null ? append(parent, name, "") : child;
    }

    private static XmlTag append(XmlTag parent, String name, String value) {
        return parent.addSubTag(parent.createChildTag(name, parent.getNamespace(), value, false), false);
    }

    @Override
    public boolean canDelegate(Module module, GenerationOptions options) throws java.io.IOException {
        MavenProject model = model(module);
        if (model == null || !"_ESTest".equals(options.testSuffix)) return false;
        PomSettings pom = read(model);
        if (pom != null) {
            var effective = new GenerationOptions();
            pom.applyTo(effective, Path.of(model.getDirectory()).toFile());
            if (effective.pomProperties.containsKey("junit_suffix")
                    || EvoSuiteCommand.splitArguments(effective.pomExtraArguments).stream()
                            .anyMatch(arg -> arg.startsWith("-Djunit_suffix="))) return false;
        }

        return supportsStaging(model, MavenRunner.getInstance(project).getSettings());
    }

    private static boolean supportsStaging(MavenProject model, MavenRunnerSettings settings)
            throws java.io.IOException {
        // The goal's legacy CLI aliases override explicit POM values. Canonical CLI properties
        // suppress that override, so use the bundled engine if the invocation supplies them.
        if (settings.getMavenProperties().keySet().stream()
                .anyMatch(key -> overridesStaging("-D" + key)))
            return false;
        for (String variable : List.of("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"))
            if (overridesStaging(settings.getEnvironmentProperties().getOrDefault(variable,
                    System.getenv().getOrDefault(variable, "")))) return false;
        for (Path dir = Path.of(model.getDirectory()); dir != null; dir = dir.getParent()) {
            for (String config : List.of("maven.config", "jvm.config")) {
                Path file = dir.resolve(".mvn").resolve(config);
                if (Files.isRegularFile(file) && overridesStaging(Files.readString(file)))
                    return false;
            }
        }
        return true;
    }

    private static boolean overridesStaging(String value) {
        return value.contains("evosuite.testFolder") || value.contains("evosuite.cuts")
                || value.contains("-DtargetFolder") || value.contains("-Dcuts");
    }

    @Override
    public boolean delegate(
            Module module,
            List<GenerationTarget> targets,
            Path staging,
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
        goals.add("-Dcuts=" + targets.stream().map(GenerationTarget::className)
                .collect(Collectors.joining(",")));
        goals.add("-DtargetFolder=" + staging.toAbsolutePath());
        MavenRunner runner = MavenRunner.getInstance(project);
        MavenRunnerSettings settings = runner.getSettings().clone();
        try {
            if (!supportsStaging(model, settings))
                throw new IllegalStateException("Maven options changed during setup. Start generation again.");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read the Maven invocation settings", ex);
        }
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
