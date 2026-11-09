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
package org.evosuite.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.JBUI
import org.evosuite.ide.SettingKeys
import java.awt.*
import javax.swing.*

class EvoSuiteApplicationConfigurable : EvoSuiteConfigurable(null)
class EvoSuiteProjectConfigurable(project: Project) : EvoSuiteConfigurable(project)

open class EvoSuiteConfigurable(private val project: Project?) : Configurable {
    private var component: JPanel? = null
    private val fields = linkedMapOf<String, JComponent>()
    private val projectSwitch = JCheckBox("Use project-specific settings (.idea/evosuite.xml)")
    private val delegate = JCheckBox("Run whole-class requests through the Maven plugin")
    private val key = JBPasswordField().apply { name = "llmApiKey"; accessibleContext.accessibleName = "Cloud API key" }
    private val pomNote = note("", 3)
    private val keyStatus = note("Search only and local Ollama do not need a cloud API key. Load the saved key to edit or remove it.", 3).apply { name = "apiKeyStatus" }
    private var credentialsPanel: JPanel? = null
    private var credentialExpander: JToggleButton? = null
    private val loadKey = JButton("Load stored API key").apply { name = "loadApiKey"; addActionListener { requestKeyLoad() } }
    private val scopeNote = note("", 3)
    private val copyDefaults = JButton("Copy IDE defaults").apply { name = "copyIdeDefaults" }
    private var projectDraft: Map<String, String> = emptyMap()
    private val inherited get() = project != null && !projectSwitch.isSelected
    private var loadingEditors = false
    private val restoreDefaults = JButton("Restore EvoSuite defaults").apply {
        name = "restoreDefaults"
        addActionListener { loadEditors(emptyMap()); delegate.isSelected = false; enableFields() }
    }
    private var keyLoadInFlight = false
    private var tabs: JTabbedPane? = null
    private var selectedTab = 0
    private var javaHomeChooser: JButton? = null
    private class CredentialDraft {
        var original = ""
        var text = ""
        var ready = false
        var touched = false
    }

    private val credentialDrafts = linkedMapOf<String, CredentialDraft>()
    private var credentialProvider = "OPENAI"
    private val credential get() = credentialDrafts.getOrPut(credentialProvider, ::CredentialDraft)
    private var lastLlmProvider = ""
    private var keyRequestGeneration = 0
    private val credentialChoice = ComboBox(arrayOf("OPENAI", "ANTHROPIC")).apply { name = "credentialProvider"; accessibleContext.accessibleName = "Provider for stored API key" }
    private var updatingKeyField = false
    private var keyEditRevision = 0

