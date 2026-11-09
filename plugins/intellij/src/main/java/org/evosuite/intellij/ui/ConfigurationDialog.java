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
package org.evosuite.intellij.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.ui.*;
import com.intellij.ui.table.JBTable;

import org.evosuite.ide.*;
import org.evosuite.intellij.run.*;
import org.evosuite.intellij.settings.*;
import org.evosuite.intellij.setup.ProjectSetupService;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.*;
import javax.swing.table.*;

/**
 * Applied settings and readiness are resolved per module, without editing build files or reading
 * PasswordSafe.
 */
public final class ConfigurationDialog {
    private ConfigurationDialog() {}

    public static void show(Project project) {
        new Task.Backgroundable(project, "EvoSuite: check configuration", true) {
            public void run(@NotNull ProgressIndicator indicator) {
                List<String[]> rows = new ArrayList<>();
                for (Module module :
                        ReadAction.compute(() -> ModuleManager.getInstance(project).getModules())) {
                    indicator.checkCanceled();
                    if (ReadAction.compute(() -> ModuleRootManager.getInstance(module).getSourceRoots(false).length > 0))
                        addRows(module, rows);
                }
                IdeThread.edt(
                        () -> {
                            indicator.checkCanceled();
                            if (!project.isDisposed()) new View(project, rows).show();
                            return null;
                        });
            }
        }.queue();
    }

    private static void addRows(Module module, List<String[]> rows) {
        String name = module.getName();
        try {
            var options = SettingsResolver.resolve(module);
            var local = EvoSuiteProjectSettings.getInstance(module.getProject()).getState();
            var stored = local.useProjectSettings ? local.values : EvoSuiteAppSettings.getInstance().getState().values;
            String scope = local.useProjectSettings ? "Project settings" : "IDE defaults";
            for (var value : EffectiveConfiguration.describe(options, stored, scope)) {
                String text = value.value().isBlank() ? "Automatic / engine default" : value.value();
                rows.add(new String[] {name, value.setting(), text, value.source()});
            }

            String java;
            try {
                var installation = ClasspathResolver.java(module, options);
                java = installation.executable() + " (JDK " + installation.version() + ")";
                String sandbox = installation.version() >= 24 ? "SecurityManager sandbox unavailable on JDK 24+"
                        : "Available (may be disabled by engine options)";
                rows.add(new String[] {name, "Sandbox availability", sandbox, "Selected JDK"});
            } catch (IOException ex) {
                java = "Needs attention: " + ex.getMessage();
            }
            rows.add(new String[] {name, "Java runtime", java, "Preflight"});
            Module tests = ProjectSetupService.testModule(module);
            String junit = GenerationOptions.JUNIT4.equals(options.testFormat) ? "org.junit.Test" : "org.junit.jupiter.api.Test";
            rows.add(new String[] {name, "JUnit dependency", dependencyStatus(tests, junit), "Preflight"});
            String runtime = options.effectiveNoRuntimeDependency() ? "Not required for new runtime-free tests"
                    : dependencyStatus(tests, "org.evosuite.runtime.EvoRunnerParameters");
            rows.add(new String[] {name, "EvoSuite runtime dependency", runtime, "Preflight"});
            rows.add(new String[] {name, "Output", ProjectSetupService.testFolder(module, options).toString(),
                    "Resolved module location"});
            rows.add(new String[] {name, "Credentials",
                    "Provider-specific secure storage / Maven server / EVOSUITE_LLM_API_KEY; use Test connection to validate",
                    "Not read during readiness check"});
        } catch (ProcessCanceledException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            rows.add(new String[] {name, "Configuration error", String.valueOf(ex.getMessage()), "Needs attention"});
        }
    }

    private static String dependencyStatus(Module module, String className) {
        return ProjectSetupService.hasClass(module, className) ? "Available" : "Will offer dependency setup";
    }

    private static final class View extends DialogWrapper {
        private final List<String[]> rows;

        View(Project project, List<String[]> rows) {
            super(project, true);
            this.rows = rows;
            setTitle("EvoSuite: Effective Configuration and Readiness");
            setResizable(true);
            init();
        }

        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(4, 8));
            panel.setPreferredSize(new Dimension(740, 500));
            panel.add(
                    new JLabel(
                            "Applied settings only. Maven and argument overrides are shown in the"
                                    + " Source column."),
                    BorderLayout.NORTH);
            DefaultTableModel model =
                    new DefaultTableModel(
                            new String[] {"Module", "Setting", "Effective value", "Source"}, 0) {
                        public boolean isCellEditable(int r, int c) {
                            return false;
                        }
                    };
            rows.forEach(model::addRow);
            JBTable table = new JBTable(model);
            table.setAutoCreateRowSorter(true);
            table.getAccessibleContext().setAccessibleName("Effective EvoSuite settings by module");
            JPanel content = new JPanel(new BorderLayout(4, 4));
            JComboBox<String> modules =
                    new JComboBox<>(
                            java.util.stream.Stream.concat(
                                            java.util.stream.Stream.of("All modules"),
                                            rows.stream().map(r -> r[0]).distinct())
                                    .toArray(String[]::new));
            modules.addActionListener(
                    e -> {
                        var sorter = (TableRowSorter<?>) table.getRowSorter();
                        sorter.setRowFilter(
                                modules.getSelectedIndex() == 0
                                        ? null
                                        : RowFilter.regexFilter(
                                                "^"
                                                        + java.util.regex.Pattern.quote(
                                                                (String) modules.getSelectedItem())
                                                        + "$",
                                                0));
                    });
            content.add(modules, BorderLayout.NORTH);
            JTextArea detail = new JTextArea(4, 30);
            detail.setEditable(false);
            detail.setLineWrap(true);
            detail.setWrapStyleWord(true);
            detail.setFont(UIManager.getFont("Label.font"));
            table.getSelectionModel()
                    .addListSelectionListener(
                            e -> {
                                int selected = table.getSelectedRow();
                                if (selected >= 0) {
                                    int row = table.convertRowIndexToModel(selected);
                                    detail.setText(
                                            model.getValueAt(row, 1)
                                                    + "\n"
                                                    + model.getValueAt(row, 2)
                                                    + "\nSource: "
                                                    + model.getValueAt(row, 3));
                                }
                            });
            JSplitPane split =
                    new JSplitPane(
                            JSplitPane.VERTICAL_SPLIT,
                            new JScrollPane(table),
                            new JScrollPane(detail));
            split.setResizeWeight(.8);
            split.setBorder(null);
            content.add(split, BorderLayout.CENTER);
            panel.add(content, BorderLayout.CENTER);
            JButton copy = new JButton("Copy redacted configuration");
            copy.addActionListener(e -> {
                String text = rows.stream().map(row -> String.join("\t", row)).collect(Collectors.joining("\n"));
                CopyPasteManager.getInstance().setContents(new StringSelection(text));
            });
            panel.add(copy, BorderLayout.SOUTH);
            return panel;
        }

        protected Action[] createActions() {
            return new Action[] {getOKAction()};
        }
    }
}
