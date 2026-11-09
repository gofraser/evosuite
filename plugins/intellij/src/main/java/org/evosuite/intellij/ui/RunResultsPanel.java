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

public final class RunResultsPanel extends JPanel implements Disposable {
    private record Row(RunHistory.Run run, RunHistory.Target target, int index) {
        String id() {
            return run.id + ":" + index;
        }
    }

    private final Project project;
    private final RunHistory history;
    private final List<Row> rows = new ArrayList<>();
    private boolean refreshing;
    private String latestRunId = "";
    private final javax.swing.table.AbstractTableModel model =
            new javax.swing.table.AbstractTableModel() {
                private final String[] columns = {
                    "Target / module",
                    "Status",
                    "Coverage",
                    "Generated tests",
                    "Execution",
                    "Run started"
                };

                public int getRowCount() {
                    return rows.size();
                }

                public int getColumnCount() {
                    return columns.length;
                }

                public String getColumnName(int column) {
                    return columns[column];
                }

                public Class<?> getColumnClass(int column) {
                    return switch (column) {
                        case 2, 3 -> Integer.class;
                        case 4 -> Long.class;
                        case 5 -> Date.class;
                        default -> String.class;
                    };
                }

                public Object getValueAt(int index, int column) {
                    Row row = rows.get(index);
                    var target = row.target();
                    return switch (column) {
                        case 0 ->
                                target == null
                                        ? row.run().mode
                                        : target.className + " [" + target.module + "]";
                        case 1 ->
                                target == null
                                        ? row.run().status
                                        : target.status.equals("Searching") && target.search >= 0
                                                ? target.status + " (" + target.search + "%)"
                                                : target.status;
                        case 2 -> target == null ? -1 : target.coverage;
                        case 3 -> target == null ? -1 : target.tests;
                        case 4 -> {
                            long started = target == null ? 0 : target.started;
                            long finished = target == null ? 0 : target.finished;
                            yield started == 0
                                    ? -1L
                                    : Math.max(
                                            0,
                                            ((finished > 0 ? finished : System.currentTimeMillis())
                                                            - started)
                                                    / 1000);
                        }
                        case 5 -> new java.util.Date(row.run().started);
                        default -> throw new IndexOutOfBoundsException();
                    };
                }
            };
    private final JBTable table = new JBTable(model);
    private final JTextArea detail = new JTextArea(6, 40);
    private final JLabel phase =
            new JLabel("No runs yet. Open a Java file and choose a generation action.");
    private final Map<String, JButton> buttons = new LinkedHashMap<>();
    private final javax.swing.Timer timer;

