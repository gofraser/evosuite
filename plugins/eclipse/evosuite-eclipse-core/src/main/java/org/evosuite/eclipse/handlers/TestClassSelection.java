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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

/**
 * The test classes that Extend Tests with EvoSuite applies to, and the class each one tests.
 *
 * <p>A test class qualifies if it is the primary, non-abstract class of a file in a test source
 * folder:
 * a hand-written test such as {@code FooTest} or an EvoSuite suite such as {@code Foo_ESTest}.
 * Its class under test follows from the naming conventions, {@code FooTest}, {@code FooTests},
 * {@code FooTestCase}, {@code FooIT}, {@code FooITCase}, {@code TestFoo} and the configured
 * suffix of EvoSuite suites, and must be a class in a main source folder in the same package.
 * Where the name leaves the class open, the user chooses it.
 */
public final class TestClassSelection {

    /** Name endings of test classes, longest first, after the configured suite suffix. */
    private static final List<String> SUFFIXES = List.of("TestCase", "ITCase", "Tests", "Test", "IT");
    private static final String PREFIX = "Test";

    private TestClassSelection() {
    }

    /**
     * The class a test class tests, as far as its name tells.
     *
     * @param classUnderTest the class, if exactly one class in a main source folder matches a
     *                       naming convention; otherwise {@code null}
     * @param candidates     all classes that match a naming convention, so more than one when the
     *                       name is ambiguous
     * @param guess          what to search for when asking the user: the name of a candidate, of
     *                       a class whose name begins the test's name (such as {@code Vault} for
     *                       {@code VaultEdgeCasesTest}), or the name without its test suffix
     */
    public record Inference(IType classUnderTest, List<IType> candidates, String guess) {
    }

    /** The top-level test classes among the selected elements, without duplicates. */
    public static List<IType> collect(Iterable<?> selection) throws JavaModelException {
        Set<IType> tests = new LinkedHashSet<>();
        for (Object item : selection) {
            IJavaElement element = Adapters.adapt(item, IJavaElement.class);
            if (element == null || !element.exists()) {
                continue;
            }
            if (element instanceof ICompilationUnit unit) {
                IType type = unit.findPrimaryType();
                if (type != null && isTestClass(type)) {
                    tests.add(type);
                }
            } else if (element instanceof IType type && isTestClass(type)) {
                tests.add(type);
            }
        }
        return new ArrayList<>(tests);
    }

    /** Whether {@code element}, a compilation unit or a type, is or contains a test class. */
    public static boolean containsTestClass(IJavaElement element) throws JavaModelException {
        if (element instanceof ICompilationUnit unit && unit.exists()) {
            IType type = unit.findPrimaryType();
            return type != null && isTestClass(type);
        }
        return element instanceof IType type && isTestClass(type);
    }

    /**
     * Whether Extend Tests applies to {@code type}: the primary, non-abstract source class of a
     * compilation unit in a test source folder, other than EvoSuite's scaffolding. Other top-level
     * classes in the file, such as a helper {@code FakeRepo} in {@code FooTest.java}, are not
     * test classes: EvoSuite reads a test class from the file named after it.
     */
    public static boolean isTestClass(IType type) throws JavaModelException {
        if (!type.exists() || type.isBinary() || type.getDeclaringType() != null || !type.isClass()
                || !type.equals(type.getCompilationUnit().findPrimaryType())
                || Flags.isAbstract(type.getFlags()) || type.getElementName().endsWith("_scaffolding")) {
            return false;
        }
        IPackageFragmentRoot root = (IPackageFragmentRoot) type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (root == null || root.getKind() != IPackageFragmentRoot.K_SOURCE) {
            return false;
        }
        IClasspathEntry entry = root.getRawClasspathEntry();
        return entry != null && entry.isTest();
    }

