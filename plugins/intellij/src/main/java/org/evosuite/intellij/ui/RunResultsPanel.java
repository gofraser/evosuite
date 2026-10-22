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

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.ui.table.JBTable;

import org.evosuite.intellij.run.*;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.file.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Persistent outcomes and recovery actions, independently of the text console. */
public final class RunResultsPanel extends JPanel implements Disposable {
    private record Row(RunHistory.Run run, RunHistory.Target target, int index) {
        String id() {
            return run.id + ":" + index;
        }
    }

    private final Project project;
    private final RunHistory history;
    private final DefaultTableModel model =
            new DefaultTableModel(
                    new String[] {"Target / module", "Status", "Coverage", "Tests", "Elapsed"}, 0) {
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };
    private final JBTable table = new JBTable(model);
    private final JTextArea detail = new JTextArea(6, 40);
    private final JLabel phase =
            new JLabel("No runs yet. Open a Java file and choose a generation action.");
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, JButton> buttons = new LinkedHashMap<>();
    private final javax.swing.Timer timer;

    public RunResultsPanel(Project project) {
        super(new BorderLayout(4, 6));
        this.project = project;
        history = RunHistory.getInstance(project);
        setName("evosuite.runs");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getAccessibleContext().setAccessibleName("EvoSuite run outcomes");
        phase.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 4, 8));
        add(phase, BorderLayout.NORTH);
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setFont(UIManager.getFont("Label.font"));
        detail.getAccessibleContext().setAccessibleName("Selected run details");
        JSplitPane split =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detail));
        split.setResizeWeight(.72);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        JPanel first = new JPanel(new GridLayout(0, 2, 4, 3));
        addButton(first, "Open tests", () -> open());
        addButton(first, "Run tests", () -> runTests());
        addButton(first, "Compare", () -> compare());
        addButton(first, "Mark reviewed", () -> reviewed());
        JPanel second = new JPanel(new GridLayout(0, 2, 4, 3));
        addButton(second, "Reports", () -> reports());
        addButton(second, "Repeat saved run", () -> repeat());
        addButton(second, "Cancel selected", () -> cancel());
        addButton(second, "Copy details", () -> copy());
        JPanel third = new JPanel(new GridLayout(0, 2, 4, 3));
        JButton config = new JButton("Settings / readiness");
        config.addActionListener(e -> ConfigurationDialog.show(project));
        third.add(config);
        JButton clear = new JButton("Clear completed");
        clear.addActionListener(
                e -> {
                    if (Messages.showYesNoDialog(
                                    project,
                                    "Delete completed history and diagnostic files? Runs with"
                                            + " pending comparisons are kept.",
                                    "EvoSuite History",
                                    Messages.getQuestionIcon())
                            == Messages.YES) history.cleanup(true);
                });
        third.add(clear);
        JPanel retention = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        JSpinner days =
                new JSpinner(new SpinnerNumberModel(history.getState().retentionDays, 1, 3650, 1));
        JLabel label = new JLabel("Keep diagnostics (days):");
        label.setLabelFor(days);
        retention.add(label);
        retention.add(days);
        days.addChangeListener(e -> history.retention((Integer) days.getValue()));
        for (JPanel section : List.of(first, second, third, retention)) {
            section.setMaximumSize(
                    new Dimension(Integer.MAX_VALUE, section.getPreferredSize().height));
            bottom.add(section);
        }
        add(bottom, BorderLayout.SOUTH);
        table.getSelectionModel()
                .addListSelectionListener(
                        e -> {
                            if (!e.getValueIsAdjusting()) selection();
                        });
        history.listen(this, this::refresh);
        Disposer.register(project, this);
        timer =
                new javax.swing.Timer(
                        1000,
                        e -> {
                            if (!project.isDisposed()
                                    && history.getState().runs.stream()
                                            .anyMatch(RunHistory.Run::active)) refresh();
                        });
        timer.start();
        refresh();
    }

    private void addButton(JPanel panel, String name, Runnable action) {
        JButton button = new JButton(name);
        button.addActionListener(e -> action.run());
        buttons.put(name, button);
        panel.add(button);
    }

    private Row selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= table.getRowCount() || rows.isEmpty()) return null;
        int index = table.convertRowIndexToModel(row);
        return index < rows.size() ? rows.get(index) : null;
    }

    public void refresh() {
        Row selected = selected();
        String id = selected == null ? "" : selected.id();
        rows.clear();
        model.setRowCount(0);
        for (var run : history.getState().runs) {
            if (run.targets.isEmpty()) rows.add(new Row(run, null, -1));
            else
                for (int i = 0; i < run.targets.size(); i++)
                    rows.add(new Row(run, run.targets.get(i), i));
        }
        int selectedIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            var t = row.target();
            long start = t != null && t.started > 0 ? t.started : row.run().started;
            long finish = t != null && t.finished > 0 ? t.finished : row.run().finished;
            model.addRow(
                    new Object[] {
                        t == null ? row.run().mode : t.className + " [" + t.module + "]",
                        t == null
                                ? row.run().status
                                : t.status.equals("Searching") && t.search >= 0
                                        ? t.status + " (" + t.search + "%)"
                                        : t.status,
                        t == null || t.coverage < 0 ? "—" : t.coverage + "%",
                        t == null || t.tests < 0 ? "—" : t.tests,
                        Math.max(
                                        0,
                                        ((finish > 0 ? finish : System.currentTimeMillis()) - start)
                                                / 1000)
                                + "s"
                    });
            if (row.id().equals(id)) selectedIndex = i;
        }
        if (selectedIndex < 0 && !rows.isEmpty()) selectedIndex = 0;
        if (selectedIndex >= 0) {
            int view = table.convertRowIndexToView(selectedIndex);
            table.setRowSelectionInterval(view, view);
        }
        selection();
    }

    private void selection() {
        Row row = selected();
        var t = row == null ? null : row.target();
        phase.setText(
                row == null
                        ? "No runs yet. Open a Java file and choose a generation action."
                        : row.run().phase);
        String text =
                row == null
                        ? ""
                        : row.run().mode
                                + " • "
                                + new java.util.Date(row.run().started)
                                + "\n"
                                + (t == null
                                        ? row.run().phase
                                        : t.className
                                                + "\nModule: " + t.module
                                                + "\n"
                                                + t.status
                                                + (t.detail.isBlank() ? "" : " — " + t.detail)
                                                + "\nSeed: "
                                                + (t.seed.isBlank() ? "Not available yet" : t.seed)
                                                + "\nSearch progress: "
                                                + (t.search < 0 ? "Not available" : t.search + "%")
                                                + "\nOutput: "
                                                + t.output
                                                + "\nReports: "
                                                + t.reports
                                                + "\nPending comparisons: "
                                                + t.comparisons.size()
                                                + "\n"
                                                + "Repeat uses saved settings and seed with the"
                                                + " current project sources and credentials.");
        if (!detail.getText().equals(text)) detail.setText(text);
        for (var button : buttons.values()) button.setEnabled(t != null);
        buttons.get("Open tests")
                .setEnabled(
                        t != null && !t.output.isBlank() && Files.isRegularFile(Path.of(t.output)));
        buttons.get("Run tests")
                .setEnabled(
                        t != null
                                && t.comparisons.isEmpty()
                                && !t.output.isBlank()
                                && Files.isRegularFile(Path.of(t.output)));
        buttons.get("Compare").setEnabled(t != null && !t.comparisons.isEmpty());
        buttons.get("Mark reviewed")
                .setEnabled(t != null && !t.comparisons.isEmpty() && t.finished > 0);
        buttons.get("Reports")
                .setEnabled(
                        t != null && !t.reports.isBlank() && Files.isDirectory(Path.of(t.reports)));
        buttons.get("Repeat saved run")
                .setEnabled(t != null && !t.settings.isEmpty() && !row.run().active());
        buttons.get("Cancel selected").setEnabled(row != null && row.run().active());
        buttons.get("Copy details").setEnabled(row != null);
    }

    private void open() {
        var row = selected();
        if (row == null || row.target() == null) return;
        var file =
                LocalFileSystem.getInstance()
                        .refreshAndFindFileByNioFile(Path.of(row.target().output));
        if (file != null) FileEditorManager.getInstance(project).openFile(file, true);
    }

    private void runTests() {
        var row = selected();
        if (row == null || row.target() == null) return;
        var file =
                LocalFileSystem.getInstance()
                        .refreshAndFindFileByNioFile(Path.of(row.target().output));
        if (file == null) return;
        var psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file);
        if (!(psi instanceof com.intellij.psi.PsiJavaFile java) || java.getClasses().length == 0)
            return;
        var settings =
                org.evosuite.intellij.run.GeneratedTestRunner.configuration(
                        project, java.getClasses()[0]);
        var config = (com.intellij.execution.junit.JUnitConfiguration) settings.getConfiguration();
        if (config.getConfigurationModule().getModule() == null) {
            Messages.showErrorDialog(
                    project,
                    "Register this output folder as test sources before running the suite. Open"
                            + " Settings / readiness to check project setup.",
                    "EvoSuite");
            return;
        }
        com.intellij.execution.ProgramRunnerUtil.executeConfiguration(
                settings,
                com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance());
    }

    private void compare() {
        var row = selected();
        if (row != null && row.target() != null) {
            var pairs = history.comparisons(row.target());
            if (pairs.isEmpty())
                Messages.showInfoMessage(
                        project,
                        "Generated comparison files are unavailable. Open Reports for recovery"
                                + " details.",
                        "EvoSuite");
            else SuiteComparison.show(project, pairs);
        }
    }

    private void reviewed() {
        var row = selected();
        if (row != null && row.target() != null) history.reviewed(row.run().id, row.index());
    }

    private void reports() {
        var row = selected();
        if (row != null && row.target() != null)
            com.intellij.ide.actions.RevealFileAction.openFile(Path.of(row.target().reports));
    }

    private void repeat() {
        var row = selected();
        if (row != null && row.target() != null)
            GenerationService.getInstance().replay(project, row.run(), row.target());
    }

    private void cancel() {
        var row = selected();
        if (row != null) GenerationService.getInstance().cancel(project, row.run().id);
    }

    private void copy() {
        var row = selected();
        if (row != null) {
            String xml =
                    com.intellij.openapi.util.JDOMUtil.writeElement(
                            com.intellij.util.xmlb.XmlSerializer.serialize(row.run()));
            com.intellij.openapi.ide.CopyPasteManager.getInstance()
                    .setContents(new StringSelection(xml));
        }
    }

    public void dispose() {
        timer.stop();
    }
}
