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
    public void testValueMinimizationCheckboxPersistsInIdeAndProjectSettings() throws Exception {
        var app = EvoSuiteAppSettings.getInstance().getState();
        var local = EvoSuiteProjectSettings.getInstance(getProject()).getState();
        var oldApp = new TreeMap<>(app.values);
        var oldLocal = new TreeMap<>(local.values);
        boolean oldSwitch = local.useProjectSettings;
        try {
            for (boolean projectScope : List.of(false, true)) {
                app.values.clear(); local.values.clear(); local.useProjectSettings = projectScope;
                var configurable = projectScope ? new EvoSuiteProjectConfigurable(getProject()) : new EvoSuiteApplicationConfigurable();
                try {
                    var ui = configurable.createComponent();
                    var values = (javax.swing.JCheckBox) findField(ui, "minimizeValues");
                    var tests = (javax.swing.JCheckBox) findField(ui, "minimize");
                    assertNotNull(values);
                    assertFalse(values.isSelected());
                    assertTrue(values.isEnabled());
                    values.doClick();
                    configurable.apply();
                    assertTrue(SettingsResolver.resolve(getModule()).minimizeValues);
                    var state = projectScope ? local.values : app.values;
                    assertEquals("true", state.get("minimizeValues"));
                    configurable.reset();
                    assertTrue(values.isSelected());
                    tests.doClick();
                    assertFalse(
                            "Value minimization requires test minimization", values.isEnabled());
                    assertTrue(
                            "Disabling the parent preserves the user's choice",
                            values.isSelected());
                    configurable.apply();
                    assertFalse(SettingsResolver.resolve(getModule()).minimize);
                    assertTrue(SettingsResolver.resolve(getModule()).minimizeValues);
                    tests.doClick();
                    assertTrue(values.isEnabled());
                    values.doClick();
                    configurable.apply();
                    assertFalse(SettingsResolver.resolve(getModule()).minimizeValues);
                    configurable.reset();
                    assertFalse(values.isSelected());
                } finally { configurable.disposeUIResources(); }
            }
        } finally { app.values = oldApp; local.values = oldLocal; local.useProjectSettings = oldSwitch; }
    }

    public void testValueMinimizationPreviewUsesInheritedIdeSettings() {
        var app = EvoSuiteAppSettings.getInstance().getState();
        var local = EvoSuiteProjectSettings.getInstance(getProject()).getState();
        var oldApp = new TreeMap<>(app.values);
        var oldLocal = new TreeMap<>(local.values);
        boolean oldSwitch = local.useProjectSettings;
        var configurable = new EvoSuiteProjectConfigurable(getProject());
        try {
            app.values.put("minimizeValues", "true"); app.values.put("minimize", "true");
            local.values.clear(); local.useProjectSettings = false;
            var ui = configurable.createComponent();
            var values = (javax.swing.JCheckBox) findField(ui, "minimizeValues");
            assertTrue(values.isSelected()); assertFalse(values.isEnabled());
            assertFalse(findField(ui,"chooseJavaHome").isEnabled());
            assertTrue(SettingsResolver.resolve(getModule()).minimizeValues);
            findCheckbox(ui, "Use project-specific settings (.idea/evosuite.xml)").doClick();
            assertFalse(values.isSelected()); assertTrue(values.isEnabled());
        } finally { configurable.disposeUIResources(); app.values = oldApp; local.values = oldLocal; local.useProjectSettings = oldSwitch; }
    }

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

    public void testLogoIsAvailableAtPluginAndActionSizes() {
        var logo =
                com.intellij.openapi.util.IconLoader.getIcon(
                        "/META-INF/pluginIcon.svg", getClass());
        assertEquals(com.intellij.util.ui.JBUI.scale(40), logo.getIconWidth());
        for (String action :
                List.of(
                        "EvoSuite.GenerateTests",
                        "EvoSuite.GenerateMethodTests",
                        "EvoSuite.ExtendTests")) {
            var icon = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(action)
                    .getTemplatePresentation().getIcon();
            assertNotNull(action, icon);
            assertEquals(com.intellij.util.ui.JBUI.scale(16), icon.getIconWidth());
            assertEquals(com.intellij.util.ui.JBUI.scale(16), icon.getIconHeight());
        }
    }

    public void testDefaultsAreVisibleWithoutChangingAutomaticSettings() throws Exception {
        var state = EvoSuiteAppSettings.getInstance().getState();
        var previous = new TreeMap<>(state.values);
        var configurable = new EvoSuiteApplicationConfigurable();
        try {
            state.values.clear();
            var ui = configurable.createComponent();
            for (String name : SettingKeys.all()) {
                var field = findField(ui, name);
                if (field instanceof javax.swing.JComboBox combo) {
                    var label = (javax.swing.JLabel) combo.getRenderer().getListCellRendererComponent(
                            new javax.swing.JList(), combo.getSelectedItem(), -1, false, false);
                    assertFalse(name, label.getText().isBlank());
                    assertTrue(
                            name,
                            label.getText()
                                    .startsWith(
                                            name.equals("testFormat") ? "Automatic" : "Default"));
                } else if (field instanceof javax.swing.JTextField text) {
                    assertFalse(name + " must show a value", text.getText().isBlank());
                    var description = (javax.swing.JTextArea) findField(ui, name + ".default");
                    assertNotNull(name, description);
                    assertTrue(name, description.getText().startsWith("Default: "));
                }
            }
            assertFalse(configurable.isModified());
            configurable.apply();
            assertFalse(configurable.isModified());
            assertFalse(
                    "Automatic JUnit detection must remain unset",
                    state.values.containsKey("testFormat"));
            ((javax.swing.JComboBox) findField(ui, "testFormat")).setSelectedItem("JUNIT4");
            configurable.apply();
            assertEquals("JUNIT4", state.values.get("testFormat"));
            ((javax.swing.JComboBox) findField(ui, "testFormat")).setSelectedItem("");
            configurable.apply();
            assertFalse(state.values.containsKey("testFormat"));
        } finally {
            configurable.disposeUIResources();
            state.values = previous;
        }
    }

    public void testDisplayedDefaultsRoundTripWithoutBecomingOverrides() throws Exception {
        var state = EvoSuiteAppSettings.getInstance().getState();
        var previous = new TreeMap<>(state.values);
        var configurable = new EvoSuiteApplicationConfigurable();
        try {
            state.values.clear();
            var ui = configurable.createComponent();
            var budget = (javax.swing.JTextField) findField(ui, "searchBudget");
            assertEquals("60", budget.getText());
            assertEquals("3000", ((javax.swing.JTextField) findField(ui, "testTimeout")).getText());
            assertEquals(
                    "src/test/evosuite",
                    ((javax.swing.JTextField) findField(ui, "testFolder")).getText());
            configurable.apply();
            assertFalse(state.values.containsKey("searchBudget"));
            assertFalse(state.values.containsKey("testTimeout"));
            budget.setText("90");
            assertTrue(configurable.isModified());
            configurable.apply();
            assertEquals("90", state.values.get("searchBudget"));
            assertFalse(configurable.isModified());
            budget.setText("");
            configurable.apply();
            assertEquals("60", budget.getText());
            assertFalse(state.values.containsKey("searchBudget"));
            state.values.put("searchBudget", "60");
            configurable.reset();
            configurable.apply();
            assertEquals(
                    "Explicit stored defaults remain explicit",
                    "60",
                    state.values.get("searchBudget"));
            var arguments = (javax.swing.JTextField) findField(ui, "extraArguments");
            var automatic = (javax.swing.JCheckBox) findField(ui, "extraArguments.automatic");
            assertTrue(automatic.isSelected());
            assertFalse(arguments.isEditable());
            assertEquals("None", arguments.getText());
            automatic.doClick();
            assertTrue(arguments.isEditable());
            arguments.setText("-Dsandbox=false");
            configurable.apply();
            assertEquals("-Dsandbox=false", state.values.get("extraArguments"));
            automatic.doClick();
            configurable.apply();
            assertFalse(state.values.containsKey("extraArguments"));
            assertEquals("None", arguments.getText());
            assertFalse(configurable.isModified());
        } finally {
            configurable.disposeUIResources();
            state.values = previous;
        }
    }

    public void testInheritedPreviewPreservesProjectDraftAndSupportsCopyAndRestore() throws Exception {
        var app = EvoSuiteAppSettings.getInstance().getState();
        var local = EvoSuiteProjectSettings.getInstance(getProject()).getState();
        var oldApp = new TreeMap<>(app.values);
        var oldLocal = new TreeMap<>(local.values);
        boolean oldSwitch = local.useProjectSettings;
        boolean oldDelegate = local.delegateToMaven;
        var configurable = new EvoSuiteProjectConfigurable(getProject());
        try {
            app.values.clear(); app.values.put("searchBudget", "180");
            local.values.clear();
            local.values.put("searchBudget", "90");
            local.useProjectSettings = false;
            local.delegateToMaven = true;
            var ui = configurable.createComponent();
            var budget = (javax.swing.JTextField) findField(ui, "searchBudget");
            var scope = findCheckbox(ui, "Use project-specific settings (.idea/evosuite.xml)");
            assertEquals("180", budget.getText()); assertFalse(budget.isEnabled());
            assertFalse(configurable.isModified());
            configurable.apply(); assertEquals("90", local.values.get("searchBudget"));
            scope.doClick(); assertEquals("90", budget.getText());
            budget.setText("75");
            scope.doClick(); assertEquals("180", budget.getText());
            scope.doClick(); assertEquals("75", budget.getText());
            ((javax.swing.JButton) findField(ui, "copyIdeDefaults")).doClick();
            assertEquals("180", budget.getText());
            assertEquals("90", local.values.get("searchBudget"));
            configurable.apply(); assertEquals("180", local.values.get("searchBudget"));
            ((javax.swing.JButton) findField(ui, "restoreDefaults")).doClick();
            assertEquals("60", budget.getText());
            configurable.apply(); assertFalse(local.values.containsKey("searchBudget"));
            assertFalse(local.delegateToMaven);
            assertFalse(configurable.isModified());
        } finally {
            configurable.disposeUIResources(); app.values = oldApp; local.values = oldLocal; local.useProjectSettings = oldSwitch; local.delegateToMaven = oldDelegate;
        }
    }

    public void testUnsupportedValuesAndInvalidNumbersAreExplainedAndPreserved() throws Exception {
        var state = EvoSuiteAppSettings.getInstance().getState();
        var previous = new TreeMap<>(state.values);
        var configurable = new EvoSuiteApplicationConfigurable();
        try {
            state.values.clear();
            state.values.put("searchBudget", "0");
            state.values.put("testNaming", "LEGACY_VALUE");
            var ui = configurable.createComponent();
            assertEquals(
                    "LEGACY_VALUE",
                    ((javax.swing.JComboBox) findField(ui, "testNaming")).getSelectedItem());
            assertFalse(configurable.isModified());
            assertInvalid(configurable, "Search budget per class");
            ((javax.swing.JTextField) findField(ui, "searchBudget")).setText("60");
            assertInvalid(configurable, "test method names");
            ((javax.swing.JComboBox) findField(ui, "testNaming")).setSelectedItem("NUMBERED");
            ((javax.swing.JComboBox) findField(ui, "llmProvider")).setSelectedItem("OLLAMA");
            ((javax.swing.JCheckBox) findField(ui, "llmBaseUrl.automatic")).setSelected(false);
            ((javax.swing.JTextField) findField(ui, "llmBaseUrl")).setText("localhost:11434");
            assertInvalid(configurable, "HTTP(S)");
            ((javax.swing.JTextField) findField(ui, "llmBaseUrl"))
                    .setText("HTTPS://example.test/v1");
            configurable.apply(); assertFalse(configurable.isModified());
            var naming = (javax.swing.JComboBox) findField(ui, "testNaming");
            for (int i = 0; i < naming.getItemCount(); i++) assertFalse("LEGACY_VALUE".equals(naming.getItemAt(i)));
        } finally { configurable.disposeUIResources(); state.values = previous; }
    }

    public void testExplicitDefaultCriteriaDoNotAppearModifiedOnOpen() throws Exception {
        var state = EvoSuiteAppSettings.getInstance().getState();
        var previous = new TreeMap<>(state.values);
        var configurable = new EvoSuiteApplicationConfigurable();
        try {
            state.values.clear();
            state.values.put("criterion", String.join(":", GenerationOptions.DEFAULT_CRITERIA));
            configurable.createComponent(); assertFalse(configurable.isModified());
            configurable.apply();
            assertEquals(
                    String.join(":", GenerationOptions.DEFAULT_CRITERIA),
                    state.values.get("criterion"));
            state.values.put("criterion", "BRANCH:LINE:INPUT");
            configurable.reset(); assertFalse(configurable.isModified());
            configurable.apply(); assertEquals("BRANCH:LINE:INPUT", state.values.get("criterion"));
        } finally { configurable.disposeUIResources(); state.values = previous; }
    }

    public void testSearchOnlyDoesNotLoadSecureStorageButManualKeyManagementWorks() throws Exception {
        var state = EvoSuiteAppSettings.getInstance().getState();
        var previous = new TreeMap<>(state.values);
        int[] reads = {0}; String[] saved = {null};
        var configurable = new EvoSuiteConfigurable(null) {
            @Override protected boolean mayAutomaticallyLoadApiKey() { return true; }
            @Override protected String loadApiKey() { reads[0]++; return "example-key"; }
            @Override protected void storeApiKey(String value) { saved[0] = value; }
        };
        try {
            state.values.clear();
            state.values.put("llmProvider", "OPENAI");
            state.values.put("llmApproach", "SEARCH_ONLY");
            var ui = configurable.createComponent();
            assertEquals(0, reads[0]);
            ((javax.swing.JButton) findField(ui, "loadApiKey")).doClick();
            var key = (javax.swing.JPasswordField) findField(ui, "llmApiKey");
            awaitEnabled(key); assertEquals(1, reads[0]);
            assertEquals("example-key", String.valueOf(key.getPassword()));
            ((javax.swing.JComboBox) findField(ui, "llmProvider")).setSelectedItem("NONE");
            assertTrue("Stored keys can be removed with LLM features disabled", key.isEnabled());
            key.setText(""); configurable.apply(); assertEquals("", saved[0]);
        } finally { configurable.disposeUIResources(); state.values = previous; }
    }

    public void testCloudKeyLoadsOnlyWhenCloudFeaturesBecomeActive() throws Exception {
        var state = EvoSuiteAppSettings.getInstance().getState();
        var previous = new TreeMap<>(state.values);
        int[] reads = {0};
        var configurable = new EvoSuiteConfigurable(null) {
            @Override protected boolean mayAutomaticallyLoadApiKey() { return true; }
            @Override protected String loadApiKey() { reads[0]++; return ""; }
        };
        try {
            state.values.clear();
            var ui = configurable.createComponent(); assertEquals(0, reads[0]);
            ((javax.swing.JComboBox) findField(ui, "llmProvider")).setSelectedItem("OLLAMA");
            assertEquals(0, reads[0]);
            ((javax.swing.JComboBox) findField(ui, "llmProvider")).setSelectedItem("OPENAI");
            awaitEnabled(findField(ui, "llmApiKey")); assertEquals(1, reads[0]);
            assertTrue(
                    ((javax.swing.JTextArea) findField(ui, "apiKeyStatus"))
                            .getText()
                            .contains("Enter a cloud API key"));
            assertEquals(
                    "Cloud API key",
                    findField(ui, "llmApiKey").getAccessibleContext().getAccessibleName());
        } finally {
            configurable.disposeUIResources();
            state.values = previous;
        }
    }

    public void testEmptyLegacyImportKeepsTheCurrentProviderDraft() throws Exception {
        var configurable =
                new EvoSuiteConfigurable(null) {
                    @Override
                    protected String loadLegacyApiKey() {
                        return "";
                    }
                };
        try {
            var ui=configurable.createComponent();
            var key = (javax.swing.JPasswordField) findField(ui, "llmApiKey");
            key.setText("current-provider-draft");
            ((javax.swing.JButton)findField(ui,"importLegacyApiKey")).doClick();
            var status=(javax.swing.JTextArea)findField(ui,"apiKeyStatus");
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!status.getText().startsWith("No legacy") && System.nanoTime() < until) {
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();Thread.sleep(10);}
            assertTrue(status.getText(),status.getText().startsWith("No legacy"));
            assertEquals("current-provider-draft",String.valueOf(key.getPassword()));
        } finally {configurable.disposeUIResources();}
    }

    public void testProviderCredentialDraftsStaySeparateAndAreSavedTogether() throws Exception {
        var state = EvoSuiteAppSettings.getInstance().getState();
        var previous = new TreeMap<>(state.values);
        List<String> saved = new ArrayList<>();
        javax.swing.JComboBox[] provider = {null};
        var configurable =
                new EvoSuiteConfigurable(null) {
                    @Override
                    protected void storeApiKey(String value) {
                        saved.add(value);
                    }
                };
        try {
            state.values.clear();
            var ui = configurable.createComponent();
            provider[0] = (javax.swing.JComboBox) findField(ui, "credentialProvider");
            var key = (javax.swing.JPasswordField) findField(ui, "llmApiKey");
            // Loading secure storage is optional: edits also support replacing an unreadable key.
            key.setText("openai-draft");
            provider[0].setSelectedItem("ANTHROPIC");
            assertEquals("", String.valueOf(key.getPassword()));
            key.setText("anthropic-draft");
            provider[0].setSelectedItem("OPENAI");
            assertEquals("openai-draft", String.valueOf(key.getPassword()));
            assertTrue(configurable.isModified());
            configurable.apply();
            assertFalse(configurable.isModified());
            // storeApiKey is invoked for both providers; the selector remains on the user's choice.
            assertEquals(Set.of("openai-draft", "anthropic-draft"), new HashSet<>(saved));
            assertFalse(state.values.toString().contains("draft"));
        } finally {
            configurable.disposeUIResources();
            state.values = previous;
        }
    }

    public void testSeedAndModelLimitsAreValidatedAndPersisted() throws Exception {
        var state = EvoSuiteAppSettings.getInstance().getState();
        var previous = new TreeMap<>(state.values);
        var configurable = new EvoSuiteApplicationConfigurable();
        try {
            state.values.clear();
            var ui = configurable.createComponent();
            ((javax.swing.JCheckBox) findField(ui, "randomSeed.automatic")).doClick();
            var seed = (javax.swing.JTextField) findField(ui, "randomSeed");
            seed.setText("9223372036854775808");
            assertInvalid(configurable, "signed 64-bit");
            seed.setText("-12345");
            ((javax.swing.JCheckBox) findField(ui, "noRuntimeDependency")).doClick();
            ((javax.swing.JComboBox) findField(ui, "llmProvider")).setSelectedItem("OLLAMA");
            var tokens = (javax.swing.JTextField) findField(ui, "llmMaxTokens");
            tokens.setText("0");
            assertInvalid(configurable, "greater than zero");
            tokens.setText("2048");
            var temperature = (javax.swing.JTextField) findField(ui, "llmTemperature");
            temperature.setText("NaN");
            assertInvalid(configurable, "between 0 and 2");
            temperature.setText("0.2");
            ((javax.swing.JComboBox) findField(ui, "llmContextMode"))
                    .setSelectedItem("SOURCE_CODE");
            configurable.apply();
            var options = SettingKeys.resolve(state.values);
            assertEquals("-12345", options.randomSeed);
            assertTrue(options.noRuntimeDependency);
            assertEquals(2048, options.llmMaxTokens);
            assertEquals("SOURCE_CODE", options.llmContextMode);
            configurable.reset();
            assertFalse(configurable.isModified());
        } finally { configurable.disposeUIResources(); state.values = previous; }
    }

    private static void assertInvalid(EvoSuiteConfigurable configurable, String text) throws Exception {
        try { ((com.intellij.openapi.options.Configurable) configurable).apply();
            fail("Expected validation: " + text);
        }
        catch (com.intellij.openapi.options.ConfigurationException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(text)); }
    }
    private static void awaitEnabled(java.awt.Component component) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!component.isEnabled() && System.nanoTime() < until) {
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10);
        }
        assertTrue(component.isEnabled());
    }

    public void testAllSettingsTabsFitCompactViewport() throws Exception {
        for (var configurable : List.of(new EvoSuiteApplicationConfigurable(), new EvoSuiteProjectConfigurable(getProject()))) {
            try {
                var ui = configurable.createComponent();
                assertTrue(ui.getPreferredSize().width <= com.intellij.util.ui.JBUI.scale(640));
                ui.setSize(com.intellij.util.ui.JBUI.size(620, 520));
                var tabs = findTabs(ui);
                for (int index = 0; index < tabs.getTabCount(); index++) {
                    tabs.setSelectedIndex(index);
                    layoutTree(ui);
                    var scroll = (javax.swing.JScrollPane) tabs.getSelectedComponent();
                    assertFieldHintsFit(scroll.getViewport().getView());
                    assertFalse(tabs.getTitleAt(index), scroll.getHorizontalScrollBar().isVisible());
                    var form = scroll.getViewport().getView();
                    for (var child : ((java.awt.Container) form).getComponents())
                        assertTrue(child.toString(), child.getX() + child.getWidth() <= form.getWidth());
                    var image = new java.awt.image.BufferedImage(ui.getWidth(), ui.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics();
                    try { ui.printAll(graphics); } finally { graphics.dispose(); }
                    var capture =
                            java.nio.file.Path.of(
                                    System.getProperty("java.io.tmpdir"),
                                    "evosuite-settings-"
                                            + (configurable
                                                            instanceof
                                                            EvoSuiteApplicationConfigurable
                                                    ? "application"
                                                    : "project")
                                            + "-"
                                            + index
                                            + ".png");
                    javax.imageio.ImageIO.write(image, "png", capture.toFile());
                }
            } finally { configurable.disposeUIResources(); }
        }
    }

    private static void assertFieldHintsFit(java.awt.Component component) {
        if (component instanceof javax.swing.JTextArea hint
                && hint.getName() != null
                && hint.getName().endsWith(".default")) {
            var parent = hint.getParent();
            assertTrue(hint.getName(), hint.getY() >= 0);
            assertTrue(hint.getName(), hint.getHeight() > 0);
            assertTrue(hint.getName(), hint.getY() + hint.getHeight() <= parent.getHeight());
            for (var child : parent.getComponents())
                if (child instanceof javax.swing.JTextField)
                    assertTrue(
                            hint.getName() + " overlaps its field",
                            hint.getY() >= child.getY() + child.getHeight());
        }
        if (component instanceof javax.swing.JCheckBox box
                && box.getName() != null
                && GenerationOptions.DEFAULT_CRITERIA.contains(box.getName()))
            assertTrue(
                    box.getText() + " must not be truncated",
                    box.getWidth() >= box.getPreferredSize().width);
        if (component instanceof java.awt.Container parent)
            for (var child : parent.getComponents()) assertFieldHintsFit(child);
    }

    private static void layoutTree(java.awt.Component component) {
        if (component instanceof java.awt.Container parent) {
            parent.doLayout();
            for (var child : parent.getComponents()) layoutTree(child);
        }
    }

    private static javax.swing.JTabbedPane findTabs(java.awt.Component component) {
        if (component instanceof javax.swing.JTabbedPane tabs) return tabs;
        if (component instanceof java.awt.Container parent)
            for (var child : parent.getComponents()) {
                var tabs = findTabs(child);
                if (tabs != null) return tabs;
            }
        return null;
    }

    private static javax.swing.JCheckBox findCheckbox(java.awt.Component component, String label) {
        if (component instanceof javax.swing.JCheckBox box && (label.equals(box.getText()) || label.equals(box.getName())))
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

    public void testEmptyExplicitAutomaticOverrideIsRejected() throws Exception {
        var configurable = new EvoSuiteProjectConfigurable(getProject());
        var state = EvoSuiteProjectSettings.getInstance(getProject()).getState();
        boolean previous = state.useProjectSettings;
        state.useProjectSettings = true;
        try {
            var ui = configurable.createComponent();
            ((javax.swing.JCheckBox) findField(ui, "extraArguments.automatic")).doClick();
            ((javax.swing.JTextField) findField(ui, "extraArguments")).setText("");
            try {
                ((com.intellij.openapi.options.Configurable) configurable).apply();
                fail("An explicit empty override must not silently revert to Automatic");
            } catch (com.intellij.openapi.options.ConfigurationException expected) {
                assertTrue(expected.getMessage().contains("automatic option"));
            }
        } finally {
            configurable.disposeUIResources();
            state.useProjectSettings = previous;
        }
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