    /**
     * The class {@code test} tests, from the naming conventions.
     *
     * @param suiteSuffix the suffix of EvoSuite's suites, such as {@code _ESTest}
     */
    public static Inference infer(IType test, String suiteSuffix) throws JavaModelException {
        String packageName = test.getPackageFragment().getElementName();
        List<String> stems = stems(test.getElementName(), suiteSuffix);
        List<IPackageFragment> packages = mainSourcePackages(test.getJavaProject(), packageName);
        List<IType> candidates = new ArrayList<>();
        for (String stem : stems) {
            for (IPackageFragment pkg : packages) {
                IType type = pkg.getCompilationUnit(stem + ".java").getType(stem);
                if (isClassUnderTest(type) && !candidates.contains(type)) {
                    candidates.add(type);
                }
            }
        }
        if (candidates.size() == 1) {
            return new Inference(candidates.get(0), candidates, candidates.get(0).getElementName());
        }
        if (!candidates.isEmpty()) {
            return new Inference(null, candidates, candidates.get(0).getElementName());
        }
        // A class whose name begins the test's name, such as Vault for VaultEdgeCasesTest: likely,
        // but only a guess, so the user confirms it.
        List<String> names = new ArrayList<>(stems);
        names.add(test.getElementName());
        String guess = null;
        for (IPackageFragment pkg : packages) {
            for (ICompilationUnit unit : pkg.getCompilationUnits()) {
                for (IType type : unit.getTypes()) {
                    String name = type.getElementName();
                    boolean begins = names.stream().anyMatch(n -> n.length() > name.length() && n.startsWith(name));
                    if (begins && isClassUnderTest(type) && (guess == null || name.length() > guess.length())) {
                        guess = name;
                    }
                }
            }
        }
        if (guess == null) {
            guess = stems.isEmpty() ? "" : stems.get(0);
        }
        return new Inference(null, List.of(), guess);
    }

    /**
     * The names the test's name stands for: {@code Foo} for {@code FooTest}, {@code TestFoo} or
     * {@code Foo_ESTest}. Ordered from the most specific convention on, which is the suite
     * suffix; empty if the name follows none.
     */
    static List<String> stems(String testName, String suiteSuffix) {
        List<String> suffixes = new ArrayList<>();
        if (!suiteSuffix.isEmpty()) {
            suffixes.add(suiteSuffix);
        }
        suffixes.addAll(SUFFIXES);
        List<String> names = new ArrayList<>(List.of(testName));
        if (testName.startsWith(PREFIX) && testName.length() > PREFIX.length()
                && Character.isUpperCase(testName.charAt(PREFIX.length()))) {
            names.add(testName.substring(PREFIX.length()));
        }
        Set<String> stems = new LinkedHashSet<>();
        for (String name : names) {
            if (!name.equals(testName)) {
                stems.add(name);
            }
            for (String suffix : suffixes) {
                if (name.endsWith(suffix) && name.length() > suffix.length()) {
                    stems.add(name.substring(0, name.length() - suffix.length()));
                }
            }
        }
        return new ArrayList<>(stems);
    }

    /** Whether tests can be generated for {@code type}: a top-level class in a main source folder. */
    static boolean isClassUnderTest(IType type) throws JavaModelException {
        if (!type.exists() || type.isBinary() || type.getDeclaringType() != null || type.isInterface()
                || type.isAnnotation()) {
            return false;
        }
        IPackageFragmentRoot root = (IPackageFragmentRoot) type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        return root != null && TargetSelection.isMainSource(root);
    }

    /**
     * The main source folders of {@code project} and of the projects it requires: where a class
     * under test can be.
     */
    public static List<IPackageFragmentRoot> mainSourceRoots(IJavaProject project) throws JavaModelException {
        List<IPackageFragmentRoot> roots = new ArrayList<>();
        for (IPackageFragmentRoot root : project.getAllPackageFragmentRoots()) {
            if (TargetSelection.isMainSource(root)) {
                roots.add(root);
            }
        }
        return roots;
    }

    private static List<IPackageFragment> mainSourcePackages(IJavaProject project, String name)
            throws JavaModelException {
        List<IPackageFragment> packages = new ArrayList<>();
        for (IPackageFragmentRoot root : mainSourceRoots(project)) {
            IPackageFragment pkg = root.getPackageFragment(name);
            if (pkg.exists()) {
                packages.add(pkg);
            }
        }
        return packages;
    }
}