    public RunResultsPanel(Project project) {
        super(new BorderLayout(4, 6));
        this.project = project;
        history = RunHistory.getInstance(project);
        setName("evosuite.runs");
        table.setName("evosuite.runs.table");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {240, 160, 80, 110, 90, 170};
        for (int i = 0; i < widths.length; i++)
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        for (int column : List.of(2, 3, 4)) {
            final String suffix = column == 2 ? "%" : column == 4 ? "s" : "";
            table.getColumnModel()
                    .getColumn(column)
                    .setCellRenderer(
                            new javax.swing.table.DefaultTableCellRenderer() {
                                @Override
                                protected void setValue(Object value) {
                                    setHorizontalAlignment(SwingConstants.RIGHT);
                                    setText(
                                            value instanceof Number n && n.longValue() >= 0
                                                    ? value + suffix
                                                    : "—");
                                }
                            });
        }
        table.getColumnModel()
                .getColumn(5)
                .setCellRenderer(
                        new javax.swing.table.DefaultTableCellRenderer() {
                            @Override
                            protected void setValue(Object value) {
                                setText(
                                        value instanceof Date date
                                                ? java.text.DateFormat.getDateTimeInstance(
                                                                java.text.DateFormat.SHORT,
                                                                java.text.DateFormat.MEDIUM)
                                                        .format(date)
                                                : "");
                            }
                        });
        table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(5, SortOrder.DESCENDING)));

        table.getAccessibleContext().setAccessibleName("EvoSuite run outcomes");
        phase.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 4, 8));
        add(phase, BorderLayout.NORTH);
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setFont(UIManager.getFont("Label.font"));
        detail.getAccessibleContext().setAccessibleName("Selected run details");
        JScrollPane outcomes = new JScrollPane(table);
        outcomes.setColumnHeaderView(table.getTableHeader());
        JSplitPane split =
                new JSplitPane(JSplitPane.VERTICAL_SPLIT, outcomes, new JScrollPane(detail));
        split.setResizeWeight(.72);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        JPanel first = new JPanel(new GridLayout(0, 2, 4, 3));
        addButton(first, "Open tests", () -> open());
        addButton(first, "Run tests", () -> runTests());
        addButton(first, "Compare", () -> compare());
        addButton(first, "Finish review…", () -> reviewed());
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
                                    GenerationDialogs.message(
                                            "Delete completed history and diagnostic files? Runs"
                                                + " with pending comparisons are kept."),
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
                            if (!refreshing && !e.getValueIsAdjusting()) selection();
                        });
        history.listen(this, this::refresh);
        Disposer.register(project, this);
        timer =
                new javax.swing.Timer(
                        1000,
                        e -> {
                            if (!project.isDisposed() && history.anyActive()) refresh();
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
        Row previous = selected();
        String id = previous == null ? "" : previous.id();
        refreshing = true;
        try {
            var snapshot = history.getState();
            String newest = snapshot.runs.isEmpty() ? "" : snapshot.runs.getFirst().id;
            boolean newRun = !newest.isEmpty() && !newest.equals(latestRunId);
            latestRunId = newest;
            List<Row> next = new ArrayList<>();
            for (var run : snapshot.runs) {
                if (run.targets.isEmpty()) next.add(new Row(run, null, -1));
                else
                    for (int index = 0; index < run.targets.size(); index++)
                        next.add(new Row(run, run.targets.get(index), index));
            }
            if (newRun && !next.isEmpty()) id = next.getFirst().id();
            rows.clear();
            rows.addAll(next);
            model.fireTableDataChanged();
            int index = -1;
            for (int i = 0; i < rows.size(); i++)
                if (rows.get(i).id().equals(id)) {
                    index = i;
                    break;
                }
            if (index < 0 && !rows.isEmpty()) index = 0;
            if (index >= 0) {
                int view = table.convertRowIndexToView(index);
                if (view >= 0) {
                    table.setRowSelectionInterval(view, view);
                    if (newRun) table.scrollRectToVisible(table.getCellRect(view, 0, true));
                }
            }
        } finally {
            refreshing = false;
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
        phase.setToolTipText(phase.getText());
        String text = details(row);
        if (!detail.getText().equals(text)) detail.setText(text);
        for (var button : buttons.values()) button.setEnabled(t != null);
        boolean outputExists = t != null && !t.output.isBlank() && Files.isRegularFile(Path.of(t.output));
        buttons.get("Open tests").setEnabled(outputExists);
        buttons.get("Run tests").setEnabled(outputExists && t.finished > 0 && t.comparisons.isEmpty()
                && canRun(t.status));
        buttons.get("Compare").setEnabled(t != null && !t.comparisons.isEmpty());
        buttons.get("Finish review…")
                .setEnabled(
                        t != null
                                && !t.comparisons.isEmpty()
                                && t.finished > 0
                                && !row.run().active());
        buttons.get("Finish review…")
                .setToolTipText(
                        row != null && row.run().active()
                                ? "Comparisons can be viewed now; finish review after this request"
                                        + " completes."
                                : "Close saved comparisons without applying changes to project"
                                        + " files.");
        buttons.get("Reports")
                .setEnabled(
                        t != null && !t.reports.isBlank() && Files.isDirectory(Path.of(t.reports)));
        buttons.get("Repeat saved run")
                .setEnabled(
                        t != null
                                && !t.settings.isEmpty()
                                && t.canReplay()
                                && !row.run().active());
        buttons.get("Repeat saved run")
                .setToolTipText(
                        t == null
                                ? "Select a completed bundled-engine target"
                                : t.replayUnavailable.isBlank()
                                        ? "Generate with the saved settings and seed, using current"
                                                + " sources and credentials"
                                        : t.replayUnavailable);
        buttons.get("Cancel selected").setEnabled(row != null && row.run().active());
        buttons.get("Copy details").setEnabled(row != null);
    }

    private static boolean canRun(String status) {
        return switch (status) {
            case "Written / updated", "Unchanged", "Review closed", "Delegated to Maven", "Coverage measured" -> true;
            default -> false;
        };
    }

    private static String details(Row row) {
        if (row == null) return "";
        String heading = row.run().mode + " • " + new java.util.Date(row.run().started) + "\n";
        var t = row.target();
        if (t == null) return heading + row.run().phase;
        StringBuilder text = new StringBuilder(heading).append(t.className).append("\nModule: ").append(t.module);
        if (!t.methods.isEmpty()) text.append("\nMethods: ").append(String.join(", ", t.methods));
        if (!t.seeds.isEmpty()) text.append("\nExisting tests: ").append(t.seeds.stream()
                .map(seed -> seed.className).collect(java.util.stream.Collectors.joining(", ")));
        text.append('\n').append(t.status);
        if (!t.detail.isBlank()) text.append(" — ").append(t.detail);
        return text.append("\nRandom seed: ").append(t.seed.isBlank() ? "Not recorded" : t.seed)
                .append("\nCoverage: ").append(t.coverage < 0 ? "Not available" : t.coverage + "%")
                .append("\nGenerated tests: ").append(t.tests < 0 ? "Not available" : Integer.toString(t.tests))
                .append("\nSearch progress: ").append(t.search < 0 ? "Not available" : t.search + "%")
                .append("\nOutput: ").append(t.output)
                .append("\nReports: ").append(t.reports)
                .append("\nPending comparisons: ").append(t.comparisons.size())
                .append('\n').append(t.replayUnavailable.isBlank()
                        ? "Repeat uses saved settings and seed with the current project sources and credentials."
                        : t.replayUnavailable).toString();
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
        var suiteClass = GeneratedTestRunner.suiteClass(java);
        if (suiteClass == null) {
            Messages.showInfoMessage(
                    project,
                    GenerationDialogs.message(
                            "No test class matches this file's name. Check the generated source"
                                + " before running tests."),
                    "EvoSuite");
            return;
        }
        var settings = GeneratedTestRunner.configuration(project, suiteClass);
        var config = (com.intellij.execution.junit.JUnitConfiguration) settings.getConfiguration();
        if (config.getConfigurationModule().getModule() == null) {
            Messages.showErrorDialog(
                    project,
                    GenerationDialogs.message(
                            "Register this output folder as test sources before running the suite."
                                + " Open Settings / readiness to check project setup."),
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
                        GenerationDialogs.message(
                                "Generated comparison files are unavailable. Open Reports for"
                                    + " recovery details."),
                        "EvoSuite");
            else SuiteComparison.show(project, pairs);
        }
    }

    private void reviewed() {
        var row = selected();
        if (row != null
                && row.target() != null
                && Messages.showYesNoDialog(
                                project,
                                GenerationDialogs.message(
                                        "Finish reviewing this target? Only changes you already"
                                            + " copied or accepted into project files are kept.\n\n"
                                            + "This closes the saved comparisons without applying"
                                            + " changes. Generated suggestions become eligible for"
                                            + " diagnostic cleanup."),
                                "EvoSuite: Finish Review",
                                "Finish Review",
                                "Keep Reviewing",
                                Messages.getQuestionIcon())
                        == Messages.YES) history.reviewed(row.run().id, row.index());
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
