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

/** Keep class-selection mistakes in the prompt instead of failing a generation run. */
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
        String cut = IdeThread.edt(() -> Messages.showInputDialog(project,
                GenerationDialogs.message("Production class to extend coverage for using " + test.name() + " (module "
                        + test.module().getName() + "):\n"
                        + (inference.candidates().size() > 1
                                ? "Possible classes: " + String.join(", ", inference.candidates()) + "\n" : "")
                        + "Enter a fully qualified class name. Additional tests go to the configured "
                        + "EvoSuite output suite.\nCancel stops the entire request."),
                "Extend Tests with EvoSuite", Messages.getQuestionIcon(), inference.guess(),
                validator(project, test)));
        if (cut == null) throw new ProcessCanceledException();
        return cut.trim();
    }
}
