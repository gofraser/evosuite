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

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.runtime.CoreException;
import org.evosuite.eclipse.Activator;
import org.evosuite.ide.PomSettings;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

final class MavenBuildEdits {
    private MavenBuildEdits() {}

    static String configure(String source, List<ManagedBuildSetup.Dependency> dependencies, String folder, String version)
            throws CoreException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(source)));
            Element project = doc.getDocumentElement();
            if (!"project".equals(project.getLocalName())) throw new IllegalArgumentException("Expected a Maven project.");
            boolean changed = false;
            if (!dependencies.isEmpty()) {
                Element list = ensure(project, "dependencies");
                for (var dependency : dependencies) {
                    Element existing = children(list, "dependency").stream().filter(node ->
                            dependency.group().equals(text(node, "groupId"))
                                    && dependency.artifact().equals(text(node, "artifactId"))
                                    && List.of("", "jar").contains(text(node, "type"))
                                    && text(node, "classifier").isEmpty()).findFirst().orElse(null);
                    if (existing == null) {
                        existing = append(list, "dependency");
                        set(existing, "groupId", dependency.group());
                        set(existing, "artifactId", dependency.artifact());
                        set(existing, "scope", "test");
                        changed = true;
                    }
                    changed |= set(existing, "version", dependency.version());
                }
            }
            if (folder != null) {
                Element plugins = ensure(ensure(project, "build"), "plugins");
                Element plugin = children(plugins, "plugin").stream().filter(node ->
                        PomSettings.GROUP_ID.equals(text(node, "groupId"))
                                && PomSettings.ARTIFACT_ID.equals(text(node, "artifactId"))).findFirst().orElse(null);
                if (plugin == null) {
                    plugin = append(plugins, "plugin");
                    set(plugin, "groupId", PomSettings.GROUP_ID);
                    set(plugin, "artifactId", PomSettings.ARTIFACT_ID);
                    set(plugin, "version", version);
                    changed = true;
                }
                Element executions = ensure(plugin, "executions");
                List<Element> existing = children(executions, "execution");
                Element execution = existing.stream().filter(node -> child(node, "goals") != null
                        && children(child(node, "goals"), "goal").stream()
                                .anyMatch(goal -> "setup".equals(goal.getTextContent().trim()))).findFirst().orElse(null);
                if (execution == null) {
                    List<String> ids = existing.stream().map(node -> text(node, "id")).toList();
                    String id = "evosuite-setup";
                    for (int n = 2; ids.contains(id); n++) id = "evosuite-setup-" + n;
                    execution = append(executions, "execution");
                    set(execution, "id", id);
                    append(ensure(execution, "goals"), "goal").setTextContent("setup");
                    changed = true;
                }
                changed |= set(ensure(execution, "configuration"), "testFolder", folder);
            }
            if (!changed) return source;
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter result = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(result));
            int declaration = source.startsWith("<?xml") ? source.indexOf("?>") + 2 : 0;
            return (declaration > 0 ? source.substring(0, declaration) + "\n" : "") + result + "\n";
        } catch (Exception e) {
            throw new CoreException(Activator.error("Cannot configure the Maven POM.", e));
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> matches = new ArrayList<>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) matches.add(element);
        }
        return matches;
    }

    private static Element child(Element parent, String name) {
        return children(parent, name).stream().findFirst().orElse(null);
    }

    private static String text(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static Element ensure(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? append(parent, name) : child;
    }

    private static boolean set(Element parent, String name, String value) {
        if (value.equals(text(parent, name))) return false;
        ensure(parent, name).setTextContent(value);
        return true;
    }

    private static Element append(Element parent, String name) {
        var document = parent.getOwnerDocument();
        String prefix = parent.getPrefix();
        Element child = document.createElementNS(parent.getNamespaceURI(), prefix == null ? name : prefix + ":" + name);
        int depth = 1;
        for (var node = parent.getParentNode(); node instanceof Element; node = node.getParentNode()) depth++;
        var end = parent.getLastChild();
        if (end == null || end.getNodeType() != org.w3c.dom.Node.TEXT_NODE || !end.getNodeValue().isBlank()) {
            end = parent.appendChild(document.createTextNode("\n" + "  ".repeat(depth - 1)));
        }
        parent.insertBefore(document.createTextNode("\n" + "  ".repeat(depth)), end);
        parent.insertBefore(child, end);
        return child;
    }
}
