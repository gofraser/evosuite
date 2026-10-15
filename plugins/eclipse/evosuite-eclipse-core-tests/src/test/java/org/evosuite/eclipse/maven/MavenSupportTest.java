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
package org.evosuite.eclipse.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.ResolverConfiguration;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Settings come from the effective model that m2e builds: plugin management in a parent,
 * execution configuration, and properties.
 */
public class MavenSupportTest {

    private static final String PARENT = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>t</groupId><artifactId>parent</artifactId><version>1</version>\n"
            + "  <packaging>pom</packaging>\n"
            + "  <build><pluginManagement><plugins><plugin>\n"
            + "    <groupId>org.evosuite.plugins</groupId><artifactId>evosuite-maven-plugin</artifactId>\n"
            + "    <version>1.2.1-SNAPSHOT</version>\n"
            + "    <configuration><timeInMinutesPerClass>3</timeInMinutesPerClass>"
            + "<criterion>LINE</criterion></configuration>\n"
            + "  </plugin></plugins></pluginManagement></build>\n"
            + "</project>\n";

    private static final String CHILD = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <parent><groupId>t</groupId><artifactId>parent</artifactId><version>1</version>"
            + "<relativePath>../maven-parent/pom.xml</relativePath></parent>\n"
            + "  <artifactId>child</artifactId><packaging>pom</packaging>\n"
            + "  <properties><generated.tests>src/test/generated</generated.tests></properties>\n"
            + "  <build><plugins><plugin>\n"
            + "    <groupId>org.evosuite.plugins</groupId><artifactId>evosuite-maven-plugin</artifactId>\n"
            + "    <configuration><criterion>BRANCH</criterion>"
            + "<testFolder>${generated.tests}</testFolder>"
            + "<evosuiteProperties><llm_provider>OLLAMA</llm_provider><llm_model>qwen3</llm_model>"
            + "</evosuiteProperties><llmApiKeyServerId>llm</llmApiKeyServerId></configuration>\n"
            + "    <executions><execution><id>default-cli</id>\n"
            + "      <configuration><extraArgs>-Dminimize=false</extraArgs>"
            + "<evosuiteProperties><llm_model>a model with spaces</llm_model></evosuiteProperties>"
            + "</configuration>\n"
            + "    </execution><execution><id>gen</id><goals><goal>generate</goal></goals>\n"
            + "      <configuration><criterion>EXECUTION</criterion><extraArgs>-Dother=true</extraArgs>"
            + "</configuration>\n"
            + "    </execution></executions>\n"
            + "  </plugin></plugins></build>\n"
            + "</project>\n";

    /** Uses the parent's plugin management without declaring the plug-in. */
    private static final String MANAGED = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <parent><groupId>t</groupId><artifactId>parent</artifactId><version>1</version>"
            + "<relativePath>../maven-parent/pom.xml</relativePath></parent>\n"
            + "  <artifactId>managed</artifactId><packaging>pom</packaging>\n"
            + "</project>\n";

    /** Sets EvoSuite properties, such as the version of its runtime, without using the plug-in. */
    private static final String ONLY_PROPERTIES = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>t</groupId><artifactId>only-props</artifactId><version>1</version><packaging>pom</packaging>\n"
            + "  <properties><evosuite.version>1.2.1-SNAPSHOT</evosuite.version>"
            + "<evosuite.timeInMinutesPerClass>4</evosuite.timeInMinutesPerClass></properties>\n"
            + "</project>\n";

    private static final String PLAIN = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>t</groupId><artifactId>plain</artifactId><version>1</version><packaging>pom</packaging>\n"
            + "</project>\n";

    private static final String BY_PROPERTIES = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>t</groupId><artifactId>props</artifactId><version>1</version><packaging>pom</packaging>\n"
            + "  <properties><evosuite.timeInMinutesPerClass>4</evosuite.timeInMinutesPerClass></properties>\n"
            + "  <build><plugins><plugin>\n"
            + "    <groupId>org.evosuite.plugins</groupId><artifactId>evosuite-maven-plugin</artifactId>\n"
            + "    <version>1.2.1-SNAPSHOT</version>\n"
            + "    <configuration><memoryInMB>2000</memoryInMB><numberOfCores>2</numberOfCores></configuration>\n"
            + "    <executions><execution><id>wire</id><goals><goal>setup</goal></goals>\n"
            + "      <configuration><testFolder>src/test/generated</testFolder></configuration>\n"
            + "    </execution></executions>\n"
            + "  </plugin></plugins></build>\n"
            + "</project>\n";

    private static final String JUNIT4_ONLY = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>t</groupId><artifactId>junit4only</artifactId><version>1</version><packaging>pom</packaging>\n"
            + "  <dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId>"
            + "<version>4.13.2</version><scope>test</scope></dependency></dependencies>\n"
            + "  <build><plugins><plugin>\n"
            + "    <groupId>org.evosuite.plugins</groupId><artifactId>evosuite-maven-plugin</artifactId>\n"
            + "    <version>1.2.1-SNAPSHOT</version>\n"
            + "    <configuration><timePerClass>90s</timePerClass></configuration>\n"
            + "  </plugin></plugins></build>\n"
            + "</project>\n";

