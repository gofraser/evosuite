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
package org.evosuite.intellij.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import org.evosuite.ide.GenerationTarget;

import java.util.*;

/** PSI/VFS selection resolver. Must run in a read action; no PSI is retained by a launch. */
public final class TargetCollector {
    public enum Mode {
        CLASSES,
        METHODS,
        EXTEND,
        COVERAGE
    }

    public record Selection(
            List<PsiElement> elements, List<VirtualFile> files, List<Module> modules) {}

    public record TestClass(Module module, String name, VirtualFile root) {}

    public record Collected(Map<Module, List<GenerationTarget>> targets, List<TestClass> tests) {}

    private TargetCollector() {}

    public static Selection snapshot(AnActionEvent event) {
        PsiElement[] array = event.getData(LangDataKeys.PSI_ELEMENT_ARRAY);
        List<PsiElement> elements =
                array == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(array));
        if (elements.isEmpty()) {
            var editor = event.getData(CommonDataKeys.EDITOR);
            PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
            if (editor != null && file != null) {
                PsiElement at =
                        file.findElementAt(
                                Math.min(
                                        editor.getCaretModel().getOffset(),
                                        Math.max(0, file.getTextLength() - 1)));
                PsiMethod method = PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
                elements.add(method != null ? method : file);
            } else {
                PsiElement element = event.getData(CommonDataKeys.PSI_ELEMENT);
                if (element != null) elements.add(element);
            }
        }
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        Module[] modules = event.getData(LangDataKeys.MODULE_CONTEXT_ARRAY);
        if (modules == null) {
            Module module = event.getData(LangDataKeys.MODULE_CONTEXT);
            modules = module == null ? new Module[0] : new Module[] {module};
        }
        return new Selection(
                List.copyOf(elements),
                files == null ? List.of() : List.of(files),
                List.of(modules));
    }

    public static boolean eligibleClass(Project project, PsiClass type, Mode mode) {
        if (type == null
                || type.getContainingClass() != null
                || type.getQualifiedName() == null
                || type.isInterface()
                || type.isAnnotationType()) return false;
        var file = type.getContainingFile().getVirtualFile();
        var index = ProjectFileIndex.getInstance(project);
        if (file == null || !index.isInSourceContent(file)) return false;
        if ((mode == Mode.EXTEND || mode == Mode.COVERAGE))
            return index.isInTestSourceContent(file)
                    && !type.hasModifierProperty(PsiModifier.ABSTRACT)
                    && !type.getName().endsWith("_scaffolding")
                    && file.getNameWithoutExtension().equals(type.getName());
        return !index.isInTestSourceContent(file) && !generated(type);
    }

    public static boolean hasEligibleTarget(Project project, Selection selection, Mode mode) {
        var index = ProjectFileIndex.getInstance(project);
        if (mode != Mode.METHODS)
            for (var module : selection.modules())
                if (java.util.Arrays.stream(
                                com.intellij.openapi.roots.ModuleRootManager.getInstance(module)
                                        .getSourceRoots())
                        .anyMatch(
                                root ->
                                        (mode == Mode.EXTEND || mode == Mode.COVERAGE)
                                                == index.isInTestSourceContent(root))) return true;
        for (var element : selection.elements()) {
            if (element instanceof PsiPackage && mode == Mode.CLASSES) return true;
            if (element instanceof PsiDirectory dir
                    && index.isInSourceContent(dir.getVirtualFile())
                    && ((mode == Mode.EXTEND || mode == Mode.COVERAGE))
                            == index.isInTestSourceContent(dir.getVirtualFile()))
                return mode != Mode.METHODS;
            if (mode == Mode.METHODS) {
                PsiMethod method =
                        element instanceof PsiMethod m
                                ? m
                                : PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
                if (method != null
                        && MethodRules.whyNotTestable(method) == null
                        && eligibleClass(project, method.getContainingClass(), mode)) return true;
            } else if (element instanceof PsiJavaFile file) {
                for (var type : file.getClasses())
                    if (eligibleClass(project, type, mode)) return true;
            } else {
                PsiClass type =
                        element instanceof PsiClass c
                                ? c
                                : PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
                if (eligibleClass(project, type, mode)) return true;
            }
        }
        if (selection.elements().isEmpty() && mode != Mode.METHODS)
            for (var file : selection.files()) {
                if (!index.isInSourceContent(file)
                        || ((mode == Mode.EXTEND || mode == Mode.COVERAGE))
                                != index.isInTestSourceContent(file)) continue;
                if (file.isDirectory()) return true;
                if (PsiManager.getInstance(project).findFile(file) instanceof PsiJavaFile java)
                    for (var type : java.getClasses())
                        if (eligibleClass(project, type, mode)) return true;
            }
        return false;
    }

    public static Collected collect(Project project, Selection selected, Mode mode) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        Map<Module, LinkedHashMap<String, GenerationTarget>> byModule = new LinkedHashMap<>();
        Map<String, TestClass> tests = new LinkedHashMap<>();
        java.util.function.Consumer<PsiClass> accept =
                type -> {
                    if (!eligibleClass(project, type, mode)) return;
                    VirtualFile file = type.getContainingFile().getVirtualFile();
                    if (file == null
                            || !index.isInSourceContent(file)
                            || type.getContainingClass() != null
                            || type.getQualifiedName() == null
                            || type.isInterface()
                            || type.isAnnotationType()) return;
                    Module module = index.getModuleForFile(file);
                    if (module == null || module.isDisposed()) return;
                    if ((mode == Mode.EXTEND || mode == Mode.COVERAGE)) {
                        if (index.isInTestSourceContent(file)
                                && !type.hasModifierProperty(PsiModifier.ABSTRACT)
                                && !type.getName().endsWith("_scaffolding")
                                && file.getNameWithoutExtension().equals(type.getName())) {
                            VirtualFile root = index.getSourceRootForFile(file);
                            if (root != null)
                                tests.put(
                                        module.getName() + ":" + type.getQualifiedName(),
                                        new TestClass(module, type.getQualifiedName(), root));
                        }
                    } else if (!index.isInTestSourceContent(file) && !generated(type)) {
                        byModule.computeIfAbsent(module, ignored -> new LinkedHashMap<>())
                                .putIfAbsent(
                                        type.getQualifiedName(),
                                        GenerationTarget.wholeClass(type.getQualifiedName()));
                    }
                };
        Set<VirtualFile> visited = new HashSet<>();
        java.util.function.Consumer<VirtualFile> visit =
                root ->
                        VfsUtilCore.iterateChildrenRecursively(
                                root,
                                file ->
                                        !index.isExcluded(file)
                                                && ((mode == Mode.EXTEND || mode == Mode.COVERAGE)
                                                        || !index.isInTestSourceContent(file))
                                                && (selected.modules().isEmpty()
                                                        || index.getModuleForFile(file) == null
                                                        || selected.modules()
                                                                .contains(
                                                                        index.getModuleForFile(
                                                                                file))),
                                file -> {
                                    com.intellij.openapi.progress.ProgressManager.checkCanceled();
                                    if (!file.isDirectory()
                                            && file.getExtension() != null
                                            && file.getExtension().equals("java")
                                            && visited.add(file)) {
                                        PsiFile psi =
                                                PsiManager.getInstance(project).findFile(file);
                                        if (psi instanceof PsiJavaFile java)
                                            for (PsiClass type : java.getClasses())
                                                accept.accept(type);
                                    }
                                    return true;
                                });
        for (PsiElement element : selected.elements()) {
            if (!element.isValid()) continue;
            if (mode == Mode.METHODS) {
                PsiMethod method =
                        element instanceof PsiMethod m
                                ? m
                                : PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
                if (method == null || MethodRules.whyNotTestable(method) != null
                        || !eligibleClass(project, method.getContainingClass(), mode)) continue;
                PsiClass type = method.getContainingClass();
                VirtualFile file = type.getContainingFile().getVirtualFile();
                if (file == null
                        || !index.isInSourceContent(file)
                        || index.isInTestSourceContent(file)
                        || generated(type)) continue;
                Module module = index.getModuleForFile(file);
                if (module == null) continue;
                var targets = byModule.computeIfAbsent(module, ignored -> new LinkedHashMap<>());
                var old = targets.get(type.getQualifiedName());
                Set<String> descriptors =
                        new LinkedHashSet<>(old == null ? List.of() : old.methods());
                descriptors.add(MethodRules.descriptor(method));
                targets.put(
                        type.getQualifiedName(),
                        new GenerationTarget(type.getQualifiedName(), List.copyOf(descriptors)));
            } else if (element instanceof PsiPackage pkg) {
                for (PsiDirectory dir : pkg.getDirectories()) visit.accept(dir.getVirtualFile());
            } else if (element instanceof PsiDirectory dir) visit.accept(dir.getVirtualFile());
            else if (element instanceof PsiJavaFile file)
                for (PsiClass type : file.getClasses()) accept.accept(type);
            else {
                PsiClass type =
                        element instanceof PsiClass cls
                                ? cls
                                : PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
                if (type != null) {
                    while (type.getContainingClass() != null) type = type.getContainingClass();
                    accept.accept(type);
                }
            }
        }
        if (mode != Mode.METHODS) {
            for (VirtualFile file : selected.files())
                if (selected.elements().isEmpty()) visit.accept(file);
            for (Module module : selected.modules())
                for (VirtualFile root :
                        ModuleRootManager.getInstance(module).getSourceRoots((mode == Mode.EXTEND || mode == Mode.COVERAGE))) {
                    // Do not collect roots belonging to nested modules when a module was selected.
                    if (index.getModuleForFile(root) == module) visit.accept(root);
                }
        }
        Map<Module, List<GenerationTarget>> targets = new LinkedHashMap<>();
        byModule.forEach((module, classes) -> targets.put(module, List.copyOf(classes.values())));
        return new Collected(targets, List.copyOf(tests.values()));
    }

    public static boolean generated(PsiClass type) {
        return type.getName() != null && type.getName().endsWith("_scaffolding")
                || type.hasAnnotation("org.evosuite.runtime.EvoRunnerParameters");
    }
}