    init {
        copyDefaults.addActionListener {
            loadEditors(EvoSuiteAppSettings.getInstance().state!!.values)
            enableFields()
        }
        projectSwitch.addActionListener {
            if (inherited) projectDraft = editorValues()
            loadEditors(if (inherited) EvoSuiteAppSettings.getInstance().state!!.values else projectDraft)
            enableFields()
        }
        credentialChoice.addActionListener {
            switchCredential(credentialChoice.selectedItem as String)
            enableFields()
        }
        key.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun edited() {
                if (updatingKeyField) return
                credential.touched = true
                keyEditRevision++
            }

            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = edited()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = edited()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = Unit
        })
    }

    override fun getDisplayName() = if (project == null) "EvoSuite" else "EvoSuite (Project)"
    override fun createComponent(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = JBUI.size(620, 520)
            minimumSize = JBUI.size(400, 300)
        }
        component = root
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        if (project != null) {
            EvoSuiteProjectSettings.getInstance(project).migrate(project)
            top.add(projectSwitch)
            top.add(scopeNote)
            val scopeActions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            scopeActions.add(JButton("Edit IDE defaults…").apply {
                addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, EvoSuiteApplicationConfigurable::class.java) }
            })
            scopeActions.add(copyDefaults)
            scopeActions.add(restoreDefaults)
            top.add(scopeActions)
            top.add(JButton("Effective configuration / check project setup…").apply {
                addActionListener { org.evosuite.intellij.ui.ConfigurationDialog.show(project) }
            })
        } else {
            top.add(JLabel("IDE defaults. Projects can opt into their own settings."))
            top.add(restoreDefaults)
        }
        top.add(note("Maven overrides can take precedence; see Advanced.", 1))
        top.components.filterIsInstance<JComponent>().forEach { it.alignmentX = Component.LEFT_ALIGNMENT }
        root.add(top, BorderLayout.NORTH)
        val tabs = JTabbedPane().also { this.tabs = it }
        listOf("EvoSuite" to SettingKeys.MAIN, "Large Language Model" to SettingKeys.LLM, "Advanced" to SettingKeys.ADVANCED).forEach { (title, keys) ->
            val panel = SettingsPanel()
            var row = 0
            keys.forEach { name ->
                val group = when (name) {
                    "searchBudget" -> "Search"
                    "assertions" -> "Test quality"
                    "testFormat" -> "Generated tests"
                    "javaHome" -> "Engine runtime"
                    "usePomSettings" -> "Project setup"
                    "llmContextMode" -> "Advanced model controls"
                    else -> null
                }
                if (group != null) panel.add(JLabel(group).apply { font = font.deriveFont(Font.BOLD) }, constraints(0, row++, 1.0, span = 2))
                val default = SettingKeys.defaultValue(name)
                val choices = choices(name)
                val editor: JComponent = if (name == "criterion") CriteriaEditor() else if (default == "true" || default == "false") JCheckBox() else if (choices != null) ComboBox(arrayOf("", *choices)).apply {
                    renderer = object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                            return super.getListCellRendererComponent(list, value, index, selected, focus).also {
                                text = if (value == "") defaultChoice(name) else choiceLabel(name, value?.toString().orEmpty())
                            }
                        }
                    }
                } else SettingTextField(defaultText(name), automaticLabel(name))
                if (editor is JTextField) editor.toolTipText = "Default: ${defaultDescription(name)}"
                editor.name = name
                fields[name] = editor
                val label = label(name)
                editor.accessibleContext.accessibleName = label
                if (editor !is JCheckBox)
                    panel.add(JLabel(label).apply { labelFor = editor; this.name = "$name.label" }, constraints(0, row, 0.0))
                val cell = if (editor is SettingTextField) JPanel(BorderLayout(0, 2)).apply {
                    val input = JPanel(BorderLayout(4, 0)).apply {
                        add(editor, BorderLayout.CENTER)
                        if (name == "javaHome") add(JButton("Choose…").apply {
                            javaHomeChooser = this; this.name = "chooseJavaHome"
                            accessibleContext.accessibleName = "Choose Java JDK directory"
                            addActionListener {
                                val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor()
                                com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null) { file ->
                                    editor.automatic?.isSelected = false
                                    editor.isEditable = true; editor.text = file.path
                                }
                            }
                        }, BorderLayout.EAST)
                    }
                    add(input, BorderLayout.NORTH)
                    editor.automatic?.let {
                        it.name = "$name.automatic"
                        add(it, BorderLayout.CENTER)
                    }
                    add(note("Default: ${defaultDescription(name)}").apply { this.name = "$name.default" }, BorderLayout.SOUTH)
                } else editor
                if (editor is JCheckBox) {
                    editor.text = label
                    panel.add(editor, constraints(0, row++, 1.0, span = 2))
                } else panel.add(cell, constraints(1, row++, 1.0))
                when (editor) {
                    is JCheckBox -> editor.addActionListener { enableFields() }
                    is JComboBox<*> -> editor.addActionListener { enableFields() }
                }
                if (name == "minimizeValues") panel.add(note("Simplify constants while preserving coverage. Off by default; requires test minimization above.", 2).apply { this.name = "minimizeValues.help" }, constraints(0, row++, 1.0, span = 2))
                if (name == "searchBudget") panel.add(note("Search time excludes building, minimization and test validation.", 2), constraints(0, row++, 1.0, span = 2))
                if (name == "noRuntimeDependency") panel.add(note("JUnit is still required. Runtime isolation helpers are omitted; existing dependencies remain for older tests.", 2), constraints(0, row++, 1.0, span = 2))
                if (name == "llmProvider") row = addCredentials(panel, row)
                if (name == "llmBaseUrl") {
                    panel.add(JButton("Test connection (metadata only)…").apply {
                        this.name = "testConnection"
                        addActionListener {
                            try { if (!inherited) validateEditors() }
                            catch (ex: ConfigurationException) {
                                com.intellij.openapi.ui.Messages.showErrorDialog(project, ex.localizedMessage.orEmpty(), "EvoSuite Settings")
                                return@addActionListener
                            }
                            val draft = SettingKeys.resolve(editorValues())
                            val provider = draft.effectiveLlmProvider()
                            val providerKey = if (provider == credentialProvider) String(key.password)
                                else credentialDrafts[provider]?.text.orEmpty()
                            org.evosuite.intellij.run.LlmConnectionCheck.show(project, draft, providerKey)
                        }
                    }, constraints(0, row++, 1.0, span = 2))
                }
            }
            if (title == "Large Language Model") {
                panel.add(note("Search only keeps LLM features off, including overrides from Maven or additional arguments. Provider defaults and EVOSUITE_LLM_MODEL are supported.", 3), constraints(0, row++, 1.0, span = 2))

            }
            if (title == "Advanced") {
                if (project != null) {
                    panel.add(delegate, constraints(0, row++, 1.0, span = 2))
                    panel.add(note("Maven can generate whole-class suites for new destinations. IntelliJ reviews any conflicts before placing them."), constraints(0, row++, 1.0, span = 2))
                    panel.add(JScrollPane(pomNote), constraints(0, row++, 1.0, span = 2))
                }
                panel.add(note("pom.xml settings take precedence when enabled. The run console lists each override."), constraints(0, row++, 1.0, span = 2))
                panel.add(note("Configure Gradle persists generated test roots and JUnit setup in the build script."), constraints(0, row++, 1.0, span = 2))
            }
            panel.add(JPanel(), constraints(0, row, 1.0, span = 2).apply { weighty = 1.0 })
            tabs.addTab(title, JScrollPane(panel))
        }
        root.add(tabs)
        tabs.selectedIndex = selectedTab
        reset()
        return root
    }
    private fun values(): Map<String, String> = if (project == null) EvoSuiteAppSettings.getInstance().state!!.values else EvoSuiteProjectSettings.getInstance(project).state!!.values
    private fun current(): Map<String, String> = if (inherited) normalized(projectDraft) else editorValues()
    private fun normalized(source: Map<String, String>): Map<String, String> = source.toMutableMap().apply {
        fields.forEach { (name, field) -> if (field is JCheckBox && !containsKey(name)) put(name, SettingKeys.defaultValue(name)) }
    }.filterValues { it.isNotEmpty() }
    private fun editorValues(): Map<String, String> = fields.mapValues { (_, field) -> when (field) {
        is CriteriaEditor -> field.value()
        is JCheckBox -> field.isSelected.toString()
        is JComboBox<*> -> field.selectedItem?.toString() ?: ""
        is SettingTextField -> field.value()
        else -> ""
    } }.filterValues { it.isNotEmpty() }
    @Suppress("UNCHECKED_CAST")
    private fun loadEditors(values: Map<String, String>) {
        loadingEditors = true
        try { fields.forEach { (name, field) -> val value = values[name].orEmpty(); when (field) {
            is CriteriaEditor -> field.load(value)
            is JCheckBox -> field.isSelected = (value.ifEmpty { SettingKeys.defaultValue(name) }).toBoolean()
            is JComboBox<*> -> {
                val combo = field as JComboBox<String>
                combo.model = DefaultComboBoxModel(arrayOf("", *choices(name).orEmpty()))
                if ((0 until combo.itemCount).none { combo.getItemAt(it) == value }) combo.addItem(value)
                combo.selectedItem = value
            }
            is SettingTextField -> field.load(value)
        } } } finally { loadingEditors = false }
    }
    override fun reset() {
        projectDraft = values().toMap()
        if (project != null) {
            projectSwitch.isSelected = EvoSuiteProjectSettings.getInstance(project).state!!.useProjectSettings
            delegate.isSelected = EvoSuiteProjectSettings.getInstance(project).state!!.delegateToMaven
        }
        loadEditors(if (inherited) EvoSuiteAppSettings.getInstance().state!!.values else projectDraft)
        if (project == null) {
            credentialDrafts.values.forEach {
                it.text = it.original
                it.touched = false
            }
            showKey(credential.text)
        } else {
            val view = component
            pomNote.text = "Reading imported Maven overrides…"
            ApplicationManager.getApplication().executeOnPooledThread {
                val note = runCatching {
                    com.intellij.openapi.application.ReadAction.compute<String, RuntimeException> {
                        com.intellij.openapi.module.ModuleManager.getInstance(project!!).modules.mapNotNull { module ->
                            val pom = org.evosuite.intellij.maven.MavenBridge.settings(module) ?: return@mapNotNull null
                            val options = org.evosuite.ide.GenerationOptions()
                            val lines = pom.applyTo(options, org.evosuite.intellij.setup.ProjectSetupService.baseDir(module).toFile())
                            module.name + ": " + lines.joinToString("; ", transform = org.evosuite.intellij.run.GenerationService::mask)
                        }.joinToString("\n").ifEmpty { "No imported EvoSuite Maven-plugin overrides." }
                    }
                }.getOrElse { "Maven overrides are unavailable while the project model is changing." }
                ApplicationManager.getApplication().invokeLater { if (view != null && component === view) pomNote.text = note }
            }
        }
        enableFields()
    }
    override fun isModified(): Boolean {
        if (component == null) return false
        if (current() != normalized(values()) || credentialDrafts.values.any { it.touched }) return true
        val state = project?.let { EvoSuiteProjectSettings.getInstance(it).state } ?: return false
        return projectSwitch.isSelected != state.useProjectSettings || delegate.isSelected != state.delegateToMaven
    }
    override fun apply() {
        val updated = current()
        if (!inherited) {
            if ((fields["criterion"] as? CriteriaEditor)?.hasSelection() == false)
                throw ConfigurationException("Select at least one coverage criterion.")
            validateEditors()
            try { SettingKeys.resolve(updated) } catch (e: IllegalArgumentException) { throw ConfigurationException(e.message ?: "Invalid settings") }
        }
        if (project == null) {
            credential.text = String(key.password)
            for ((provider, draft) in credentialDrafts) {
                if (!draft.touched) continue
                try {
                    storeApiKey(provider, draft.text)
                } catch (e: Exception) {
                    throw ConfigurationException("Could not save the $provider API key. Retry Apply to save it.")
                }
                draft.original = draft.text
                draft.touched = false
            }
        }
        if (project == null) {
            EvoSuiteAppSettings.getInstance().state!!.values = java.util.TreeMap(updated)
        } else {
            EvoSuiteProjectSettings.getInstance(project).state!!.apply {
                values = java.util.TreeMap(updated)
                useProjectSettings = projectSwitch.isSelected
                delegateToMaven = delegate.isSelected
            }
        }
        projectDraft = values().toMap()
        loadEditors(if (inherited) EvoSuiteAppSettings.getInstance().state!!.values else projectDraft)
        enableFields()
    }
    private fun addCredentials(parent: JPanel, firstRow: Int): Int {
        val panel = JPanel(GridBagLayout())
        var row = 0
        val expand = JToggleButton("Cloud API keys…").apply { isSelected = project != null; name = "cloudCredentials" }
        credentialExpander = expand; credentialsPanel = panel
        panel.isVisible = expand.isSelected
        expand.addActionListener { panel.isVisible = expand.isSelected; parent.revalidate() }
        parent.add(expand, constraints(0, firstRow, 1.0, span = 2))
        parent.add(panel, constraints(0, firstRow + 1, 1.0, span = 2))
        if (project == null) {
            panel.add(JLabel("Stored key provider").apply { labelFor = credentialChoice }, constraints(0, row, 0.0))
            panel.add(credentialChoice, constraints(1, row++, 1.0))
            panel.add(JLabel("Cloud API key").apply { labelFor = key }, constraints(0, row, 0.0))
            panel.add(key, constraints(1, row++, 1.0))
            panel.add(loadKey, constraints(1, row++, 1.0))
            panel.add(JButton("Import legacy shared key for this provider").apply {
                name = "importLegacyApiKey"
                addActionListener { importLegacyKey() }
            }, constraints(1, row++, 1.0))
            panel.add(keyStatus, constraints(0, row++, 1.0, span = 2))
        } else {
            panel.add(JButton("Manage shared API key…").apply {
                name = "manageApiKey"
                addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, EvoSuiteApplicationConfigurable::class.java) { it.selectProviderCredentials(effectiveChoice("llmProvider")) } }
            }, constraints(1, row++, 1.0))
            panel.add(note("Cloud API keys are shared across projects and stored in the IDE's secure storage. EVOSUITE_LLM_API_KEY is also supported.", 3), constraints(0, row++, 1.0, span = 2))
        }
        return firstRow + 2
    }
    fun selectProviderCredentials(provider: String) {
        selectedTab = 1
        tabs?.selectedIndex = selectedTab
        if (provider == "OPENAI" || provider == "ANTHROPIC") switchCredential(provider)
        credentialExpander?.isSelected = true; credentialsPanel?.isVisible = true
        component?.revalidate()
    }
    protected open fun loadLegacyApiKey(): String = LlmApiKeyStore.get()
    protected open fun loadApiKey(provider: String): String? = LlmApiKeyStore.get(provider)
    protected open fun mayAutomaticallyLoadApiKey() = !ApplicationManager.getApplication().isHeadlessEnvironment && !ApplicationManager.getApplication().isUnitTestMode
    private fun requestKeyLoad() {
        if (project != null || credential.ready || keyLoadInFlight || component == null) return
        keyStatus.text = "Reading API key from secure storage…"
        readKey(::loadApiKey) { loaded ->
            val draft = credential
            draft.original = loaded.getOrNull().orEmpty()
            draft.ready = true
            if (!draft.touched) {
                draft.text = draft.original
                showKey(draft.text)
            }
            keyStatus.text = when {
                loaded.isFailure -> "Secure storage could not be read. Enter a replacement key or reopen settings to retry; the saved key is kept unless you edit this field."
                draft.original.isBlank() -> "Enter a cloud API key and Apply, or set EVOSUITE_LLM_API_KEY. Search only and local Ollama do not need a key."
                else -> "Clear the field and Apply to remove the saved key. EVOSUITE_LLM_API_KEY is also supported."
            }
        }
    }

    private fun importLegacyKey() {
        val revision = keyEditRevision
        readKey({ loadLegacyApiKey() }) { loaded ->
            if (revision != keyEditRevision) return@readKey
            val legacy = loaded.getOrNull()
            if (!legacy.isNullOrBlank()) {
                credential.text = legacy
                credential.ready = true
                credential.touched = true
                showKey(legacy)
                keyStatus.text = "Apply to store this key for $credentialProvider. Other providers keep their own keys."
            } else {
                keyStatus.text = if (loaded.isSuccess) "No legacy shared key was found. The current provider key is kept."
                    else "The legacy shared key could not be read. The current provider key is kept."
            }
        }
    }

    private fun readKey(read: (String) -> String?, apply: (Result<String?>) -> Unit) {
        val provider = credentialProvider
        val request = ++keyRequestGeneration
        keyLoadInFlight = true
        loadKey.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { read(provider) }
            ApplicationManager.getApplication().invokeLater {
                if (component == null || request != keyRequestGeneration) return@invokeLater
                keyLoadInFlight = false
                apply(result)
                enableFields()
            }
        }
    }
    private fun validateEditors() {
        fields.forEach { (name, field) ->
            if (field.isEnabled && field is SettingTextField && field.customValueMissing())
                invalid(name, "Enter a value for ${label(name).lowercase()} or select its automatic option.")
        }
        val values = current()
        mapOf("searchBudget" to "seconds greater than zero", "testTimeout" to "milliseconds (zero uses the default)", "clientMemory" to "megabytes (zero uses the default)", "llmMaxCalls" to "calls (zero means unlimited)", "llmMaxTokens" to "tokens greater than zero", "llmTimeoutSeconds" to "seconds greater than zero", "llmRetryMaxAttempts" to "attempts (zero disables retries)").forEach { (name, units) ->
            val value = values[name]
            if (!value.isNullOrBlank()) {
                val number = value.toIntOrNull()
                if (number == null || number < 0 || name in listOf("searchBudget", "llmMaxTokens", "llmTimeoutSeconds") && number == 0)
                    invalid(name, "${label(name)} must be a whole number of $units.")
            }
        }
        fields.keys.forEach { name ->
            val value = values[name].orEmpty()
            choices(name)?.let { if (fields[name]?.isEnabled == true && value.isNotEmpty() && value !in it) invalid(name, "Choose a supported value for ${label(name).lowercase()}; '$value' is not supported.") }
        }
        val seed = values["randomSeed"].orEmpty()
        if (seed.isNotBlank() && seed.toLongOrNull() == null) invalid("randomSeed", "Random seed must be a signed 64-bit integer.")
        val temperature = (values["llmTemperature"] ?: SettingKeys.defaultValue("llmTemperature")).toDoubleOrNull()
            ?: invalid("llmTemperature", "Sampling temperature must be between 0 and 2.")
        if (!temperature.isFinite() || temperature !in 0.0..2.0) invalid("llmTemperature", "Sampling temperature must be between 0 and 2.")
        val criteria = values["criterion"].orEmpty().split(":").filter { it.isNotBlank() }
        if (criteria.any { it !in SettingKeys.SUPPORTED_CRITERIA }) invalid("criterion", "Choose supported coverage criteria. Unknown: " + criteria.filter { it !in SettingKeys.SUPPORTED_CRITERIA }.joinToString())
        val home = values["javaHome"].orEmpty()
        if (home.isNotBlank() && runCatching { org.evosuite.ide.JavaInstallation.at(java.nio.file.Path.of(home)).also { require(it.hasCompiler()) } }.isFailure)
            invalid("javaHome", "Choose a valid Java JDK directory containing bin/java and bin/javac.")
        val url = values["llmBaseUrl"].orEmpty()
        if (url.isNotEmpty() && fields["llmBaseUrl"]?.isEnabled == true) {
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            if (uri == null || uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null)
                invalid("llmBaseUrl", "Provider endpoint must be a full HTTP(S) URL without credentials, query or fragment.")
        }
    }
    private fun invalid(name: String, message: String): Nothing {
        tabs?.selectedIndex = when (name) { in SettingKeys.LLM -> 1; in SettingKeys.ADVANCED -> 2; else -> 0 }
        fields[name]?.let { it.scrollRectToVisible(Rectangle(0, 0, it.width, it.height)); it.requestFocusInWindow(); if (it is JTextField) it.selectAll() }
        throw ConfigurationException(message)
    }
    private fun label(name: String): String = mapOf(
        "searchBudget" to "Search budget per class (seconds)", "criterion" to "Coverage criteria",
        "assertions" to "Generate assertions", "minimize" to "Remove redundant tests and statements",
        "minimizeValues" to "Minimize values (simplify constants)",
        "noRuntimeDependency" to "Omit EvoSuite runtime dependency",
        "randomSeed" to "Random seed",
        "llmContextMode" to "Code context sent to the model",
        "llmMaxTokens" to "Maximum output tokens per call",
        "llmTimeoutSeconds" to "HTTP timeout per call (seconds)",
        "llmRetryMaxAttempts" to "Retry attempts per failed call",
        "llmTemperature" to "Sampling temperature (0–2)",
        "assertionStrategy" to "Assertion selection", "testFormat" to "JUnit version",
        "testFolder" to "Test folder (module-relative)", "testSuffix" to "Test class suffix",
        "testNaming" to "Test method names", "variableNaming" to "Variable names",
        "testComments" to "Add coverage comments to tests", "testTimeout" to "Test timeout (ms)",
        "openGeneratedTests" to "Open generated tests in the editor", "javaHome" to "Java JDK directory",
        "clientMemory" to "Client heap (MB)", "extraArguments" to "Additional arguments",
        "usePomSettings" to "Use Maven configuration overrides", "configureBuildPath" to "Configure test roots and dependencies",
        "llmProvider" to "Provider", "llmModel" to "Model", "llmBaseUrl" to "Provider endpoint",
        "llmApproach" to "Generation approach", "llmSeeding" to "LLM seed tests",
        "llmOnStagnation" to "Ask the LLM when search stops improving", "llmOperators" to "Use LLM mutation and crossover",
        "llmPostprocessing" to "Improve final tests with the LLM", "llmMaxCalls" to "LLM calls per class"
    )[name] ?: name
    private fun choiceLabel(name: String, value: String): String {
        val labels = mapOf(
            "JUNIT5" to "JUnit 5", "JUNIT4" to "JUnit 4", "MUTATION" to "Fault-detecting assertions", "ALL" to "All assertions",
            "NUMBERED" to "Numbered (test0, test1, …)", "COVERAGE" to "Based on coverage goals",
            "TYPE_BASED" to "Based on type", "HEURISTICS_BASED" to "Based on context",
            "NONE" to "No provider", "OPENAI" to "OpenAI", "ANTHROPIC" to "Anthropic", "OLLAMA" to "Ollama (local)",
            "SEARCH_ONLY" to "Search only (LLM disabled)", "SEARCH" to "Search with optional LLM assistance",
            "LLM_SINGLE_PROMPT" to "LLM only: single prompt", "LLM_ITERATIVE" to "LLM only: iterative",
            "SIGNATURE_ONLY" to "Class and method signatures", "SOURCE_CODE" to "Project source code",
            "DECOMPILED_SOURCE" to "Decompiled source", "BYTECODE_DISASSEMBLED" to "Disassembled bytecode",
            "OFF" to "Off", "MIN" to "Minimal", "STANDARD" to "Standard", "FULL" to "Full"
        )
        return labels[value] ?: if (value in choices(name).orEmpty()) value else "Unsupported: $value"
    }
    // PasswordSafe may open a modal prompt, so run it off the EDT.
    protected open fun storeApiKey(provider: String, password: String) {
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
            Runnable { runCatching { LlmApiKeyStore.set(provider, password) }.onFailure { failure.set(it) } },
            "Saving EvoSuite API key", false, project)
        failure.get()?.let { throw IllegalStateException("Secure storage failed", it) }
    }
    private fun showKey(text: String) {
        updatingKeyField = true
        try {
            key.text = text
        } finally {
            updatingKeyField = false
        }
    }

    private fun switchCredential(provider: String) {
        if (provider == credentialProvider) return
        credential.text = String(key.password)
        keyRequestGeneration++
        keyLoadInFlight = false
        credentialProvider = provider
        showKey(credential.text)
        if (credentialChoice.selectedItem != provider) credentialChoice.selectedItem = provider
        keyStatus.text = "Keys are stored separately for OpenAI and Anthropic. Load or enter the $provider key."
    }
    private fun enableFields() {
        if (loadingEditors) return
        val scope = project == null || projectSwitch.isSelected
        if (project != null) {
            scopeNote.text = if (inherited) "Using IDE defaults (shown below). Enable project settings to override them." else "Project settings use EvoSuite defaults for unset values. Copy IDE defaults to start from your IDE configuration."
            copyDefaults.isEnabled = scope
        }
        restoreDefaults.isEnabled = scope
        javaHomeChooser?.isEnabled = scope
        loadKey.isEnabled = !credential.ready && !keyLoadInFlight
        fields.keys.forEach { enableEditor(it, scope) }
        val provider = effectiveChoice("llmProvider")
        val approach = effectiveChoice("llmApproach")
        if (project == null && provider != lastLlmProvider) {
            lastLlmProvider = provider
            if (provider == "OPENAI" || provider == "ANTHROPIC") {
                switchCredential(provider); credentialExpander?.isSelected = true; credentialsPanel?.isVisible = true
            } else { credentialExpander?.isSelected = false; credentialsPanel?.isVisible = false }
        }
        val llm = scope && provider.isNotEmpty() && provider != "NONE" && approach != "SEARCH_ONLY"
        SettingKeys.LLM.filter { it != "llmProvider" && it != "llmApproach" }.forEach { enableEditor(it, llm) }
        val search = approach != "LLM_SINGLE_PROMPT" && approach != "LLM_ITERATIVE"
        listOf("llmSeeding", "llmOnStagnation", "llmOperators").forEach { fields[it]?.isEnabled = llm && search }
        key.isEnabled = project == null && credential.ready
        if (project == null && llm && (provider == "OPENAI" || provider == "ANTHROPIC")
            && mayAutomaticallyLoadApiKey()) requestKeyLoad()
        fields["minimizeValues"]?.isEnabled = scope && (fields["minimize"] as? JCheckBox)?.isSelected == true
        fields["assertionStrategy"]?.isEnabled = scope && (fields["assertions"] as? JCheckBox)?.isSelected == true
    }
    private fun enableEditor(name: String, enabled: Boolean) {
        fields[name]?.let { field ->
            setEnabled(field, enabled)
            (field as? SettingTextField)?.automatic?.isEnabled = enabled
        }
    }
    private fun defaultText(name: String): String = when (name) {
        "testTimeout" -> "3000"
        "javaHome" -> "Module JDK"
        "llmModel" -> "Provider / EVOSUITE_LLM_MODEL"
        "llmBaseUrl" -> "Provider endpoint"
        "randomSeed" -> "Fresh random seed"
        "extraArguments" -> "None"
        else -> SettingKeys.defaultValue(name)
    }
    private fun automaticLabel(name: String): String? = when (name) {
        "javaHome" -> "Use module JDK"
        "llmModel" -> "Use provider / environment default"
        "llmBaseUrl" -> "Use provider endpoint"
        "randomSeed" -> "Choose a fresh seed for each run"
        "extraArguments" -> "No additional arguments"
        else -> null
    }
    private fun effectiveChoice(name: String) = (fields[name] as? JComboBox<*>)?.selectedItem?.toString().orEmpty().ifEmpty { SettingKeys.defaultValue(name) }
    private fun defaultChoice(name: String) = if (name == "testFormat") "Automatic (JUnit 4 or JUnit 5)" else "Default (${choiceLabel(name, SettingKeys.defaultValue(name))})"
    private fun defaultDescription(name: String): String = when (name) {
        "searchBudget" -> "${SettingKeys.defaultValue(name)} seconds per class"
        "testTimeout" -> "3000 ms (0 uses the engine default)"
        "clientMemory" -> "engine heap limit (0 uses the engine default)"
        "javaHome" -> "module JDK"
        "llmModel" -> "EVOSUITE_LLM_MODEL or provider default"
        "llmBaseUrl" -> "provider endpoint; Ollama: http://localhost:11434"
        "llmMaxCalls" -> "unlimited (0)"
        "randomSeed" -> "fresh engine seed (use a fixed signed 64-bit integer to repeat a run)"
        "extraArguments" -> "none"
        else -> SettingKeys.defaultValue(name).ifEmpty { "EvoSuite default" }
    }
    override fun disposeUIResources() {
        component = null
        fields.clear()
        tabs = null
        credentialsPanel = null
        credentialExpander = null
        javaHomeChooser = null
        keyRequestGeneration++
        lastLlmProvider = ""
        keyLoadInFlight = false
        showKey("")
        credentialDrafts.clear()
    }
    private fun constraints(x: Int, y: Int, weight: Double, span: Int = 1) = GridBagConstraints().apply {
        gridx = x
        gridy = y
        gridwidth = span
        weightx = weight
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.NORTHWEST
        insets = Insets(4, 8, 4, 8)
    }
    private fun setEnabled(component: JComponent, enabled: Boolean) {
        component.isEnabled = enabled
        component.components.filterIsInstance<JComponent>().forEach { setEnabled(it, enabled) }
    }
    private fun choices(name: String): Array<String>? = when(name) {
        "testFormat" -> arrayOf("JUNIT5", "JUNIT4")
        "assertionStrategy" -> arrayOf("MUTATION", "ALL")
        "testNaming" -> arrayOf("NUMBERED", "COVERAGE")
        "variableNaming" -> arrayOf("TYPE_BASED", "HEURISTICS_BASED")
        "llmProvider" -> arrayOf("NONE", "OPENAI", "ANTHROPIC", "OLLAMA")
        "llmApproach" -> arrayOf("SEARCH_ONLY", "SEARCH", "LLM_SINGLE_PROMPT", "LLM_ITERATIVE")
        "llmContextMode" -> arrayOf("SIGNATURE_ONLY", "SOURCE_CODE", "DECOMPILED_SOURCE", "BYTECODE_DISASSEMBLED")
        "llmSeeding" -> arrayOf("OFF", "MIN", "STANDARD", "FULL")
        else -> null
    }
}

