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

import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.w3c.dom.*;

import java.nio.file.*;
import java.util.function.Consumer;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Maven 3 decorated encryption. Unknown formats fall back to PasswordSafe, never to encrypted text.
 */
public final class MavenServerPassword {
    private MavenServerPassword() {}

    public static String read(Path settings, String id, Consumer<String> console) {
        try {
            if (!Files.isRegularFile(settings))
                throw new IllegalArgumentException("Maven settings.xml was not found");
            NodeList servers = xml(settings).getElementsByTagName("server");
            for (int i = 0; i < servers.getLength(); i++) {
                Element server = (Element) servers.item(i);
                if (!id.equals(text(server, "id"))) continue;
                String password = text(server, "password");
                if (password.startsWith("${env.") && password.endsWith("}"))
                    password = System.getenv(password.substring(6, password.length() - 1));
                if (password == null || password.isBlank())
                    throw new IllegalArgumentException("Maven server has no password");
                if (!password.contains("{")) return password.trim();
                Path security =
                        securityPath(
                                System.getProperty(
                                        "settings.security",
                                        Path.of(
                                                        System.getProperty("user.home"),
                                                        ".m2",
                                                        "settings-security.xml")
                                                .toString()),
                                null);
                Element config = securityConfig(security);
                DefaultPlexusCipher cipher = new DefaultPlexusCipher();
                String master =
                        cipher.decryptDecorated(text(config, "master"), "settings.security");
                return cipher.decryptDecorated(password, master).trim();
            }
            throw new IllegalArgumentException("Maven server was not found");
        } catch (Exception ex) {
            console.accept(
                    "Could not read/decrypt the key of Maven server "
                            + id
                            + ". Using the EvoSuite secure key instead.");
            return "";
        }
    }

    static Path securityPath(String value, Path relativeTo) {
        if (value.startsWith("~/") || value.startsWith("~\\"))
            return Path.of(System.getProperty("user.home")).resolve(value.substring(2)).normalize();
        Path path = Path.of(value);
        return !path.isAbsolute() && relativeTo != null
                ? relativeTo.resolve(path).normalize()
                : path.toAbsolutePath().normalize();
    }

    private static Element securityConfig(Path security) throws Exception {
        java.util.Set<Path> seen = new java.util.HashSet<>();
        for (int depth = 0; depth < 10 && seen.add(security); depth++) {
            Element config = xml(security).getDocumentElement();
            String relocation = text(config, "relocation");
            if (relocation.isBlank()) return config;
            security = securityPath(relocation, security.getParent());
        }
        throw new IllegalArgumentException("Cyclic or excessive Maven security relocation");
    }

    private static Document xml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (var in = Files.newInputStream(path)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        return children.getLength() == 0 ? "" : children.item(0).getTextContent().trim();
    }
}
