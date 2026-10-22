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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.JBUI
import org.evosuite.ide.SettingKeys
import java.awt.*
import javax.swing.*

class EvoSuiteApplicationConfigurable : EvoSuiteConfigurable(null)
class EvoSuiteProjectConfigurable(project: Project) : EvoSuiteConfigurable(project)

/** Display defaults as values while preserving unset settings and automatic resolution. */
open class EvoSuiteConfigurable(private val project: Project?) : Configurable {
    private var component: JPanel? = null
    private val fields = linkedMapOf<String, JComponent>()
    private val projectSwitch = JCheckBox("Use project-specific settings (.idea/evosuite.xml)")
    private val delegate = JCheckBox("Run whole-class requests through the Maven plugin")
    private val key = JBPasswordField().apply { name = "llmApiKey" }
    private val pomNote = note("", 3)
    private val keyStatus = note("Loading API key from secure storage…")
    private var originalKey: String? = null
    private var keyReady = false
    private var keyTouched = false
    private var loadingKey = false
    private var generation = 0
    override fun getDisplayName() = if (project == null) "EvoSuite" else "EvoSuite (Project)"
    override fun createComponent(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = JBUI.size(620, 520)
            minimumSize = JBUI.size(400, 300)
        }
        component = root
        keyReady = false
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        if (project != null) {
            EvoSuiteProjectSettings.getInstance(project).migrate(project)
            top.add(projectSwitch)
            top.add(note("When enabled, unset project fields use EvoSuite defaults. Commit .idea/evosuite.xml to share."))
            top.add(delegate)
            top.add(JScrollPane(pomNote).apply { maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(76)) })
            top.add(note("Maven delegation uses Maven scheduling/export. Add/Compare, methods and extension use the bundled engine."))
        } else top.add(JLabel("IDE defaults. Projects can opt into their own settings."))
        root.add(top, BorderLayout.NORTH)
        val tabs = JTabbedPane()
        listOf("EvoSuite" to SettingKeys.MAIN, "Large Language Model" to SettingKeys.LLM, "Advanced" to SettingKeys.ADVANCED).forEach { (title, keys) ->
            val panel = SettingsPanel()
            var row = 0
            keys.forEach { name ->
                val default = SettingKeys.defaultValue(name)
                val choices = choices(name)
                val editor: JComponent = if (name == "criterion") CriteriaEditor() else if (default == "true" || default == "false") JCheckBox() else if (choices != null) ComboBox(arrayOf("", *choices)).apply {
                    renderer = object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                            return super.getListCellRendererComponent(list, value, index, selected, focus).also {
                                text = if (value == "") defaultChoice(name) else value?.toString().orEmpty()
                            }
                        }
                    }
                } else SettingTextField(defaultText(name), automaticLabel(name))
                if (editor is JTextField) editor.toolTipText = "Default: ${defaultDescription(name)}"
                editor.name = name
                fields[name] = editor
                val label = name.replace(Regex("([A-Z])"), " $1").replaceFirstChar { it.uppercase() }.replace("Llm", "LLM")
                panel.add(JLabel(label).apply { labelFor = editor }, constraints(0, row, 0.0))
                val cell = if (editor is JTextField) JPanel(BorderLayout(0, 2)).apply {
                    add(editor, BorderLayout.NORTH)
                    (editor as? SettingTextField)?.automatic?.let {
                        it.name = "$name.automatic"
                        add(it, BorderLayout.CENTER)
                    }
                    add(note("Default: ${defaultDescription(name)}").apply { this.name = "$name.default" }, BorderLayout.SOUTH)
                } else editor
                panel.add(cell, constraints(1, row++, 1.0))
                if (editor is JCheckBox) editor.addActionListener { enableFields() }
                if (editor is JComboBox<*>) editor.addActionListener { enableFields() }
            }
            if (title == "Large Language Model" && project == null) {
                panel.add(JLabel("API key (secure storage)"), constraints(0, row, 0.0))
                panel.add(key, constraints(1, row++, 1.0))
                panel.add(keyStatus, constraints(1, row++, 1.0))
                key.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                    override fun insertUpdate(e: javax.swing.event.DocumentEvent) { if (!loadingKey) keyTouched = true }
                    override fun removeUpdate(e: javax.swing.event.DocumentEvent) { if (!loadingKey) keyTouched = true }
                    override fun changedUpdate(e: javax.swing.event.DocumentEvent) {}
                })
            }
            if (title == "Advanced") {
                panel.add(note("pom.xml settings take precedence when enabled. The run console lists each override."), constraints(0, row++, 1.0).apply { gridwidth = 2 })
                panel.add(note("Gradle: sourceSets.test.java.srcDir(\"src/test/evosuite\")"), constraints(0, row++, 1.0).apply { gridwidth = 2 })
            }
            panel.add(JPanel(), constraints(0, row, 1.0).apply { weighty = 1.0; gridwidth = 2 })
            tabs.addTab(title, JScrollPane(panel))
        }
        root.add(tabs)
        projectSwitch.addActionListener { enableFields() }
        reset()
        if (project == null && !ApplicationManager.getApplication().isHeadlessEnvironment && !ApplicationManager.getApplication().isUnitTestMode) {
            val token = ++generation
            key.isEnabled = false
            ApplicationManager.getApplication().executeOnPooledThread {
                val loaded = runCatching { LlmApiKeyStore.get() }
                ApplicationManager.getApplication().invokeLater {
                    if (component == null || token != generation) return@invokeLater
                    loadingKey = true
                    originalKey = loaded.getOrNull()
                    key.text = originalKey ?: ""
                    loadingKey = false
                    keyTouched = false
                    keyReady = true
                    enableFields()
                    keyStatus.text = if (loaded.isSuccess) "Clear this field to remove the key. EVOSUITE_LLM_API_KEY is also supported." else "Secure storage could not be read. The saved key is kept unless you enter a new one."
                }
            }
        }
        return root
    }
    private fun values(): Map<String, String> = if (project == null) EvoSuiteAppSettings.getInstance().state!!.values else EvoSuiteProjectSettings.getInstance(project).state!!.values
    private fun current(): Map<String, String> = fields.mapValues { (_, field) -> when (field) {
        is CriteriaEditor -> field.value()
        is JCheckBox -> field.isSelected.toString()
        is JComboBox<*> -> field.selectedItem?.toString() ?: ""
        is SettingTextField -> field.value()
        is JTextField -> field.text.trim()
        else -> ""
    } }.filterValues { it.isNotEmpty() }
    override fun reset() {
        val values = values()
        fields.forEach { (name, field) -> val value = values[name] ?: ""; when (field) {
            is CriteriaEditor -> field.load(value)
            is JCheckBox -> field.isSelected = (value.ifEmpty { SettingKeys.defaultValue(name) }).toBoolean()
            is JComboBox<*> -> field.selectedItem = value
            is SettingTextField -> field.load(value)
            is JTextField -> field.text = value
        } }
        if (project != null) {
            projectSwitch.isSelected = EvoSuiteProjectSettings.getInstance(project).state!!.useProjectSettings
            delegate.isSelected = EvoSuiteProjectSettings.getInstance(project).state!!.delegateToMaven
        }
        if (project == null) {
            loadingKey = true
            key.text = originalKey ?: ""
            loadingKey = false
            keyTouched = false
        } else {
            val token = generation
            pomNote.text = "Reading imported Maven overrides…"
            ApplicationManager.getApplication().executeOnPooledThread {
                val note = runCatching {
                    com.intellij.openapi.application.ReadAction.compute<String, RuntimeException> {
                        com.intellij.openapi.module.ModuleManager.getInstance(project!!).modules.mapNotNull { module ->
                            val pom = org.evosuite.intellij.maven.MavenBridge.settings(module) ?: return@mapNotNull null
                            val options = org.evosuite.ide.GenerationOptions()
                            val lines = pom.applyTo(options, org.evosuite.intellij.setup.ProjectSetupService.baseDir(module).toFile())
                            module.name + ": " + lines.joinToString("; ").replace(Regex("(-Dllm_api_key=).*"), "$1****")
                        }.joinToString("\n").ifEmpty { "No imported EvoSuite Maven-plugin overrides." }
                    }
                }.getOrElse { "Maven overrides are unavailable while the project model is changing." }
                ApplicationManager.getApplication().invokeLater { if (component != null && token == generation) pomNote.text = note }
            }
        }
        enableFields()
    }
    override fun isModified(): Boolean {
        if (component == null) return false
        val expected = values().toMutableMap()
        fields.forEach { (name, field) -> if (field is JCheckBox && !expected.containsKey(name)) expected[name] = SettingKeys.defaultValue(name) }
        return current() != expected.filterValues { it.isNotEmpty() } || project != null && (projectSwitch.isSelected != EvoSuiteProjectSettings.getInstance(project).state!!.useProjectSettings || delegate.isSelected != EvoSuiteProjectSettings.getInstance(project).state!!.delegateToMaven) || keyTouched
    }
    override fun apply() {
        if (project == null || projectSwitch.isSelected) {
            if ((fields["criterion"] as? CriteriaEditor)?.hasSelection() == false)
                throw ConfigurationException("Select at least one coverage criterion.")
            try { SettingKeys.resolve(current()) } catch (e: IllegalArgumentException) { throw ConfigurationException(e.message ?: "Invalid settings") }
        }
        if (project == null && keyTouched) {
            val password = String(key.password)
            try { storeApiKey(password) }
            catch (e: Exception) { throw ConfigurationException("Could not save the EvoSuite API key in secure storage. Retry Apply to save it.") }
            originalKey = password
            keyTouched = false
        }
        if (project == null) EvoSuiteAppSettings.getInstance().state!!.values = java.util.TreeMap(current())
        else EvoSuiteProjectSettings.getInstance(project).state!!.apply { values = java.util.TreeMap(current()); useProjectSettings = projectSwitch.isSelected; delegateToMaven = delegate.isSelected }
        fields.forEach { (name, field) -> if (field is SettingTextField) field.load(values()[name].orEmpty()) }
        enableFields()
    }
    /** Keep Apply open on failure; PasswordSafe still runs off EDT and may show its own prompt. */
    protected open fun storeApiKey(password: String) {
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
            Runnable { runCatching { LlmApiKeyStore.set(password) }.onFailure { failure.set(it) } },
            "Saving EvoSuite API key", false, project)
        failure.get()?.let { throw IllegalStateException("Secure storage failed", it) }
    }
    private fun enableFields() {
        val scope = project == null || projectSwitch.isSelected
        fields.keys.forEach { enableEditor(it, scope) }
        val provider = effectiveChoice("llmProvider")
        val approach = effectiveChoice("llmApproach")
        val llm = scope && provider.isNotEmpty() && provider != "NONE" && approach != "SEARCH_ONLY"
        SettingKeys.LLM.filter { it != "llmProvider" && it != "llmApproach" }.forEach { enableEditor(it, llm) }
        val search = approach != "LLM_SINGLE_PROMPT" && approach != "LLM_ITERATIVE"
        listOf("llmSeeding", "llmOnStagnation", "llmOperators").forEach { fields[it]?.isEnabled = llm && search }
        key.isEnabled = keyReady && llm && (provider == "OPENAI" || provider == "ANTHROPIC")
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
        "extraArguments" -> "None"
        else -> SettingKeys.defaultValue(name)
    }
    private fun automaticLabel(name: String): String? = when (name) {
        "javaHome" -> "Use module JDK"
        "llmModel" -> "Use provider / environment default"
        "llmBaseUrl" -> "Use provider endpoint"
        "extraArguments" -> "No additional arguments"
        else -> null
    }
    private fun effectiveChoice(name: String) = (fields[name] as? JComboBox<*>)?.selectedItem?.toString().orEmpty().ifEmpty { SettingKeys.defaultValue(name) }
    private fun defaultChoice(name: String) = if (name == "testFormat") "Automatic (JUnit 4 or JUnit 5)" else "Default (${SettingKeys.defaultValue(name)})"
    private fun defaultDescription(name: String): String = when (name) {
        "searchBudget" -> "${SettingKeys.defaultValue(name)} seconds per class"
        "testTimeout" -> "3000 ms (0 uses the engine default)"
        "clientMemory" -> "engine heap limit (0 uses the engine default)"
        "javaHome" -> "module JDK"
        "llmModel" -> "EVOSUITE_LLM_MODEL or provider default"
        "llmBaseUrl" -> "provider endpoint; Ollama: http://localhost:11434"
        "llmMaxCalls" -> "unlimited (0)"
        "extraArguments" -> "none"
        else -> SettingKeys.defaultValue(name).ifEmpty { "EvoSuite default" }
    }
    override fun disposeUIResources() { component = null; fields.clear(); generation++ }
    private fun constraints(x: Int, y: Int, weight: Double) = GridBagConstraints().apply { gridx=x; gridy=y; weightx=weight; fill=GridBagConstraints.HORIZONTAL; anchor=GridBagConstraints.NORTHWEST; insets=Insets(4, 8, 4, 8) }
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
        "llmSeeding" -> arrayOf("OFF", "MIN", "STANDARD", "FULL")
        else -> null
    }
}