private class CriteriaEditor : JPanel(BorderLayout()) {
    private val known = org.evosuite.ide.GenerationOptions.DEFAULT_CRITERIA
    private val boxes = known.associateWith { key -> JCheckBox(mapOf(
        "LINE" to "Lines", "BRANCH" to "Branches", "EXCEPTION" to "Exceptions",
        "WEAKMUTATION" to "Weak mutation", "OUTPUT" to "Return values", "METHOD" to "Methods",
        "METHODNOEXCEPTION" to "Normal methods", "CBRANCH" to "Context branches"
    )[key] ?: key, true).apply { name = key; toolTipText = "EvoSuite criterion: $key" } }
    private val other = JTextField()
    private var stored = ""
    private var original: List<String> = known
    init {
        val grid = JPanel(GridLayout(0, 2))
        boxes.values.forEach { grid.add(it) }
        add(grid, BorderLayout.CENTER)
        val custom = JPanel(BorderLayout(4, 2)); custom.add(JLabel("Additional criteria (optional, colon separated)"), BorderLayout.NORTH); custom.add(other, BorderLayout.CENTER)
        custom.add(JButton("Choose…").apply {
            accessibleContext.accessibleName = "Choose additional coverage criteria"
            addActionListener {
                val chosen = other.text.split(":").toSet()
                val options = SettingKeys.SUPPORTED_CRITERIA.filter { it !in known }.associateWith { JCheckBox(it, it in chosen) }
                val panel = JPanel(GridLayout(0, 2, 6, 2)); options.values.forEach { panel.add(it) }
                val dialog = object : com.intellij.openapi.ui.DialogWrapper(this@CriteriaEditor, true) {
                    init { title = "Additional coverage criteria"; init() }
                    override fun createCenterPanel(): JComponent = panel
                }
                if (dialog.showAndGet())
                    other.text = options.filterValues { it.isSelected }.keys.joinToString(":")
            }
        }, BorderLayout.EAST)
        add(custom, BorderLayout.SOUTH)
    }
    fun load(value: String) {
        stored = value
        val selected = if (value.isBlank()) known else value.split(":").map { it.trim() }.filter { it.isNotEmpty() }
        original = selected
        boxes.forEach { (name, box) -> box.isSelected = name in selected }
        other.text = selected.filter { it !in known }.joinToString(":")
    }
    fun hasSelection() = boxes.values.any { it.isSelected } || other.text.split(":").any { it.isNotBlank() }
    fun value(): String {
        val values = boxes.filterValues { it.isSelected }.keys.toList() + other.text.split(":").map { it.trim() }.filter { it.isNotEmpty() }
        return if (values.toSet() == original.toSet()) stored else if (values == known) "" else values.distinct().joinToString(":")
    }
}

