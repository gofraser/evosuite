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
import com.intellij.util.xmlb.XmlSerializer;

import org.evosuite.ide.*;
import org.evosuite.intellij.settings.*;

import java.util.*;

public class SettingsTest extends LightJavaCodeInsightFixtureTestCase {
    public void testProjectStateIsShareableAndHasNoKey() {
        var state = new EvoSuiteProjectSettings.State();
        state.useProjectSettings = true;
        state.values.put("searchBudget", "90");
        var xml = XmlSerializer.serialize(state);
        var roundtrip = XmlSerializer.deserialize(xml, EvoSuiteProjectSettings.State.class);
        assertEquals(state.values, roundtrip.values);
        assertTrue(roundtrip.useProjectSettings);
        assertFalse(com.intellij.openapi.util.JDOMUtil.writeElement(xml).contains("apiKey"));
    }

    public void testProjectSwitchUsesDefaultsInsteadOfIdeValues() {
        var app = EvoSuiteAppSettings.getInstance().getState();
        var previous = new TreeMap<>(app.values);
        var local = EvoSuiteProjectSettings.getInstance(getProject()).getState();
        var previousLocal = new TreeMap<>(local.values);
        boolean previousSwitch = local.useProjectSettings;
        try {
            app.values.put("searchBudget", "180");
            local.values.clear();
            local.useProjectSettings = false;
            assertEquals(180, SettingsResolver.resolve(getModule()).searchBudget);
            local.useProjectSettings = true;
            assertEquals(60, SettingsResolver.resolve(getModule()).searchBudget);
            local.values.put("searchBudget", "90");
            assertEquals(90, SettingsResolver.resolve(getModule()).searchBudget);
        } finally {
            app.values = previous;
            local.values = previousLocal;
            local.useProjectSettings = previousSwitch;
        }
    }

    public void testBundledEngineAndRuntimeAreOutsidePluginLibraries() throws Exception {
        var engine = org.evosuite.intellij.run.EngineJars.engine();
        var runtime = org.evosuite.intellij.run.EngineJars.runtime();
        assertTrue(java.nio.file.Files.isRegularFile(engine));
        assertTrue(java.nio.file.Files.isRegularFile(runtime));
        assertEquals("engine", engine.getParent().getFileName().toString());
        assertEquals(engine.getParent(), runtime.getParent());
        try (var zip = new java.util.zip.ZipFile(engine.toFile())) {
            var metadata = new Properties();
            try (var in =
                    zip.getInputStream(
                            zip.getEntry(
                                    "META-INF/maven/org.evosuite/evosuite-master/pom.properties"))) {
                metadata.load(in);
            }
            assertEquals(
                    metadata.getProperty("version"), org.evosuite.intellij.run.EngineJars.VERSION);
        }
    }

    public void testIdeSettingsCanBeIndexedWithoutAccessingSecureStorage() {
        var configurable = new EvoSuiteApplicationConfigurable();
        try {
            assertNotNull(configurable.createComponent());
            configurable.reset();
            assertFalse(configurable.isModified());
        } finally {
            configurable.disposeUIResources();
        }
    }

    public void testLlmOnlyKeepsItsBudgetAndCriteriaEditable() {
        var state = EvoSuiteProjectSettings.getInstance(getProject()).getState();
        var previous = new TreeMap<>(state.values);
        boolean previousSwitch = state.useProjectSettings;
        var configurable = new EvoSuiteProjectConfigurable(getProject());
        try {
            state.useProjectSettings = true;
            state.values.put("llmProvider", "OPENAI");
            state.values.put("llmApproach", "LLM_ITERATIVE");
            var ui = configurable.createComponent();
            for (String key : java.util.List.of("searchBudget", "criterion", "minimize")) {
                var field = findField(ui, key);
                assertNotNull(key, field);
                assertTrue(key, field.isEnabled());
            }
            assertFalse(findField(ui, "llmSeeding").isEnabled());
        } finally {
            configurable.disposeUIResources();
            state.values = previous;
            state.useProjectSettings = previousSwitch;
        }
    }

    public void testInvalidProjectOverridesCanBeDisabled() throws Exception {
        var state = EvoSuiteProjectSettings.getInstance(getProject()).getState();
        var previous = new TreeMap<>(state.values);
        boolean previousSwitch = state.useProjectSettings;
        var configurable = new EvoSuiteProjectConfigurable(getProject());
        try {
            state.useProjectSettings = true;
            state.values.put("searchBudget", "invalid-old-setting");
            var ui = configurable.createComponent();
            var checkbox = findCheckbox(ui, "Use project-specific settings (.idea/evosuite.xml)");
            assertNotNull(checkbox);
            checkbox.doClick();
            configurable.apply();
            assertFalse(state.useProjectSettings);
        } finally {
            configurable.disposeUIResources();
            state.values = previous;
            state.useProjectSettings = previousSwitch;
        }
    }

    public void testEmptyCriteriaAreRejectedRatherThanRestoringAllDefaults() throws Exception {
        var configurable = new EvoSuiteApplicationConfigurable();
        try {
            var ui = configurable.createComponent();
            for (String criterion : GenerationOptions.DEFAULT_CRITERIA) {
                var checkbox = findCheckbox(ui, criterion);
                assertNotNull(checkbox);
                checkbox.setSelected(false);
            }
            try {
                ((com.intellij.openapi.options.Configurable) configurable).apply();
                fail("Empty criteria must be rejected");
            } catch (com.intellij.openapi.options.ConfigurationException expected) {
                assertTrue(expected.getMessage().contains("criterion"));
            }
        } finally {
            configurable.disposeUIResources();
        }
    }

    public void testSecureKeyFailureKeepsApplyPendingUntilRetrySucceeds() throws Exception {
        var state = EvoSuiteAppSettings.getInstance().getState();
        var previous = new TreeMap<>(state.values);
        int[] attempts = {0};
        var configurable =
                new EvoSuiteConfigurable(null) {
                    @Override
                    protected void storeApiKey(String password) {
                        assertEquals("example-key", password);
                        if (++attempts[0] == 1)
                            throw new IllegalStateException("Simulated secure-store failure");
                    }
                };
        try {
            var ui = configurable.createComponent();
            ((javax.swing.JPasswordField) findField(ui, "llmApiKey")).setText("example-key");
            try {
                ((com.intellij.openapi.options.Configurable) configurable).apply();
                fail("Store failure must reach Apply");
            } catch (com.intellij.openapi.options.ConfigurationException expected) {
                assertTrue(configurable.isModified());
            }
            configurable.apply();
            assertEquals(2, attempts[0]);
            assertFalse(configurable.isModified());
        } finally {
            configurable.disposeUIResources();
            state.values = previous;
        }
    }

    private static javax.swing.JCheckBox findCheckbox(java.awt.Component component, String label) {
        if (component instanceof javax.swing.JCheckBox box && label.equals(box.getText()))
            return box;
        if (component instanceof java.awt.Container parent)
            for (var child : parent.getComponents()) {
                var found = findCheckbox(child, label);
                if (found != null) return found;
            }
        return null;
    }

    private static java.awt.Component findField(java.awt.Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof java.awt.Container parent) {
            for (var child : parent.getComponents()) {
                var found = findField(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    public void testConfigurableResetApplyAndModified() throws Exception {
        var configurable = new EvoSuiteProjectConfigurable(getProject());
        try {
            assertNotNull(configurable.createComponent());
            configurable.reset();
            assertFalse(configurable.isModified());
            configurable.apply();
            assertFalse(configurable.isModified());
        } finally {
            configurable.disposeUIResources();
        }
    }
}
