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
package org.evosuite.eclipse.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.handlers.GenerateTestsHandler;
import org.evosuite.eclipse.launch.RunHistory;
import org.evosuite.eclipse.launch.RunHistory.Run;

public final class RunsView extends ViewPart {
    public static final String ID = "org.evosuite.eclipse.runs";

    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final Runnable listener = this::queueRefresh;
    private Display display;
    private TableViewer table;
    private Text details;
    private Button openSuite;
    private Button runTests;
    private Button reports;
    private Button repeat;
    private Button review;
    private Button finish;
    private Button cancel;
    private Button copy;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        table = new TableViewer(parent, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        table.getTable().setHeaderVisible(true);
        table.getTable().setLinesVisible(true);
        table.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.setContentProvider(ArrayContentProvider.getInstance());
        column("Started (UTC)", 165, run -> run.started().substring(0, 19).replace('T', ' '));
        column("Project", 160, Run::project);
        column("Target", 210, Run::label);
        column("Operation", 105, run -> run.coverageOnly() ? "Coverage" : "Generation");
        column("Status", 110, run -> run.state().name());

        Composite buttons = new Composite(parent, SWT.NONE);
        buttons.setLayout(new org.eclipse.swt.layout.RowLayout());
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        openSuite = button(buttons, "Open suite", () -> {
            for (var file : selected().suites()) {
                try {
                    IDE.openEditor(getSite().getPage(), file);
                } catch (CoreException e) {
                    error(e.getStatus().getMessage());
                }
            }
        });
        runTests = button(buttons, "Run tests", () -> {
            for (var file : selected().suites()) {
                try {
                    new SuiteJUnitLaunch().run(file, selected().options());
                } catch (CoreException | IllegalArgumentException e) {
                    error(e.getMessage());
                }
            }
        });
        reports = button(buttons, "Open reports", () -> {
            if (!Program.launch(selected().reports().toString())) error("Cannot open the reports folder.");
        });
        repeat = button(buttons, "Rerun with saved settings", () -> {
            try {
                GenerateTestsHandler.replay(getSite().getShell(), selected());
            } catch (CoreException | IllegalArgumentException e) {
                error(e.getMessage());
            }
        });
        review = button(buttons, "Review changes", () -> SuiteComparison.open(selected().comparisons()));
        finish = button(buttons, "Finish review", () -> {
            if (MessageDialog.openConfirm(getSite().getShell(), "Finish EvoSuite review",
                    "Finish this review and discard unapplied changes? Saved edits are kept. "
                            + "If you accepted a runtime-free suite, its unchanged generated scaffolding is removed.")) {
                try {
                    selected().finishReview();
                } catch (CoreException e) {
                    error(e.getStatus().getMessage());
                }
            }
        });
        cancel = button(buttons, "Cancel run", () -> selected().cancel());
        copy = button(buttons, "Copy summary", () -> {
            Clipboard clipboard = new Clipboard(display);
            try {
                clipboard.setContents(new Object[]{details.getText()}, new Transfer[]{TextTransfer.getInstance()});
            } finally {
                clipboard.dispose();
            }
        });
        button(buttons, "Clear completed", () -> {
            if (MessageDialog.openConfirm(getSite().getShell(), "Clear completed EvoSuite runs",
                    "Delete completed run records and their reports? Runs awaiting review and test files are kept.")) {
                RunHistory.clearCompleted();
            }
        });

        details = new Text(parent, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
        GridData layout = new GridData(SWT.FILL, SWT.FILL, true, false);
        layout.heightHint = 95;
        details.setLayoutData(layout);
        table.addSelectionChangedListener(event -> updateSelection());
        RunHistory.listen(listener);
        refresh(null);
    }

    private void column(String label, int width, Function<Run, String> value) {
        TableViewerColumn column = new TableViewerColumn(table, SWT.NONE);
        column.getColumn().setText(label);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return value.apply((Run) element);
            }
        });
    }

    private static Button button(Composite parent, String label, Runnable run) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.addListener(SWT.Selection, event -> run.run());
        return button;
    }

    private Run selected() {
        return (Run) table.getStructuredSelection().getFirstElement();
    }

    private void queueRefresh() {
        if (display.isDisposed() || !refreshQueued.compareAndSet(false, true)) return;
        display.asyncExec(() -> {
            refreshQueued.set(false);
            if (!table.getControl().isDisposed()) {
                Run run = selected();
                refresh(run == null ? null : run.id());
            }
        });
    }

    private void refresh(String runId) {
        List<Run> runs = RunHistory.runs();
        table.setInput(runs);
        Run next = runs.stream().filter(run -> run.id().equals(runId))
                .findFirst().orElse(runs.isEmpty() ? null : runs.getFirst());
        table.setSelection(next == null ? StructuredSelection.EMPTY : new StructuredSelection(next), true);
        updateSelection();
    }

    private void updateSelection() {
        Run run = selected();
        boolean hasSuites = run != null && !run.suites().isEmpty();
        boolean hasReview = run != null && run.hasReview();
        openSuite.setEnabled(hasSuites);
        runTests.setEnabled(hasSuites);
        review.setEnabled(hasReview);
        finish.setEnabled(hasReview);
        reports.setEnabled(run != null && hasReports(run));
        cancel.setEnabled(run != null && run.canCancel());
        repeat.setEnabled(run != null && !run.canCancel() && !run.delegatedToMaven()
                && ResourcesPlugin.getWorkspace().getRoot().getProject(run.project()).isAccessible());
        copy.setEnabled(run != null);
        details.setText(run == null ? "" : run.project() + " / " + run.label() + " — " + run.state() + "\n"
                + run.started() + "\n" + (run.canCancel() ? run.progress() : run.summary())
                + settingsSummary(run));
    }

    private static String settingsSummary(Run run) {
        if (run.delegatedToMaven()) return "\nSettings from Maven";
        try {
            var options = run.options();
            return "\nSearch budget: " + options.effectiveSearchBudget() + " s"
                    + "\nRandom seed: " + options.effectiveRandomSeed()
                    + "\nTest format: " + options.effectiveTestFormat();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean hasReports(Run run) {
        if (!Files.isDirectory(run.reports())) return false;
        try (var paths = Files.walk(run.reports())) {
            return paths.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }

    private void error(String message) {
        MessageDialog.openError(getSite().getShell(), "EvoSuite", message);
    }

    public static void show(String runId) {
        if (!PlatformUI.isWorkbenchRunning()) return;
        var workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().asyncExec(() -> {
            var window = workbench.getActiveWorkbenchWindow();
            if (window == null || window.getActivePage() == null) return;
            try {
                var view = (RunsView) window.getActivePage().showView(ID);
                view.refresh(runId);
            } catch (CoreException e) {
                Activator.log(e.getStatus());
            }
        });
    }

    @Override
    public void setFocus() {
        table.getControl().setFocus();
    }

    @Override
    public void dispose() {
        RunHistory.unlisten(listener);
        super.dispose();
    }
}
