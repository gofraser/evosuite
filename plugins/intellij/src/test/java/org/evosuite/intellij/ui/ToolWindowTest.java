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

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.ui.UIUtil;
import org.evosuite.intellij.actions.TargetCollector;
import org.jetbrains.annotations.NotNull;

public class ToolWindowTest extends JavaCodeInsightFixtureTestCase {
    public void testWelcomeIsReplacedByOutputAndRetainedAfterReopening() throws Exception {
        var output = EvoSuiteConsole.getInstance(getProject());
        var panel = new EvoSuiteToolWindowPanel(getProject());
        var welcome = findWelcome(panel);
        assertNotNull(welcome);
        assertTrue(welcome.isVisible());
        capture(panel, "welcome");
        panel.setSize(400, 180);
        layoutTree(panel);
        var welcomeScroll = (javax.swing.JScrollPane) ((javax.swing.JPanel) welcome).getComponent(0);
        assertTrue("Welcome actions remain reachable in a short tool window", welcomeScroll.getVerticalScrollBar().isVisible());
        assertFalse(welcomeScroll.getHorizontalScrollBar().isVisible());
        output.println("Generating demo.Vault");
        UIUtil.dispatchAllInvocationEvents();
        assertFalse(welcome.isVisible());
        capture(panel, "console");
        var reopened = new EvoSuiteToolWindowPanel(getProject());
        assertFalse(findWelcome(reopened).isVisible());
        output.println("Finished demo.Vault");
        UIUtil.dispatchAllInvocationEvents();
        assertFalse(findWelcome(reopened).isVisible());
        panel.setSize(620, 380);
        layoutTree(panel);
    }

    public void testToolWindowActionsUseOpenEditorInsteadOfConsoleContext() {
        var file = myFixture.addFileToProject("demo/Vault.java",
                "package demo; public class Vault { public int value() { return 1; } }");
        var editor = FileEditorManager.getInstance(getProject())
                .openTextEditor(new OpenFileDescriptor(getProject(), file.getVirtualFile()), false);
        assertNotNull(editor);
        editor.getCaretModel().moveToOffset(file.getText().indexOf("return"));
        var real = new OpenFileGenerationAction("EvoSuite.GenerateMethodTests", "Generate Tests for Current Method");
        var realEvent = AnActionEvent.createEvent(SimpleDataContext.getProjectContext(getProject()),
                real.getTemplatePresentation().clone(), "EvoSuiteToolWindow", ActionUiKind.TOOLBAR, null);
        real.update(realEvent);
        assertTrue(realEvent.getPresentation().isEnabled());
        TargetCollector.Selection[] submitted = {null};
        var delegate = new DumbAwareAction() {
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void update(@NotNull AnActionEvent event) {
                event.getPresentation().setEnabled(event.getData(CommonDataKeys.EDITOR) == editor);
            }
            @Override public void actionPerformed(@NotNull AnActionEvent event) {
                submitted[0] = TargetCollector.snapshot(event);
            }
        };
        var action = new OpenFileGenerationAction(delegate, "Generate Tests for Open File");
        var event = AnActionEvent.createEvent(SimpleDataContext.getProjectContext(getProject()),
                action.getTemplatePresentation().clone(), "EvoSuiteToolWindow", ActionUiKind.TOOLBAR, null);
        action.update(event);
        assertTrue(event.getPresentation().isEnabled());
        action.actionPerformed(event);
        assertNotNull(submitted[0]);
        assertEquals(1, submitted[0].elements().size());
        var method = (com.intellij.psi.PsiMethod) submitted[0].elements().getFirst();
        assertEquals("value", method.getName());
        assertEquals(file, method.getContainingFile());
        FileEditorManager.getInstance(getProject()).closeFile(file.getVirtualFile());
        action.update(event);
        assertFalse(event.getPresentation().isEnabled());
        assertTrue("Unavailable toolbar actions stay visible", event.getPresentation().isVisible());
    }

    private static void capture(javax.swing.JPanel panel, String name) throws Exception {
        panel.setSize(620, 380);
        layoutTree(panel);
        var image = new java.awt.image.BufferedImage(620, 380, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { panel.printAll(graphics); } finally { graphics.dispose(); }
        javax.imageio.ImageIO.write(image, "png", java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                "evosuite-tool-window-" + name + ".png").toFile());
    }

    private static java.awt.Component findWelcome(java.awt.Component component) {
        if ("evosuite.welcome".equals(component.getName())) return component;
        if (component instanceof java.awt.Container parent)
            for (var child : parent.getComponents()) {
                var found = findWelcome(child);
                if (found != null) return found;
            }
        return null;
    }

    private static void layoutTree(java.awt.Component component) {
        if (component instanceof java.awt.Container parent) {
            parent.doLayout();
            for (var child : parent.getComponents()) layoutTree(child);
        }
    }
}
