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
package org.evosuite.intellij;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import org.evosuite.ide.*;
import org.evosuite.intellij.maven.*;
import org.jdom.Element;
import org.jetbrains.idea.maven.importing.MavenWorkspaceConfigurator;
import org.jetbrains.idea.maven.model.*;
import org.jetbrains.idea.maven.project.*;

import java.nio.file.*;
import java.util.*;

public class MavenModelTest extends LightJavaCodeInsightFixtureTestCase {
    public void testEffectiveModelMappingAndSetupFolder() {
        var pom =
                myFixture
                        .addFileToProject(
                                "pom.xml",
                                "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>demo</artifactId><version>1</version></project>")
                        .getVirtualFile();
        MavenModel effective = new MavenModel();
        effective.setMavenId(new MavenId("demo", "demo", "1"));
        effective.setPackaging("jar");
        effective.setProperties(Map.of("budget", "90s", "folder", "src/test/generated"));
        Element config =
                new Element("configuration")
                        .addContent(new Element("timePerClass").setText("${budget}"));
        Element cli =
                new Element("configuration").addContent(new Element("criterion").setText("BRANCH"));
        Element setup =
                new Element("configuration")
                        .addContent(new Element("testFolder").setText("${folder}"));
        effective.setPlugins(
                List.of(
                        new MavenPlugin(
                                PomSettings.GROUP_ID,
                                PomSettings.ARTIFACT_ID,
                                "1.2.1-SNAPSHOT",
                                false,
                                false,
                                config,
                                List.of(
                                        new MavenPlugin.Execution(
                                                "default-cli", List.of("generate"), cli),
                                        new MavenPlugin.Execution(
                                                "setup", List.of("setup"), setup)),
                                List.of())));
        MavenProject model = new MavenProject(pom);
        model.updateFromReaderResult(
                new MavenProjectReaderResult(
                        effective, Map.of(), MavenExplicitProfiles.NONE, List.of()),
                Path.of(System.getProperty("java.io.tmpdir")),
                false);
        GenerationOptions options = new GenerationOptions();
        assertNotNull(MavenSupport.read(model));
        MavenSupport.read(model).applyTo(options, null);
        assertEquals(90, options.searchBudget);
        assertEquals("BRANCH", options.criterion);
        assertEquals("src/test/generated", options.testFolder);
        var folders = new EvoSuiteMavenFolders().getAdditionalFolders(() -> model).toList();
        assertEquals(1, folders.size());
        assertEquals(
                MavenWorkspaceConfigurator.FolderType.TEST_SOURCE, folders.getFirst().getType());
        assertTrue(
                folders.getFirst().getPath(),
                folders.getFirst().getPath().endsWith("src/test/generated"));
    }

    public void testMavenServerPasswordDoesNotReturnEncryptedTextOrLoadExternalEntities()
            throws Exception {
        Path settings = Files.createTempFile("evosuite-maven-settings-", ".xml");
        try {
            Files.writeString(
                    settings,
                    "<settings><servers><server><id>llm</id><password>plaintext-key</password></server></servers></settings>");
            assertEquals("plaintext-key", MavenServerPassword.read(settings, "llm", ignored -> {}));
            Files.writeString(
                    settings,
                    "<settings><servers><server><id>llm</id><password>{invalid-encrypted-password}</password></server></servers></settings>");
            List<String> warnings = new ArrayList<>();
            assertEquals("", MavenServerPassword.read(settings, "llm", warnings::add));
            assertEquals(1, warnings.size());
            assertFalse(warnings.toString().contains("invalid-encrypted-password"));
        } finally {
            Files.deleteIfExists(settings);
        }
    }
}
