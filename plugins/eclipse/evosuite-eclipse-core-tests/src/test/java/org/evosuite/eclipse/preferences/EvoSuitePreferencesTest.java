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

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.ProjectScope;
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
        workspace.put(EvoSuitePreferences.LLM_PROVIDER, GenerationOptions.LLM_OLLAMA);
        workspace.put(EvoSuitePreferences.LLM_MODEL, " qwen3-coder ");
        workspace.putBoolean(EvoSuitePreferences.LLM_ON_STAGNATION, true);
        GenerationOptions fromWorkspace = EvoSuitePreferences.load(project.getProject());
        assertEquals(GenerationOptions.LLM_OLLAMA, fromWorkspace.llmProvider);
        assertEquals("qwen3-coder", fromWorkspace.llmModel);
        assertTrue(fromWorkspace.llmOnStagnation);

        IEclipsePreferences own = new ProjectScope(project.getProject()).getNode(EvoSuitePreferences.QUALIFIER);
        own.putBoolean(EvoSuitePreferences.USE_PROJECT_SETTINGS, true);
        own.put(EvoSuitePreferences.LLM_PROVIDER, GenerationOptions.LLM_ANTHROPIC);
        GenerationOptions fromProject = EvoSuitePreferences.load(project.getProject());
        assertEquals(GenerationOptions.LLM_ANTHROPIC, fromProject.llmProvider);
        // Unset project values take the defaults, not the workspace's.
        assertEquals("", fromProject.llmModel);
        assertFalse(fromProject.llmOnStagnation);
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
