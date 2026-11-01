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
package org.evosuite.eclipse.launch;

import org.eclipse.core.filebuffers.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.EvoSuiteJars;
import org.evosuite.eclipse.maven.MavenSupport;
import org.w3c.dom.*;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Persistent Maven/Gradle test configuration, offered once and synchronized before building. */
public final class BuildToolSetup {
    private static final String START = "// EvoSuite managed test configuration: start";
    private static final String END = "// EvoSuite managed test configuration: end";

    private BuildToolSetup() {}

    public static String runtimeVersion() throws CoreException {
        try (var jar = new java.util.jar.JarFile(EvoSuiteJars.runtime())) {
            Properties properties = new Properties();
            var entry =
                    jar.getJarEntry(
                            "META-INF/maven/org.evosuite/evosuite-standalone-runtime/pom.properties");
            if (entry == null) throw new IOException("Runtime version metadata is missing");
            try (var in = jar.getInputStream(entry)) {
                properties.load(in);
            }
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new CoreException(Activator.error("Cannot identify bundled runtime version", e));
        }
    }

    public static boolean prepare(
            IJavaProject project, GenerationOptions options, IProgressMonitor monitor)
            throws CoreException {
        IProject resource = project.getProject();
        if (!ProjectSetup.isManagedByBuildTool(resource)) return false;
        boolean junit4 = options.effectiveTestFormat().equals(GenerationOptions.JUNIT4);
        boolean missingJunit =
                project.findType(junit4 ? "org.junit.Test" : "org.junit.jupiter.api.Test") == null;
        boolean missingRuntime =
                !options.effectiveNoRuntimeDependency()
                        && project.findType("org.evosuite.runtime.EvoRunnerParameters") == null;
        IFile pom = resource.getFile("pom.xml");
        boolean maven = resource.hasNature(ProjectSetup.MAVEN_NATURE) && pom.exists();
        if (!maven && !resource.hasNature(ProjectSetup.GRADLE_NATURE)) return false;
        IFile file = maven ? pom : gradleBuildFile(resource);
        boolean existed = file.exists();
        String original = existed ? read(file) : "";
        String version = runtimeVersion();
        String full =
                maven
                        ? updatePom(original, options, missingJunit, missingRuntime, true, version)
                        : updateGradle(
                                original,
                                options,
                                missingJunit,
                                missingRuntime,
                                file.getName().endsWith(".kts"),
                                version);
        if (full.equals(original)) return true;
        int[] choice = {-1};
        Display.getDefault()
                .syncExec(
                        () ->
                                choice[0] =
                                        new MessageDialog(
                                                        Display.getDefault().getActiveShell(),
                                                        "EvoSuite "
                                                                + (maven ? "Maven" : "Gradle")
                                                                + " setup",
                                                        null,
                                                        "Configure "
                                                                + file.getFullPath()
                                                                + "?\n\nRegister "
                                                                + options.testFolder
                                                                + " as test sources"
                                                                + (missingJunit
                                                                        ? ", add JUnit"
                                                                        : "")
                                                                + (missingRuntime
                                                                        ? ", add EvoSuite runtime "
                                                                                + version
                                                                        : "")
                                                                + ". The build file is saved with"
                                                                + " local history and the project"
                                                                + " is synchronized before"
                                                                + " generation.",
                                                        MessageDialog.QUESTION,
                                                        maven
                                                                ? new String[] {
                                                                    "Configure Maven",
                                                                    "Dependencies only",
                                                                    "Skip setup",
                                                                    "Cancel"
                                                                }
                                                                : new String[] {
                                                                    "Configure Gradle",
                                                                    "Skip setup",
                                                                    "Cancel"
                                                                },
                                                        0)
                                                .open());
        if (choice[0] < 0 || choice[0] == (maven ? 3 : 2)) throw new OperationCanceledException();
        if (choice[0] == (maven ? 2 : 1)) return false;
        String updated =
                maven && choice[0] == 1
                        ? updatePom(original, options, missingJunit, missingRuntime, false, version)
                        : full;
        ITextFileBuffer buffer =
                FileBuffers.getTextFileBufferManager()
                        .getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        if (buffer != null && buffer.isDirty())
            throw new CoreException(
                    Activator.error("Save " + file.getName() + " before configuring it."));
        if (file.exists() != existed || (existed && !read(file).equals(original)))
            throw new CoreException(
                    Activator.error(
                            file.getName()
                                    + " changed while setup was being confirmed. Retry"
                                    + " generation."));
        String charset = existed ? file.getCharset() : resource.getDefaultCharset();
        var contents = new ByteArrayInputStream(updated.getBytes(Charset.forName(charset)));
        if (existed) file.setContents(contents, IResource.KEEP_HISTORY, monitor);
        else file.create(contents, false, monitor);
        if (maven) MavenSupport.refresh(resource, monitor);
        else syncGradle(resource, monitor);
        return true;
    }

