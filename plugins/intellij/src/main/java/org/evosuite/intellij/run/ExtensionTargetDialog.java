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
package org.evosuite.intellij.run;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.search.GlobalSearchScope;
import org.evosuite.intellij.actions.TargetCollector;
import org.evosuite.intellij.actions.TestClassInference;

public final class ExtensionTargetDialog {
    private ExtensionTargetDialog() {}

    public static InputValidatorEx validator(Project project, TargetCollector.TestClass test) {
        return new InputValidatorEx() {
            @Override public String getErrorText(String input) {
                if (input.isBlank()) return "Enter the fully qualified production class name.";
                boolean valid = IdeThread.smartRead(project, () -> TestClassInference.isCut(project,
                        JavaPsiFacade.getInstance(project).findClass(input.trim(),
                                GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(test.module(), false))));
                return valid ? null : "No top-level production class named " + input.trim()
                        + " is available in module " + test.module().getName() + ".";
            }
            @Override public boolean checkInput(String input) { return getErrorText(input) == null; }
            @Override public boolean canClose(String input) { return checkInput(input); }
        };
    }

    public static String choose(Project project, TargetCollector.TestClass test,
            TestClassInference.Inference inference) {
        String cut;
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode()) {
            cut = IdeThread.edt(() -> Messages.showInputDialog(project,
                GenerationDialogs.message(
                                                    "Production class for "
                                                            + test.name() + ". Additional tests go to the"
                                                            + " EvoSuite output suite. Cancel stops"
                                                            + " the entire request."),
                "Extend Tests with EvoSuite", Messages.getQuestionIcon(), inference.guess(),
                validator(project, test)));
        } else
            cut =
                    IdeThread.edt(
                            () -> {
                                var scope =
                                        GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(
                                                test.module(), false);
                                var initial =
                                        JavaPsiFacade.getInstance(project)
                                                .findClass(inference.guess(), scope);
                                var chooser =
                                        com.intellij.ide.util.TreeClassChooserFactory.getInstance(
                                                        project)
                                                .createNoInnerClassesScopeChooser(
                                                        "Production class for "
                                                                + test.name()
                                                                + " (Cancel stops the request)",
                                                        scope,
                                                        type ->
                                                                TestClassInference.isCut(
                                                                        project, type),
                                                        initial);
                                chooser.showDialog();
                                var selected = chooser.getSelected();
                                return selected == null ? null : selected.getQualifiedName();
                            });
        if (cut == null) throw new ProcessCanceledException();
        return cut.trim();
    }
}
