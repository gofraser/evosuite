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
package org.evosuite.eclipse.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.launch.SettingKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class EvoSuitePreferencesTest {

    private final IEclipsePreferences workspace = InstanceScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);

    @AfterEach
    public void clear() throws Exception {
        workspace.clear();
    }

    @Test
    public void projectSettingsReplaceTheWorkspaceOnes() throws Exception {
        IJavaProject project = TestProjects.create("prefs", "src", null);
        workspace.put(SettingKeys.LLM_PROVIDER, GenerationOptions.LLM_OLLAMA);
        workspace.put(SettingKeys.LLM_MODEL, " qwen3-coder ");
        workspace.putBoolean(SettingKeys.LLM_ON_STAGNATION, true);
        GenerationOptions fromWorkspace = EvoSuitePreferences.load(project.getProject());
        assertEquals(GenerationOptions.LLM_OLLAMA, fromWorkspace.llmProvider);
        assertEquals("qwen3-coder", fromWorkspace.llmModel);
        assertTrue(fromWorkspace.llmOnStagnation);

        IEclipsePreferences own = new ProjectScope(project.getProject()).getNode(EvoSuitePreferences.QUALIFIER);
        own.putBoolean(EvoSuitePreferences.USE_PROJECT_SETTINGS, true);
        own.put(SettingKeys.LLM_PROVIDER, GenerationOptions.LLM_ANTHROPIC);
        GenerationOptions fromProject = EvoSuitePreferences.load(project.getProject());
        assertEquals(GenerationOptions.LLM_ANTHROPIC, fromProject.llmProvider);
        // Unset project values take the defaults, not the workspace's.
        assertEquals("", fromProject.llmModel);
        assertFalse(fromProject.llmOnStagnation);
    }

    /**
     * The keys below are spelled out on purpose: they are what existing projects have committed
     * in {@code .settings}, so a rename of a field or constant must not change them.
     */
    @Test
    public void aPrefsFileWrittenWithTheStoredKeysIsReadBack() throws Exception {
        IJavaProject project = TestProjects.create("stored", "src", null);
        String prefs = String.join("\n",
                "eclipse.preferences.version=1",
                "useProjectSettings=true",
                "searchBudget=17",
                "randomSeed=-42",
                "criterion=LINE\\:BRANCH",
                "assertions=false",
                "minimize=false",
                "minimizeValues=true",
                "noRuntimeDependency=true",
                "assertionStrategy=ALL",
                "testFormat=JUNIT4",
                "testFolder=src/gen",
                "testSuffix=Gen",
                "testNaming=COVERAGE",
                "variableNaming=HEURISTICS_BASED",
                "testComments=true",
                "testTimeout=500",
                "openGeneratedTests=false",
                "llmProvider=OPENAI",
                "llmModel= gpt-5 ",
                "llmBaseUrl= http\\://localhost\\:1234 ",
                "llmApproach=LLM_ITERATIVE",
                "llmSeeding=FULL",
                "llmOnStagnation=true",
                "llmOperators=true",
                "llmPostprocessing=true",
                "llmMaxCalls=9",
                "llmContextMode=SOURCE_CODE",
                "llmMaxTokens=1000",
                "llmTimeoutSeconds=12",
                "llmRetryMaxAttempts=5",
                "llmTemperature=1.5",
                "javaHome=/opt/jdk",
                "clientMemory=2048",
                "extraArguments=-Dsandbox\\=false",
                "usePomSettings=false",
                "configureBuildPath=false",
                "");
        IFolder settings = project.getProject().getFolder(".settings");
        if (!settings.exists()) {
            settings.create(true, true, null);
        }
        IFile file = settings.getFile("org.evosuite.plugins.eclipse.core.prefs");
        ByteArrayInputStream content = new ByteArrayInputStream(prefs.getBytes(StandardCharsets.ISO_8859_1));
        if (file.exists()) {
            file.setContents(content, true, false, null);
        } else {
            file.create(content, true, null);
        }
        new ProjectScope(project.getProject()).getNode(EvoSuitePreferences.QUALIFIER).sync();

        assertTrue(EvoSuitePreferences.usesProjectSettings(project.getProject()));
        GenerationOptions o = EvoSuitePreferences.load(project.getProject());
        assertEquals(17, o.searchBudget);
        assertEquals("-42", o.randomSeed);
        assertEquals("LINE:BRANCH", o.criterion);
        assertFalse(o.assertions);
        assertFalse(o.minimize);
        assertTrue(o.minimizeValues);
        assertTrue(o.noRuntimeDependency);
        assertEquals("ALL", o.assertionStrategy);
        assertEquals("JUNIT4", o.testFormat);
        assertEquals("src/gen", o.testFolder);
        assertEquals("Gen", o.testSuffix);
        assertEquals("COVERAGE", o.testNaming);
        assertEquals("HEURISTICS_BASED", o.variableNaming);
        assertTrue(o.testComments);
        assertEquals(500, o.testTimeout);
        assertFalse(o.openGeneratedTests);
        assertEquals("OPENAI", o.llmProvider);
        assertEquals("gpt-5", o.llmModel);
        assertEquals("http://localhost:1234", o.llmBaseUrl);
        assertEquals("LLM_ITERATIVE", o.llmApproach);
        assertEquals("FULL", o.llmSeeding);
        assertTrue(o.llmOnStagnation);
        assertTrue(o.llmOperators);
        assertTrue(o.llmPostprocessing);
        assertEquals(9, o.llmMaxCalls);
        assertEquals("SOURCE_CODE", o.llmContextMode);
        assertEquals(1000, o.llmMaxTokens);
        assertEquals(12, o.llmTimeoutSeconds);
        assertEquals(5, o.llmRetryMaxAttempts);
        assertEquals("1.5", o.llmTemperature);
        assertEquals("/opt/jdk", o.javaHome);
        assertEquals(2048, o.clientMemory);
        assertEquals("-Dsandbox=false", o.extraArguments);
        assertFalse(o.usePomSettings);
        assertFalse(o.configureBuildPath);
    }

    @Test
    public void everyDefaultIsRegisteredUnderItsOwnKey() throws Exception {
        EvoSuitePreferences.initializeDefaults();
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);
        assertEquals("false", defaults.get(EvoSuitePreferences.USE_PROJECT_SETTINGS, null));
        for (String key : SettingKeys.all()) {
            assertEquals(SettingKeys.defaultValue(key), defaults.get(key, null), key);
        }
    }

    @Test
    public void thePropertyPageEnablesItsFieldsWithTheProjectSettings() throws Exception {
        IJavaProject project = TestProjects.create("page", "src", null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            Shell shell = new Shell(Display.getDefault());
            try {
                EvoSuitePropertyPage page = new EvoSuitePropertyPage();
                page.setElement(project.getProject());
                page.createControl(shell);
                // The project page leaves the workspace-wide API key out.
                assertFalse(texts(shell).stream().anyMatch(t -> (t.getStyle() & org.eclipse.swt.SWT.PASSWORD) != 0
                        || t.getEchoChar() != 0));
                assertFalse(page.settingsEnabled());
                page.setUseProjectSettings(true);
                assertTrue(page.settingsEnabled());
                page.performDefaults();
                assertFalse(page.settingsEnabled());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                shell.dispose();
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static java.util.List<Text> texts(Composite parent) {
        java.util.List<Text> result = new java.util.ArrayList<>();
        for (Control child : parent.getChildren()) {
            if (child instanceof Text text) {
                result.add(text);
            } else if (child instanceof Composite composite) {
                result.addAll(texts(composite));
            }
        }
        return result;
    }
}
