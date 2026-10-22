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

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.util.ui.JBUI;
import org.evosuite.intellij.run.GenerationService;
import org.evosuite.intellij.settings.EvoSuiteProjectConfigurable;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import javax.swing.*;

public final class EvoSuiteToolWindowPanel extends JPanel
        implements com.intellij.openapi.Disposable {
    public EvoSuiteToolWindowPanel(Project project) {
        super(new BorderLayout());
        EvoSuiteConsole output = EvoSuiteConsole.getInstance(project);
        var console = output.view();
        var generate = new OpenFileGenerationAction("EvoSuite.GenerateTests", "Generate Tests for Open File");
        var method = new OpenFileGenerationAction("EvoSuite.GenerateMethodTests", "Generate Tests for Current Method");
        var coverage =
                new OpenFileGenerationAction(
                        "EvoSuite.MeasureCoverage", "Measure Coverage of Open Tests");
        var extend = new OpenFileGenerationAction("EvoSuite.ExtendTests", "Extend Tests in Open File");
        var settings = new DumbAwareAction("EvoSuite Settings", "Configure EvoSuite", AllIcons.General.Settings) {
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void actionPerformed(@NotNull AnActionEvent event) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, EvoSuiteProjectConfigurable.class);
            }
        };
        DefaultActionGroup actions = new DefaultActionGroup(generate, method, extend, coverage, settings);
        actions.addSeparator();
        actions.add(new AnAction(
                        "Cancel all project runs",
                        "Cancel all queued and running EvoSuite requests in this project", AllIcons.Actions.Cancel) {
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
            @Override public void update(@NotNull AnActionEvent event) {
                event.getPresentation().setEnabled(GenerationService.getInstance().isRunning(project));
            }
            @Override public void actionPerformed(@NotNull AnActionEvent event) {
                GenerationService.getInstance().cancel(project);
            }
        });
        actions.addAll(console.createConsoleActions());
        var toolbar = ActionManager.getInstance().createActionToolbar("EvoSuiteConsole", actions, true);
        toolbar.setTargetComponent(this);
        add(toolbar.getComponent(), BorderLayout.NORTH);

        CardLayout cards = new CardLayout();
        JPanel body = new JPanel(cards);
        JPanel welcome = new JPanel(new BorderLayout());
        JPanel centered = new WelcomeViewport();
        welcome.setName("evosuite.welcome");
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(JBUI.Borders.empty(16));
        JLabel title = new JLabel("Generate Java tests with EvoSuite");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        content.add(title);
        content.add(Box.createVerticalStrut(JBUI.scale(8)));
        JTextArea help = new JTextArea(
                        "Open a Java file and choose an action below. You can also right-click a"
                            + " class, package or module in the Project view. Progress and results"
                            + " appear here when generation starts.", 6, 34);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setEditable(false);
        help.setFocusable(false);
        help.setOpaque(false);
        help.setFont(UIManager.getFont("Label.font"));
        content.add(help);
        for (var action : new AnAction[] {generate, method, extend, coverage, settings}) {
            JButton button = new JButton(action.getTemplatePresentation().getText());
            button.addActionListener(event -> ActionUtil.performAction(action, AnActionEvent.createEvent(
                    SimpleDataContext.getProjectContext(project), action.getTemplatePresentation().clone(),
                    "EvoSuiteToolWindow", ActionUiKind.TOOLBAR, null)));
            content.add(Box.createVerticalStrut(JBUI.scale(6)));
            content.add(button);
        }
        for (Component child : content.getComponents())
            if (child instanceof JComponent component) component.setAlignmentX(Component.LEFT_ALIGNMENT);
        centered.add(content);
        JScrollPane welcomeScroll = new JScrollPane(centered);
        welcomeScroll.setBorder(JBUI.Borders.empty());
        welcome.add(welcomeScroll);
        body.add(welcome, "welcome");
        body.add(console.getComponent(), "console");
        JTabbedPane sections = new JTabbedPane();
        sections.addTab("Console", body);
        var results = new RunResultsPanel(project);
        com.intellij.openapi.util.Disposer.register(this, results);
        sections.addTab("Runs", results);
        add(sections, BorderLayout.CENTER);
        com.intellij.openapi.util.Disposer.register(project, this);
        final String[] latest = {""};
        org.evosuite.intellij.run.RunHistory.getInstance(project)
                .listen(
                        this,
                        () -> {
                            var runs =
                                    org.evosuite.intellij.run.RunHistory.getInstance(project)
                                            .getState()
                                            .runs;
                            if (!runs.isEmpty() && !runs.getFirst().id.equals(latest[0])) {
                                latest[0] = runs.getFirst().id;
                                sections.setSelectedComponent(results);
                            }
                        });
        cards.show(body, output.hasOutput() ? "console" : "welcome");
        output.onOutput(() -> cards.show(body, "console"));
    }

    @Override
    public void dispose() {}
    private static final class WelcomeViewport extends JPanel implements Scrollable {
        WelcomeViewport() { super(new GridBagLayout()); }
        @Override public Dimension getPreferredScrollableViewportSize() { return JBUI.size(420, 280); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getPreferredSize().height < getParent().getHeight();
        }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return JBUI.scale(24); }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return visible.height; }
    }

}
