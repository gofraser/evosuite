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
package org.evosuite.eclipse.handlers;

import java.util.List;

import org.evosuite.eclipse.launch.SuiteAction;

/**
 * What to ask when some of the classes to generate tests for already have test suites: the
 * wording and the preselected answer of the dialog, apart from the dialog itself. One question
 * covers all classes of a request, however many there are.
 */
public final class ExistingSuitesQuestion {

    /**
     * What the new suites cover. Suites for part of a class would lose the tests of the rest
     * when replacing the existing ones, so adding is preselected for them.
     */
    public enum Scope {
        /** Whole classes. */
        CLASSES(null),
        /** Selected methods of each class. */
        METHODS("The new tests only exercise the selected methods, so replacing would also drop the tests of "
                + "the rest of the class."),
        /** What existing tests of each class leave uncovered. */
        EXTENSION("The new tests only cover what the existing tests leave uncovered, so replacing would also "
                + "drop the suite's other tests.");

        /** Why replacing is a bad idea, or {@code null} if it is not. */
        final String partialNote;

        Scope(String partialNote) {
            this.partialNote = partialNote;
        }
    }

    /** The dialog's buttons; the first three stand for {@link #ACTIONS}, the last cancels. */
    public static final List<String> BUTTONS = List.of("&Replace", "&Add New Tests", "C&ompare", "Cancel");
    private static final List<SuiteAction> ACTIONS = List.of(SuiteAction.REPLACE, SuiteAction.ADD,
            SuiteAction.COMPARE);
    /** How many class names the message lists before it abbreviates. */
    static final int NAMES_SHOWN = 5;

    public final String title;
    public final String message;
    /** The preselected answer. */
    public final SuiteAction defaultAction;

    private ExistingSuitesQuestion(String title, String message, SuiteAction defaultAction) {
        this.title = title;
        this.message = message;
        this.defaultAction = defaultAction;
    }

    /**
     * @param withSuites  the simple names of the classes that have a suite, never empty
     * @param classCount  how many classes the request covers in all
     * @param testFolder  the folder the suites are in, or {@code null} if the projects differ
     * @param scope       what the new suites cover
     */
    public static ExistingSuitesQuestion of(List<String> withSuites, int classCount, String testFolder,
                                            Scope scope) {
        if (withSuites.isEmpty()) {
            throw new IllegalArgumentException("no existing suites to ask about");
        }
        boolean one = withSuites.size() == 1;
        String where = testFolder == null ? "" : " in " + testFolder;
        StringBuilder message = new StringBuilder();
        if (one && classCount == 1) {
            message.append(withSuites.get(0)).append(" already has a test suite").append(where).append('.');
        } else if (one) {
            message.append("Of the ").append(classCount).append(" classes, ").append(withSuites.get(0))
                    .append(" already has a test suite").append(where).append('.');
        } else {
            message.append(withSuites.size() == classCount ? "All " + classCount : withSuites.size() + " of the "
                    + classCount).append(" classes already have test suites").append(where).append(": ")
                    .append(names(withSuites)).append('.');
        }
        message.append("\n\n");
        message.append("Replace: overwrite ").append(one ? "it" : "them").append(" with the new suite")
                .append(one ? "" : "s").append(".\n");
        message.append("Add New Tests: keep ").append(one ? "its" : "their")
                .append(" tests and add the new ones that differ from them.\n");
        message.append("Compare: show ").append(one ? "the existing suite and the new one" : "each existing suite "
                + "and its new one").append(" side by side, to copy over what you want and save.");
        if (scope.partialNote != null) {
            message.append("\n\n").append(scope.partialNote);
        }
        message.append("\n\nUntil generation has finished, ").append(one ? "the suite stays" : "the suites stay")
                .append(" as ").append(one ? "it is" : "they are").append('.');
        if (!one || classCount > 1) {
            message.append(" The choice applies to ").append(one ? "it" : "all " + withSuites.size());
            message.append(classCount > withSuites.size() ? "; the other classes get new suites." : ".");
        }
        String title = one ? "Existing Test Suite" : "Existing Test Suites";
        return new ExistingSuitesQuestion(title, message.toString(),
                scope.partialNote != null ? SuiteAction.ADD : SuiteAction.REPLACE);
    }

    /** The index of the preselected button. */
    public int defaultButton() {
        return ACTIONS.indexOf(defaultAction);
    }

    /** The answer a button stands for; {@code null} for Cancel or a closed dialog. */
    public static SuiteAction action(int button) {
        return button >= 0 && button < ACTIONS.size() ? ACTIONS.get(button) : null;
    }

    private static String names(List<String> names) {
        if (names.size() <= NAMES_SHOWN) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, NAMES_SHOWN - 1)) + " and " + (names.size() - NAMES_SHOWN + 1)
                + " more";
    }
}
