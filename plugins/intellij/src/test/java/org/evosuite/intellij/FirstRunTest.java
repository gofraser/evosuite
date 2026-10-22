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
package org.evosuite.intellij;

import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.ui.UIUtil;
import org.evosuite.intellij.actions.TargetCollector;
import org.evosuite.intellij.run.GenerationService;
import org.evosuite.intellij.settings.EvoSuiteProjectSettings;
import org.evosuite.intellij.setup.ProjectSetupService;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class FirstRunTest extends JavaCodeInsightFixtureTestCase {
    public void testUiCancellationAndFailuresReachBackgroundGenerationUnchanged() {
        for (RuntimeException expected : List.of(new com.intellij.openapi.progress.ProcessCanceledException(),
                new IllegalArgumentException("Cannot update the project build file"))) {
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    org.evosuite.intellij.run.IdeThread.edt(() -> { throw expected; });
                    return null;
                } catch (RuntimeException actual) { return actual; }
            });
            assertSame(expected, com.intellij.testFramework.PlatformTestUtil.waitForFuture(future, 10_000));
        }
    }

    public void testCancelExistingSuiteQuestionStopsBeforeSetupOrGeneration() throws Exception {
        var file = (com.intellij.psi.PsiJavaFile) myFixture.addFileToProject("src/demo/CancelRun.java",
                "package demo; public class CancelRun {} ");
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () ->
                com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(getModule(), model ->
                        model.getContentEntries()[0].addSourceFolder(file.getVirtualFile().getParent().getParent(), false)));
        String original = "package demo; @org.evosuite.runtime.EvoRunnerParameters public class CancelRun_ESTest {}";
        var suite = myFixture.addFileToProject("output-tests/demo/CancelRun_ESTest.java", original).getVirtualFile();
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        var state = EvoSuiteProjectSettings.getInstance(getProject()).getState();
        state.useProjectSettings = true;
        state.values.put("javaHome", myFixture.getTempDirPath() + "/missing-jdk");
        state.values.put("testFolder", "output-tests");
        List<String> questions = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Notification> notifications = new java.util.concurrent.CopyOnWriteArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC,
                new Notifications() { @Override public void notify(Notification n) {
                    if ("EvoSuite".equals(n.getGroupId())) notifications.add(n);
                }});
        var previous = TestDialogManager.setTestDialog(message -> { questions.add(message); return 3; });
        try {
            GenerationService.getInstance().submit(getProject(),
                    new TargetCollector.Selection(List.of(file.getClasses()[0]), List.of(), List.of()),
                    TargetCollector.Mode.CLASSES);
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while ((questions.isEmpty() || GenerationService.getInstance().isRunning(getProject()))
                    && System.nanoTime() < until) {
                UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10);
            }
            assertEquals(questions.toString(), 1, questions.size());
            assertTrue(questions.getFirst().contains("Replace:"));
            assertTrue(questions.getFirst().contains("Add New Tests:"));
            assertTrue(questions.getFirst().contains("Compare:"));
            assertTrue(questions.getFirst().contains("Cancel stops this request"));
            for (String line : questions.getFirst().split("\n")) assertTrue(line, line.length() <= 88);
            assertFalse(GenerationService.getInstance().isRunning(getProject()));
            assertTrue("Cancel should not proceed to JDK validation/setup", notifications.isEmpty());
            assertEquals(original, com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(suite).getText());
        } finally { TestDialogManager.setTestDialog(previous); }
    }

    public void testMissingJdkStopsBeforeDependencyPromptsAndOffersSettingsRecovery() throws Exception {
        var file = (com.intellij.psi.PsiJavaFile) myFixture.addFileToProject("src/demo/FirstRun.java",
                "package demo; public class FirstRun { public int value() { return 1; } }");
        var type = file.getClasses()[0];
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () ->
                com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(getModule(), model ->
                        model.getContentEntries()[0].addSourceFolder(file.getVirtualFile().getParent().getParent(), false)));
        var selection = new TargetCollector.Selection(List.of(type), List.of(), List.of());
        assertFalse(TargetCollector.collect(getProject(), selection, TargetCollector.Mode.CLASSES).targets().isEmpty());
        var state = EvoSuiteProjectSettings.getInstance(getProject()).getState();
        state.useProjectSettings = true;
        state.values.put("javaHome", myFixture.getTempDirPath() + "/missing-jdk");
        state.values.put("testFolder", "first-run-tests");
        var folder = ProjectSetupService.baseDir(getModule()).resolve("first-run-tests");
        AtomicReference<Notification> result = new AtomicReference<>();
        getProject().getMessageBus().connect(getTestRootDisposable())
                .subscribe(Notifications.TOPIC, new Notifications() {
                    @Override public void notify(Notification notification) {
                        if ("EvoSuite".equals(notification.getGroupId())) result.set(notification);
                    }
                });
        List<String> questions = new ArrayList<>();
        var previous = TestDialogManager.setTestDialog(message -> { questions.add(message); return 0; });
        try {
            GenerationService.getInstance().submit(getProject(),
                    selection, TargetCollector.Mode.CLASSES);
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (result.get() == null && System.nanoTime() < until) {
                UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10);
            }
            assertNotNull("Configuration failure must be visible without reading console output", result.get());
            assertEquals(NotificationType.WARNING, result.get().getType());
            assertTrue(result.get().getActions().stream().anyMatch(a -> "EvoSuite settings".equals(a.getTemplatePresentation().getText())));
            assertTrue(questions.toString(), questions.stream().noneMatch(q -> q.contains("test dependencies")));
            assertFalse("Invalid JDK must not create the generated test root", Files.exists(folder));
        } finally { TestDialogManager.setTestDialog(previous); }
    }
}