    /** Keep an existing script; otherwise inherit the nearest enclosing build's DSL. */
    static IFile gradleBuildFile(IProject project) {
        if (project.getFile("build.gradle.kts").exists())
            return project.getFile("build.gradle.kts");
        if (project.getFile("build.gradle").exists()) return project.getFile("build.gradle");
        for (var directory = project.getLocation().toFile().toPath();
                directory != null;
                directory = directory.getParent()) {
            if (java.nio.file.Files.isRegularFile(directory.resolve("build.gradle.kts"))
                    || java.nio.file.Files.isRegularFile(directory.resolve("settings.gradle.kts")))
                return project.getFile("build.gradle.kts");
            if (java.nio.file.Files.isRegularFile(directory.resolve("build.gradle"))
                    || java.nio.file.Files.isRegularFile(directory.resolve("settings.gradle")))
                break;
        }
        return project.getFile("build.gradle");
    }

    private static String read(IFile file) throws CoreException {
        try (InputStream in = file.getContents()) {
            return new String(in.readAllBytes(), file.getCharset());
        } catch (IOException e) {
            throw new CoreException(Activator.error("Cannot read " + file.getFullPath(), e));
        }
    }

    public static String updateGradle(
            String original,
            GenerationOptions options,
            boolean junit,
            boolean runtime,
            boolean kotlin,
            String version) {
        String path =
                options.testFolder.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
        String block = START + "\nsourceSets.named(\"test\") { java.srcDir(\"" + path + "\") }\n";
        String taskType =
                kotlin
                        ? "<org.gradle.api.tasks.testing.Test>()"
                        : "(org.gradle.api.tasks.testing.Test)";
        String runner =
                options.effectiveTestFormat().equals(GenerationOptions.JUNIT4)
                        ? "useJUnit()"
                        : "useJUnitPlatform()";
        block += "tasks.withType" + taskType + ".configureEach { " + runner + " }\n";
        block += END;
        int start = original.indexOf(START), end = original.indexOf(END);
        String result =
                start >= 0 && end >= start
                        ? original.substring(0, start)
                                + block
                                + original.substring(end + END.length())
                        : original.stripTrailing() + "\n\n" + block + "\n";
        String dependencies = "";
        if (junit) {
            dependencies +=
                    "    testImplementation(\""
                            + (options.effectiveTestFormat().equals(GenerationOptions.JUNIT4)
                                    ? "junit:junit:4.13.2"
                                    : "org.junit.jupiter:junit-jupiter:5.13.4")
                            + "\")\n";
            if (!options.effectiveTestFormat().equals(GenerationOptions.JUNIT4))
                dependencies +=
                        "    testRuntimeOnly(\"org.junit.platform:junit-platform-launcher:1.13.4\")\n";
        }
        if (runtime)
            dependencies +=
                    "    testImplementation(\"org.evosuite:evosuite-standalone-runtime:"
                            + version
                            + "\")\n";
        // Do not add declarations already present when a sync has not yet refreshed the classpath.
        List<String> additions =
                dependencies
                        .lines()
                        .filter(
                                line -> {
                                    String coordinate =
                                            line.substring(
                                                    line.indexOf('"') + 1, line.lastIndexOf('"'));
                                    return !result.contains(
                                            coordinate.substring(0, coordinate.lastIndexOf(':')));
                                })
                        .toList();
        return additions.isEmpty()
                ? result
                : result + "\ndependencies {\n" + String.join("\n", additions) + "\n}\n";
    }

