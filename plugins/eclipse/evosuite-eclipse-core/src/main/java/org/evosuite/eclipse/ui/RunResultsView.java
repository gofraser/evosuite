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

import org.eclipse.core.runtime.*;
import org.eclipse.debug.core.*;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.launch.*;

import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Eclipse's generation workspace: current-file actions and persistent, actionable run results. */
public final class RunResultsView extends ViewPart {
    public static final String ID = "org.evosuite.eclipse.runs";
    private TableViewer viewer;
    private Label detail;
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final Runnable listener = this::refresh;

    public static void show() {
        if (!PlatformUI.isWorkbenchRunning()) return;
        PlatformUI.getWorkbench()
                .getDisplay()
                .asyncExec(
                        () -> {
                            IWorkbenchWindow window =
                                    PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                            if (window != null && window.getActivePage() != null) {
                                try {
                                    window.getActivePage().showView(ID);
                                } catch (PartInitException e) {
                                    Activator.log(e.getStatus());
                                }
                            }
                        });
    }

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout());
        Label welcome = new Label(parent, SWT.WRAP);
        welcome.setText(
                "Open a Java file and generate class or method tests, extend existing tests, or"
                    + " measure coverage. Select a run below to open tests, run JUnit, inspect"
                    + " reports or review comparisons.");
        welcome.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Composite actions = new Composite(parent, SWT.NONE);
        actions.setLayout(new RowLayout());
        button(actions, "Generate class", () -> command("generateTests"));
        button(actions, "Generate method", () -> command("generateMethodTests"));
        button(actions, "Extend tests", () -> command("extendTests"));
        button(actions, "Measure coverage", () -> command("measureCoverage"));
        button(actions, "Settings", this::settings);
        viewer =
                new TableViewer(
                        parent,
                        SWT.FULL_SELECTION | SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        viewer.getTable().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        column("Project / class", 260, e -> e.get("project") + " / " + e.get("class"));
        column("Status", 150, e -> e.get("status"));
        column("Coverage", 85, e -> e.get("coverage"));
        column("Tests", 60, e -> e.get("tests"));
        column(
                "Elapsed",
                75,
                e ->
                        e.number("started") == 0
                                ? ""
                                : Duration.ofMillis(
                                                        (e.active()
                                                                        ? System.currentTimeMillis()
                                                                        : e.number("finished"))
                                                                - e.number("started"))
                                                .toSeconds()
                                        + " s");
        column("Seed", 160, e -> e.get("seed"));
        column("Phase", 350, e -> e.get("phase"));
        viewer.setInput(RunHistory.entries());
        viewer.addSelectionChangedListener(
                e -> {
                    RunHistory.Entry selected = selected();
                    detail.setText(
                            selected == null
                                    ? ""
                                    : selected.get("detail")
                                            + "\nOutput: "
                                            + selected.get("output")
                                            + "\nReports: "
                                            + selected.get("reports"));
                });
        viewer.addDoubleClickListener(e -> openTests());
        Composite results = new Composite(parent, SWT.NONE);
        results.setLayout(new RowLayout());
        button(results, "Open tests", this::openTests);
        button(results, "Run tests", this::runTests);
        button(
                results,
                "Compare",
                () -> {
                    if (selected() != null) SuiteComparison.open(selected().comparisons());
                });
        button(results, "Accept missing files", this::acceptMissing);
        button(
                results,
                "Reports",
                () -> {
                    if (selected() != null)
                        org.eclipse.swt.program.Program.launch(
                                RunHistory.directory(selected()).toString());
                });
        button(results, "Repeat saved run", this::repeat);
        button(
                results,
                "Mark reviewed",
                () -> {
                    var entry = selected();
                    if (entry != null && !entry.comparisons().isEmpty()
                            && MessageDialog.openConfirm(getSite().getShell(), "Finish EvoSuite review",
                                    "Discard unapplied changes? Saved edits are kept. If you accepted a runtime-free "
                                            + "suite, its unchanged generated scaffolding is removed.")) {
                        RunHistory.reviewed(entry);
                    }
                });
        button(
                results,
                "Cancel selected",
                () -> {
                    if (selected() != null) selected().cancel();
                });
        button(
                results,
                "Cancel project runs",
                () -> {
                    if (selected() != null)
                        RunHistory.entries().stream()
                                .filter(
                                        e ->
                                                e.active()
                                                        && e.get("project")
                                                                .equals(selected().get("project")))
                                .forEach(RunHistory.Entry::cancel);
                });
        button(
                results,
                "Clear completed",
                () -> {
                    if (MessageDialog.openConfirm(
                            parent.getShell(),
                            "Clear completed runs",
                            "Remove completed run history and diagnostics? "
                                    + "Pending comparisons and project test files are kept."))
                        RunHistory.prune(true);
                });
        button(
                results,
                "Retention",
                () -> {
                    InputDialog dialog =
                            new InputDialog(
                                    parent.getShell(),
                                    "Run retention",
                                    "Days to keep completed diagnostics:",
                                    Integer.toString(RunHistory.retentionDays()),
                                    value -> {
                                        try {
                                            int n = Integer.parseInt(value);
                                            if (n >= 1 && n <= 3650) return null;
                                        } catch (NumberFormatException ignored) {
                                        }
                                        return "Enter a number from 1 to 3650.";
                                    });
                    if (dialog.open() == org.eclipse.jface.window.Window.OK)
                        RunHistory.retentionDays(Integer.parseInt(dialog.getValue()));
                });
        detail = new Label(parent, SWT.WRAP);
        detail.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        RunHistory.listen(listener);
        getViewSite().setSelectionProvider(viewer);
        getViewSite()
                .getActionBars()
                .getToolBarManager()
                .add(
                        new Action("Console") {
                            @Override
                            public void run() {
                                EvoSuiteConsole.get().show();
                            }
                        });
    }

    private void column(
            String title, int width, java.util.function.Function<RunHistory.Entry, String> text) {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        col.getColumn().setWidth(width);
        col.setLabelProvider(
                new ColumnLabelProvider() {
                    @Override
                    public String getText(Object value) {
                        return text.apply((RunHistory.Entry) value);
                    }
                });
    }

    private void button(Composite parent, String label, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.addListener(SWT.Selection, e -> action.run());
    }

    private RunHistory.Entry selected() {
        return (RunHistory.Entry) viewer.getStructuredSelection().getFirstElement();
    }

    private void refresh() {
        if (refreshPending.compareAndSet(false, true))
            Display.getDefault()
                    .asyncExec(
                            () -> {
                                refreshPending.set(false);
                                if (viewer != null && !viewer.getControl().isDisposed()) {
                                    ISelection selected = viewer.getSelection();
                                    viewer.setInput(RunHistory.entries());
                                    viewer.setSelection(selected);
                                }
                            });
    }

    private void command(String name) {
        try {
            getSite()
                    .getService(IHandlerService.class)
                    .executeCommand("org.evosuite.eclipse." + name, null);
        } catch (Exception e) {
            MessageDialog.openError(getSite().getShell(), "EvoSuite", e.getMessage());
        }
    }

    private void settings() {
        if (selected() != null)
            PreferencesUtil.createPropertyDialogOn(
                            getSite().getShell(),
                            selected().project().getProject(),
                            "org.evosuite.eclipse.preferences.EvoSuitePropertyPage",
                            null,
                            null)
                    .open();
        else
            PreferencesUtil.createPreferenceDialogOn(
                            getSite().getShell(),
                            "org.evosuite.eclipse.preferences.EvoSuitePreferencePage",
                            null,
                            null)
                    .open();
    }

    private void openTests() {
        try {
            if (selected() != null && selected().output() != null && selected().output().exists())
                IDE.openEditor(getSite().getPage(), selected().output(), true);
        } catch (PartInitException e) {
            Activator.log(e.getStatus());
        }
    }

    private void runTests() {
        RunHistory.Entry entry = selected();
        if (entry == null || entry.active() || entry.output() == null || !entry.output().exists())
            return;
        if (!entry.comparisons().isEmpty()) {
            MessageDialog.openInformation(
                    getSite().getShell(),
                    "EvoSuite",
                    "Finish reviewing comparisons before running this suite.");
            return;
        }
        try {
            ICompilationUnit unit = JavaCore.createCompilationUnitFrom(entry.output());
            IType type = unit == null ? null : unit.findPrimaryType();
            if (type == null || !unit.getJavaProject().isOnClasspath(unit))
                throw new CoreException(
                        Activator.error("The suite must be in a test source folder."));
            var manager = DebugPlugin.getDefault().getLaunchManager();
            var config =
                    manager.getLaunchConfigurationType("org.eclipse.jdt.junit.launchconfig")
                            .newInstance(
                                    null,
                                    manager.generateLaunchConfigurationName(
                                            type.getElementName() + " (EvoSuite)"));
            config.setAttribute(
                    IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, entry.get("project"));
            config.setAttribute(
                    IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                    type.getFullyQualifiedName());
            config.setAttribute(
                    "org.eclipse.jdt.junit.TEST_KIND",
                    entry.options().effectiveTestFormat().equals(GenerationOptions.JUNIT4)
                            ? "org.eclipse.jdt.junit.loader.junit4"
                            : "org.eclipse.jdt.junit.loader.junit5");
            if (!entry.options().effectiveNoRuntimeDependency()) {
                var vm = org.eclipse.jdt.launching.JavaRuntime.getVMInstall(type.getJavaProject());
                if (vm == null) vm = org.eclipse.jdt.launching.JavaRuntime.getDefaultVMInstall();
                if (vm instanceof org.eclipse.jdt.launching.IVMInstall2 versioned) {
                    String version = versioned.getJavaVersion();
                    if (version != null && version.matches("^(18|19|2[0-3])(?:[._+-].*)?$")) {
                        config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,
                                "-Djava.security.manager=allow");
                    }
                }
            }
            DebugUITools.launch(config, ILaunchManager.RUN_MODE);
        } catch (CoreException e) {
            MessageDialog.openError(getSite().getShell(), "Run generated tests", e.getMessage());
        }
    }

    private void acceptMissing() {
        RunHistory.Entry entry = selected();
        if (entry == null) return;
        var missing = entry.comparisons().stream().filter(p -> !p.existing().exists()).toList();
        if (missing.isEmpty()) return;
        var dialog =
                new org.eclipse.ui.dialogs.ListSelectionDialog(
                        getSite().getShell(),
                        missing,
                        ArrayContentProvider.getInstance(),
                        new LabelProvider() {
                            @Override
                            public String getText(Object item) {
                                return ((SuiteComparison.Pair) item)
                                        .existing()
                                        .getFullPath()
                                        .toString();
                            }
                        },
                        "Select generated files to create. Review suite and scaffolding"
                            + " compatibility together before running tests.");
        if (dialog.open() != org.eclipse.jface.window.Window.OK) return;
        try {
            for (Object item : dialog.getResult()) {
                var pair = (SuiteComparison.Pair) item;
                if (pair.existing().exists()) continue;
                ProjectSetup.createFolders(pair.existing().getParent());
                pair.existing()
                        .create(
                                new java.io.ByteArrayInputStream(
                                        pair.generatedSource()
                                                .getBytes(
                                                        java.nio.charset.Charset.forName(
                                                                pair.existing()
                                                                        .getParent()
                                                                        .getDefaultCharset()))),
                                false,
                                null);
                GeneratedSuiteRegistry.record(
                        pair.existing().getLocation().toFile(), pair.generatedSource());
            }
        } catch (CoreException e) {
            MessageDialog.openError(getSite().getShell(), "Accept generated file", e.getMessage());
        }
    }

    private void repeat() {
        RunHistory.Entry entry = selected();
        if (entry == null || entry.active()) return;
        if (Boolean.parseBoolean(entry.get("delegated"))) {
            MessageDialog.openInformation(
                    getSite().getShell(),
                    "Repeat Maven run",
                    "Maven controls settings, seeds and export. Repeat this request through the"
                        + " Maven runner.");
            return;
        }
        try {
            boolean coverage = Boolean.parseBoolean(entry.get("coverageOnly"));
            if (!coverage
                    && !MessageDialog.openConfirm(
                            getSite().getShell(),
                            "Repeat saved run",
                            "Repeat with the saved engine settings and seed, using current sources"
                                + " and credentials? Existing suites will open in a"
                                + " comparison.")) return;
            if (!PlatformUI.getWorkbench().saveAllEditors(true)) return;
            new TestGenerationJob(
                            entry.project(),
                            List.of(entry.target()),
                            entry.options(),
                            SuiteAction.COMPARE,
                            coverage)
                    .schedule();
        } catch (RuntimeException e) {
            MessageDialog.openError(getSite().getShell(), "Repeat saved run", e.getMessage());
        }
    }

    @Override
    public void setFocus() {
        viewer.getControl().setFocus();
    }

    @Override
    public void dispose() {
        RunHistory.unlisten(listener);
        super.dispose();
    }
}
