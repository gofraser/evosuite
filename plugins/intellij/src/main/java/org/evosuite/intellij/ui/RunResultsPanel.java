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

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.table.JBTable;

import org.evosuite.intellij.run.*;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

public final class RunResultsPanel extends JPanel implements Disposable {
    private record Row(RunHistory.Run run, RunHistory.Target target, int index) {}

    private static final String NO_RUNS = "No runs yet. Open a Java file and choose a generation action.";

    private final Project project;
    private final RunHistory history;
    private final List<Row> rows = new ArrayList<>();
    private boolean refreshing;
    private String latestRunId = "";
    private final AbstractTableModel model =
            new AbstractTableModel() {
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
                        case 5 -> new Date(row.run().started);
                        default -> throw new IndexOutOfBoundsException();
                    };
                }
            };
    private final JBTable table = new JBTable(model);
    private final JTextArea detail = new JTextArea(6, 40);
    private final JLabel phase = new JLabel(NO_RUNS);
    private final JButton runTests = new JButton("Run tests");
    private final JButton openReports = new JButton("Open reports");
    private final JMenuItem openTests = new JMenuItem("Open tests");
    private final JMenuItem copySummary = new JMenuItem("Copy summary");
    private final JMenuItem compare = new JMenuItem("Compare");
    private final JMenuItem finishReview = new JMenuItem("Finish review…");
    private final JMenuItem repeatRun = new JMenuItem("Repeat saved run");
    private final JMenuItem cancelRun = new JMenuItem("Cancel selected");
    private final JMenuItem clear = new JMenuItem("Clear completed runs…");
    private final Timer timer;

    public RunResultsPanel(Project project) {
        super(new BorderLayout(0, 3));
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
                            new DefaultTableCellRenderer() {
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
                        new DefaultTableCellRenderer() {
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
        phase.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 8));
        add(phase, BorderLayout.NORTH);
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setFont(UIManager.getFont("Label.font"));
        detail.getAccessibleContext().setAccessibleName("Selected run details");
        JScrollPane outcomes = new JScrollPane(table);
        outcomes.setColumnHeaderView(table.getTableHeader());
        outcomes.setBorder(BorderFactory.createEmptyBorder());
        outcomes.setViewportBorder(null);
        int tableHeight = table.getTableHeader().getPreferredSize().height
                + 2 * table.getRowHeight() + outcomes.getHorizontalScrollBar().getPreferredSize().height;
        outcomes.setMinimumSize(new Dimension(0, tableHeight));
        JScrollPane summary = new JScrollPane(detail);
        summary.setMinimumSize(new Dimension());
        JBSplitter split = new JBSplitter(true, .72f);
        split.setHonorComponentsMinimumSize(true);
        split.setFirstComponent(outcomes);
        split.setSecondComponent(summary);
        add(split, BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1, 0, 4, 0));
        addAction(actions, runTests, this::runTests);
        runTests.setToolTipText("Run the selected generated suite in IntelliJ’s Run window.");
        addAction(actions, openReports, this::reports);

        JPopupMenu menu = new JPopupMenu();
        addAction(menu, openTests, this::open);
        addAction(menu, copySummary, this::copy);
        copySummary.setToolTipText("Copy the summary shown for the selected row.");
        menu.addSeparator();
        addAction(menu, compare, this::compare);
        addAction(menu, finishReview, this::reviewed);
        addAction(menu, repeatRun, this::repeat);
        addAction(menu, cancelRun, this::cancel);
        menu.addSeparator();
        addAction(menu, new JMenuItem("Settings / readiness"), () -> ConfigurationDialog.show(project));
        addAction(menu, new JMenuItem("Run history retention…"), this::editRetention);
        addAction(menu, clear, this::clearCompleted);

        JButton more = new JButton("More…");
        more.setComponentPopupMenu(menu);
        more.addActionListener(e -> menu.show(more, 0, more.getHeight()));
        actions.add(more);
        add(actions, BorderLayout.SOUTH);
        table.getSelectionModel()
                .addListSelectionListener(
                        e -> {
                            if (!refreshing && !e.getValueIsAdjusting()) selection();
                        });
        history.listen(this, this::refresh);
        timer =
                new Timer(
                        1000,
                        e -> {
                            if (!project.isDisposed() && history.anyActive()) refresh();
                        });
        timer.start();
        refresh();
    }

    private static void addAction(JComponent parent, AbstractButton button, Runnable action) {
        button.addActionListener(e -> action.run());
        parent.add(button);
    }

    private void editRetention() {
        JSpinner days =
                new JSpinner(new SpinnerNumberModel(history.retentionDays(), 1, 3650, 1));
        JLabel label = new JLabel("Keep completed runs (days):");
        label.setLabelFor(days);
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT));
        form.add(label);
        form.add(days);
        var dialog = new DialogBuilder(project);
        dialog.setTitle("EvoSuite Run History Retention");
        dialog.setCenterPanel(form);
        if (dialog.showAndGet()) history.retention((Integer) days.getValue());
    }

    private Row selected() {
        int view = table.getSelectedRow();
        return view < 0 ? null : rows.get(table.convertRowIndexToModel(view));
    }

    public void refresh() {
        refresh(null);
    }

    void refresh(String requestedRun) {
        Row previous = selected();
        var runs = history.getState().runs;
        String newest = runs.isEmpty() ? "" : runs.getFirst().id;
        boolean newRun = !newest.isEmpty() && !newest.equals(latestRunId);
        latestRunId = newest;

        refreshing = true;
        try {
            rows.clear();
            for (var run : runs) {
                if (run.targets.isEmpty()) rows.add(new Row(run, null, -1));
                for (int index = 0; index < run.targets.size(); index++)
                    rows.add(new Row(run, run.targets.get(index), index));
            }
            model.fireTableDataChanged();
            if (!rows.isEmpty()) {
                String preferredRun = requestedRun != null ? requestedRun : newRun ? newest : null;
                int view = table.convertRowIndexToView(rowIndex(preferredRun, previous));
                if (view >= 0) {
                    table.setRowSelectionInterval(view, view);
                    if (newRun || requestedRun != null)
                        table.scrollRectToVisible(table.getCellRect(view, 0, true));
                }
            }
        } finally {
            refreshing = false;
        }
        selection();
    }

    private int rowIndex(String preferredRun, Row previous) {
        int fallback = 0;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.run().id.equals(preferredRun)) return index;
            if (previous != null && row.run().id.equals(previous.run().id) && row.index() == previous.index())
                fallback = index;
        }
        return fallback;
    }

    private void selection() {
        Row row = selected();
        var t = row == null ? null : row.target();
        if (row == null) {
            phase.setText(NO_RUNS);
        } else if (t != null && t.status.equals("Coverage measured") && t.coverage >= 0) {
            phase.setText("Coverage: " + t.coverage + "% — " + t.className);
        } else {
            phase.setText(row.run().phase);
        }
        phase.setToolTipText(phase.getText());
        Path reports = reportDirectory(t);
        String text = details(row, reports);
        if (!detail.getText().equals(text)) detail.setText(text);
        boolean outputExists = t != null && !t.output.isBlank() && Files.isRegularFile(Path.of(t.output));
        openTests.setEnabled(outputExists);
        runTests.setEnabled(outputExists && t.finished > 0 && t.comparisons.isEmpty()
                && canRun(t.status));
        boolean hasComparisons = t != null && !t.comparisons.isEmpty();
        boolean active = row != null && row.run().active();
        compare.setEnabled(hasComparisons);
        finishReview.setEnabled(hasComparisons && t.finished > 0 && !active);
        finishReview.setToolTipText(active
                ? "Comparisons can be viewed now; finish review after this request completes."
                : "Close saved comparisons without applying changes to project files.");
        openReports.setEnabled(reports != null);
        openReports.setToolTipText(
                reports == null ? "No reports were saved for this target." : "Open the saved reports folder.");
        repeatRun.setEnabled(t != null && !t.settings.isEmpty() && t.canReplay() && !active);
        repeatRun.setToolTipText(
                t == null ? "Select a completed bundled-engine target"
                        : t.replayUnavailable.isBlank()
                                ? "Generate with the saved settings and seed, using current sources and credentials"
                                : t.replayUnavailable);
        cancelRun.setEnabled(active);
        copySummary.setEnabled(row != null);
        clear.setEnabled(history.hasClearableRuns());
        clear.setToolTipText(clear.isEnabled()
                ? "Remove completed runs and their saved diagnostics."
                : "No completed runs without pending comparisons to clear.");
    }

    private static boolean canRun(String status) {
        return switch (status) {
            case "Written / updated", "Unchanged", "Review closed", "Delegated to Maven", "Coverage measured" -> true;
            default -> false;
        };
    }

    private static String details(Row row, Path reports) {
        if (row == null) return "";
        String heading = row.run().mode + " • " + new Date(row.run().started) + "\n";
        var t = row.target();
        if (t == null) return heading + row.run().phase;
        StringBuilder text = new StringBuilder(heading).append(t.className).append("\nModule: ").append(t.module);
        if (!t.methods.isEmpty()) text.append("\nMethods: ").append(String.join(", ", t.methods));
        if (!t.seeds.isEmpty()) text.append("\nExisting tests: ").append(t.seeds.stream()
                .map(seed -> seed.className).collect(Collectors.joining(", ")));
        text.append('\n').append(t.status);
        if (!t.detail.isBlank()) text.append(" — ").append(t.detail);
        return text.append("\nRandom seed: ").append(t.seed.isBlank() ? "Not recorded" : t.seed)
                .append("\nCoverage: ").append(t.coverage < 0 ? "Not available" : t.coverage + "%")
                .append("\nGenerated tests: ").append(t.tests < 0 ? "Not available" : Integer.toString(t.tests))
                .append("\nSearch progress: ").append(t.search < 0 ? "Not available" : t.search + "%")
                .append("\nOutput: ").append(t.output)
                .append("\nReports: ").append(reports == null ? "No saved reports" : reports)
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
        if (row != null && row.target() != null)
            GeneratedTestRunner.run(project, Path.of(row.target().output));
    }

    private void compare() {
        var row = selected();
        if (row != null && row.target() != null) {
            var pairs = history.comparisons(row.target());
            if (pairs.isEmpty())
                Messages.showInfoMessage(
                        project,
                        "Saved comparison files are unavailable.",
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

    private static Path reportDirectory(RunHistory.Target target) {
        if (target == null || target.reports.isBlank()) return null;
        Path directory = Path.of(target.reports);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var files = Files.find(directory, Integer.MAX_VALUE,
                (path, attributes) -> attributes.isRegularFile()
                        && !path.getFileName().toString().startsWith("."))) {
            return files.findAny().isPresent() ? directory : null;
        } catch (IOException | UncheckedIOException ignored) {
            return null;
        }
    }

    private void reports() {
        var row = selected();
        Path directory = reportDirectory(row == null ? null : row.target());
        if (directory != null) RevealFileAction.openDirectory(directory);
        else selection();
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
        if (selected() == null) return;
        CopyPasteManager.getInstance().setContents(new StringSelection(detail.getText()));
        StatusBar.Info.set("EvoSuite summary copied.", project);
    }

    private void clearCompleted() {
        if (!history.hasClearableRuns()) return;
        if (Messages.showYesNoDialog(
                        project,
                        GenerationDialogs.message(
                                "Remove completed runs from this project's history and delete their"
                                    + " saved reports and diagnostics?\n\n"
                                    + "Generated test files, active runs, and runs with pending"
                                    + " comparisons are kept."),
                        "EvoSuite: Clear Completed Runs",
                        "Clear Runs",
                        "Cancel",
                        Messages.getQuestionIcon())
                == Messages.YES) history.cleanup(true);
    }

    public void dispose() {
        timer.stop();
    }
}