    public static String updatePom(
            String source,
            GenerationOptions options,
            boolean junit,
            boolean runtime,
            boolean setup,
            String version)
            throws CoreException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc =
                    factory.newDocumentBuilder()
                            .parse(
                                    new ByteArrayInputStream(
                                            source.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
            Element project = doc.getDocumentElement();
            boolean changed = false;
            if (junit)
                changed |=
                        dependency(
                                project,
                                options.effectiveTestFormat().equals(GenerationOptions.JUNIT4)
                                        ? "junit"
                                        : "org.junit.jupiter",
                                options.effectiveTestFormat().equals(GenerationOptions.JUNIT4)
                                        ? "junit"
                                        : "junit-jupiter",
                                options.effectiveTestFormat().equals(GenerationOptions.JUNIT4)
                                        ? "4.13.2"
                                        : "5.13.4");
            if (runtime)
                changed |=
                        dependency(project, "org.evosuite", "evosuite-standalone-runtime", version);
            if (setup) {
                Element plugins = child(child(project, "build"), "plugins");
                Element plugin = null;
                for (Element candidate : children(plugins, "plugin"))
                    if (text(candidate, "groupId").equals("org.evosuite.plugins")
                            && text(candidate, "artifactId").equals("evosuite-maven-plugin"))
                        plugin = candidate;
                if (plugin == null) {
                    plugin = append(plugins, "plugin");
                    set(plugin, "groupId", "org.evosuite.plugins");
                    set(plugin, "artifactId", "evosuite-maven-plugin");
                    set(plugin, "version", version);
                    changed = true;
                }
                Element execution = null;
                Element executions = child(plugin, "executions");
                for (Element candidate : children(executions, "execution"))
                    if (children(child(candidate, "goals"), "goal").stream()
                            .anyMatch(goal -> goal.getTextContent().trim().equals("setup")))
                        execution = candidate;
                if (execution == null) {
                    execution = append(executions, "execution");
                    set(execution, "id", "evosuite-setup");
                    set(child(execution, "goals"), "goal", "setup");
                    changed = true;
                }
                Element config = child(execution, "configuration");
                if (!text(config, "testFolder").equals(options.testFolder)) {
                    set(config, "testFolder", options.testFolder);
                    changed = true;
                }
            }
            if (!changed) return source;
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(output));
            return output.toString();
        } catch (Exception e) {
            throw new CoreException(Activator.error("Cannot configure pom.xml", e));
        }
    }

    private static boolean dependency(
            Element project, String group, String artifact, String version) {
        Element deps = child(project, "dependencies");
        for (Element dep : children(deps, "dependency"))
            if (text(dep, "groupId").equals(group) && text(dep, "artifactId").equals(artifact))
                return false;
        Element dep = append(deps, "dependency");
        set(dep, "groupId", group);
        set(dep, "artifactId", artifact);
        set(dep, "version", version);
        set(dep, "scope", "test");
        return true;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && element.getTagName().equals(name))
                result.add(element);
        return result;
    }

    private static Element child(Element parent, String name) {
        return children(parent, name).stream().findFirst().orElseGet(() -> append(parent, name));
    }

    private static Element append(Element parent, String name) {
        Element child = parent.getOwnerDocument().createElement(name);
        parent.appendChild(child);
        return child;
    }

    private static String text(Element parent, String name) {
        return children(parent, name).stream()
                .findFirst()
                .map(Element::getTextContent)
                .orElse("")
                .trim();
    }

    private static void set(Element parent, String name, String value) {
        child(parent, name).setTextContent(value);
    }

    private static void syncGradle(IProject project, IProgressMonitor monitor)
            throws CoreException {
        var bundle = Platform.getBundle("org.eclipse.buildship.core");
        if (bundle == null) return;
        try {
            Object workspace =
                    bundle.loadClass("org.eclipse.buildship.core.GradleCore")
                            .getMethod("getWorkspace")
                            .invoke(null);
            var build =
                    (Optional<?>)
                            bundle.loadClass("org.eclipse.buildship.core.GradleWorkspace")
                                    .getMethod("getBuild", IProject.class)
                                    .invoke(workspace, project);
            if (build.isPresent()) {
                Object result =
                        bundle.loadClass("org.eclipse.buildship.core.GradleBuild")
                                .getMethod("synchronize", IProgressMonitor.class)
                                .invoke(build.get(), monitor);
                IStatus status =
                        (IStatus)
                                bundle.loadClass("org.eclipse.buildship.core.SynchronizationResult")
                                        .getMethod("getStatus")
                                        .invoke(result);
                if (!status.isOK()) throw new CoreException(status);
            }
        } catch (ReflectiveOperationException e) {
            throw new CoreException(Activator.error("Cannot synchronize Gradle after setup", e));
        }
    }
}