    private final IEclipsePreferences workspace = InstanceScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);

    @AfterEach
    public void clear() throws Exception {
        workspace.clear();
    }

    private static IProject mavenProject(String name, String pom) throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, null);
        }
        project.create(null);
        project.open(null);
        project.getFile("pom.xml").create(new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)), true, null);
        MavenPlugin.getProjectConfigurationManager().enableMavenNature(project, new ResolverConfiguration(),
                new NullProgressMonitor());
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
        while (MavenPlugin.getMavenProjectRegistry().getProject(project) == null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertNotNull(MavenPlugin.getMavenProjectRegistry().getProject(project), "m2e did not load " + name);
        return project;
    }

    @Test
    public void readsTheEffectiveConfiguration() throws Exception {
        mavenProject("maven-parent", PARENT);
        IProject child = mavenProject("maven-child", CHILD);

        GenerationOptions options = EvoSuitePreferences.resolve(child, null);
        assertEquals(180, options.searchBudget, "plugin management in the parent");
        assertEquals("BRANCH", options.criterion, "the child overrides the parent, not the gen execution");
        assertEquals("src/test/generated", options.testFolder, "a property");
        assertEquals("-Dminimize=false", options.pomExtraArguments, "the execution mvn evosuite:generate runs");
        assertEquals(java.util.Map.of("llm_provider", "OLLAMA", "llm_model", "a model with spaces"),
                options.pomProperties, "maps merge entry by entry");
        assertEquals("llm", options.llmApiKeyServerId);
        assertTrue(options.fromPom.contains("search budget 180 s (timeInMinutesPerClass)"), options.fromPom.toString());
    }

    @Test
    public void readsPluginManagementWithoutAPluginDeclaration() throws Exception {
        mavenProject("maven-parent", PARENT);
        GenerationOptions options = EvoSuitePreferences.resolve(mavenProject("maven-managed", MANAGED), null);
        assertEquals(180, options.searchBudget, options.fromPom.toString());
        assertEquals("LINE", options.criterion);
    }

    @Test
    public void evosuitePropertiesAloneDoNotMeanThePluginIsUsed() throws Exception {
        GenerationOptions options = EvoSuitePreferences.resolve(mavenProject("maven-only-props", ONLY_PROPERTIES),
                null);
        assertEquals(new GenerationOptions().searchBudget, options.searchBudget);
        assertEquals(List.of(), options.fromPom);
    }

    @Test
    public void readsPropertiesCoresAndTheSetupExecution() throws Exception {
        GenerationOptions options = EvoSuitePreferences.resolve(mavenProject("maven-props", BY_PROPERTIES), null);
        assertEquals(240, options.searchBudget);
        assertEquals(1000, options.clientMemory);
        assertEquals("src/test/generated", options.testFolder);
    }

    @Test
    public void readsServerPasswordsFromMavensSettings(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path settings = dir.resolve("settings.xml");
        java.nio.file.Files.writeString(settings, "<settings><servers>"
                + "<server><id>llm</id><password>sk-test</password></server>"
                + "<server><id>empty</id></server></servers></settings>");
        String previous = MavenPlugin.getMavenConfiguration().getUserSettingsFile();
        MavenPlugin.getMavenConfiguration().setUserSettingsFile(settings.toString());
        try {
            assertEquals("sk-test", MavenSupport.serverPassword("llm"));
            assertEquals(null, MavenSupport.serverPassword("empty"));
            assertEquals(null, MavenSupport.serverPassword("missing"));
        } finally {
            MavenPlugin.getMavenConfiguration().setUserSettingsFile(previous);
        }
    }

    @Test
    public void followsTheMavenPluginOnTimeAndJUnitVersion() throws Exception {
        GenerationOptions options = EvoSuitePreferences.resolve(mavenProject("maven-junit4", JUNIT4_ONLY), null);
        assertEquals(90, options.searchBudget);
        assertEquals(GenerationOptions.JUNIT4, options.testFormat, options.fromPom.toString());
    }

    @Test
    public void canBeSwitchedOff() throws Exception {
        mavenProject("maven-parent", PARENT);
        IProject child = mavenProject("maven-child", CHILD);
        workspace.putBoolean(EvoSuitePreferences.USE_POM_SETTINGS, false);

        GenerationOptions options = EvoSuitePreferences.resolve(child, null);
        assertEquals(new GenerationOptions().searchBudget, options.searchBudget);
        assertEquals(List.of(), options.fromPom);
    }

    @Test
    public void projectsWithoutThePluginKeepTheirSettings() throws Exception {
        IProject plain = mavenProject("maven-plain", PLAIN);
        GenerationOptions options = EvoSuitePreferences.resolve(plain, null);
        assertEquals(new GenerationOptions().searchBudget, options.searchBudget);
        assertEquals(List.of(), options.fromPom);
    }
}