private class SettingsPanel : JPanel(GridBagLayout()), Scrollable {
    override fun getPreferredScrollableViewportSize() = JBUI.size(600, 440)
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
}

private fun note(text: String, rows: Int = 2) = JTextArea(text, rows, 22).apply {
    isEditable = false
    isFocusable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
    font = UIManager.getFont("Label.font")
    foreground = UIManager.getColor("Label.disabledForeground")
    minimumSize = Dimension(0, preferredSize.height)
    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    border = null
}

/** A displayed default is not an explicit override until edited. */
private class SettingTextField(private val defaultText: String, automaticLabel: String?) : JTextField(22) {
    val automatic = automaticLabel?.let { JCheckBox(it) }
    private var stored = ""
    private var displayed = ""
    private var custom = ""
    init {
        automatic?.addActionListener {
            if (automatic.isSelected) {
                custom = text
                text = defaultText
            } else text = custom
            isEditable = !automatic.isSelected
        }
    }
    fun load(value: String) {
        stored = value
        custom = value
        automatic?.isSelected = value.isBlank()
        text = if (automatic?.isSelected == true || value.isEmpty()) defaultText else value
        displayed = text
        isEditable = automatic?.isSelected != true
    }
    fun customValueMissing() = automatic != null && !automatic.isSelected && text.isBlank()
    fun value(): String {
        if (automatic?.isSelected == true) return ""
        val value = text.trim()
        return if (value == displayed) stored else value
    }
}
