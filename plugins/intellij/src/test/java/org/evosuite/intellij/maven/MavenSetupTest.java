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

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import org.evosuite.ide.GenerationOptions;

public class MavenSetupTest extends LightJavaCodeInsightFixtureTestCase {
    public void testFolderChangeUpdatesExecutionWithoutDuplicatingOrDroppingOtherGoals() {
        var xml =
                (XmlFile)
                        myFixture.addFileToProject(
                                "pom.xml",
                                "<project><build><plugins><plugin><groupId>org.evosuite.plugins</groupId>"
                                    + "<artifactId>evosuite-maven-plugin</artifactId><executions><execution>"
                                    + "<id>custom-setup</id><goals><goal>info</goal><goal>setup</goal></goals>"
                                    + "<configuration><testFolder>old</testFolder><agent>false</agent></configuration>"
                                    + "</execution></executions></plugin></plugins></build></project>");
        var options = new GenerationOptions();
        options.testFolder = "src/test/new";
        WriteCommandAction.runWriteCommandAction(
                getProject(),
                () -> {
                    MavenSupport.configureSetup(xml, options, "custom-setup");
                    MavenSupport.configureSetup(xml, options, "custom-setup");
                });
        var plugin =
                xml.getRootTag()
                        .findFirstSubTag("build")
                        .findFirstSubTag("plugins")
                        .findFirstSubTag("plugin");
        var executions = plugin.findFirstSubTag("executions").findSubTags("execution");
        assertEquals(1, executions.length);
        assertEquals(2, executions[0].findFirstSubTag("goals").findSubTags("goal").length);
        assertEquals(
                "src/test/new",
                executions[0].findFirstSubTag("configuration").getSubTagText("testFolder"));
        assertEquals(
                "false", executions[0].findFirstSubTag("configuration").getSubTagText("agent"));
    }

    public void testInheritedExecutionIdIsOverriddenInChild() {
        var xml = (XmlFile) myFixture.addFileToProject("pom.xml", "<project/>");
        WriteCommandAction.runWriteCommandAction(
                getProject(),
                () -> MavenSupport.configureSetup(xml, new GenerationOptions(), "inherited-setup"));
        var execution =
                xml.getRootTag()
                        .findFirstSubTag("build")
                        .findFirstSubTag("plugins")
                        .findFirstSubTag("plugin")
                        .findFirstSubTag("executions")
                        .findFirstSubTag("execution");
        assertEquals("inherited-setup", execution.getSubTagText("id"));
        assertEquals("setup", execution.findFirstSubTag("goals").getSubTagText("goal"));
    }

    public void testExactSnapshotDependencyIsIdempotentAndPreservesExistingScope() {
        var xml = (XmlFile) myFixture.addFileToProject("pom.xml", "<project/>");
        WriteCommandAction.runWriteCommandAction(
                getProject(),
                () -> {
                    MavenSupport.configureTestDependency(
                            xml, "org.evosuite", "evosuite-standalone-runtime", "1.2.1-SNAPSHOT");
                    MavenSupport.configureTestDependency(
                            xml, "org.evosuite", "evosuite-standalone-runtime", "1.2.1-SNAPSHOT");
                });
        var dependencies =
                xml.getRootTag().findFirstSubTag("dependencies").findSubTags("dependency");
        assertEquals(1, dependencies.length);
        assertEquals("test", dependencies[0].getSubTagText("scope"));
        assertEquals("1.2.1-SNAPSHOT", dependencies[0].getSubTagText("version"));
        WriteCommandAction.runWriteCommandAction(
                getProject(),
                () -> {
                    dependencies[0].findFirstSubTag("scope").getValue().setText("compile");
                    MavenSupport.configureTestDependency(
                            xml, "org.evosuite", "evosuite-standalone-runtime", "2.0");
                });
        assertEquals("compile", dependencies[0].getSubTagText("scope"));
        assertEquals("2.0", dependencies[0].getSubTagText("version"));
    }
}