private class CriteriaEditor : JPanel(BorderLayout()) {
    private val known = org.evosuite.ide.GenerationOptions.DEFAULT_CRITERIA
    private val boxes = known.associateWith { JCheckBox(it, true) }
    private val other = JTextField()
    init {
        val grid = JPanel(GridLayout(0, 2))
        boxes.values.forEach { grid.add(it) }
        add(grid, BorderLayout.CENTER)
        val custom = JPanel(BorderLayout(4, 2)); custom.add(JLabel("Other criteria (colon separated)"), BorderLayout.NORTH); custom.add(other)
        add(custom, BorderLayout.SOUTH)
    }
    fun load(value: String) {
        val selected = if (value.isBlank()) known else value.split(":")
        boxes.forEach { (name, box) -> box.isSelected = name in selected }
        other.text = selected.filter { it !in known }.joinToString(":")
    }
    fun hasSelection() = boxes.values.any { it.isSelected } || other.text.split(":").any { it.isNotBlank() }
    fun value(): String {
        val values = boxes.filterValues { it.isSelected }.keys.toList() + other.text.split(":").map { it.trim() }.filter { it.isNotEmpty() }
        return if (values == known) "" else values.distinct().joinToString(":")
    }
}

/** Track the viewport width so settings never demand a screen-wide dialog. */
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
    minimumSize = Dimension(0, 0)
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
    fun value(): String {
        if (automatic?.isSelected == true) return ""
        val value = text.trim()
        return if (value == displayed) stored else value
    }
}
