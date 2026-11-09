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
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import org.evosuite.ide.GenerationTarget;

import java.util.*;
import java.util.function.Consumer;

/** PSI/VFS selection resolver. Must run in a read action; no raw PSI is retained by a launch. */
public final class TargetCollector {
    public enum Mode {
        CLASSES,
        METHODS,
        EXTEND,
        COVERAGE
    }

    /** Stable handles survive document saves and PSI reparsing before background collection. */
    public static final class Selection {
        private final List<SmartPsiElementPointer<PsiElement>> pointers;
        private final List<VirtualFile> files;
        private final List<Module> modules;

        public Selection(List<PsiElement> elements, List<VirtualFile> files, List<Module> modules) {
            pointers =
                    elements.stream()
                            .filter(PsiElement::isValid)
                            .map(
                                    element ->
                                            SmartPointerManager.getInstance(element.getProject())
                                                    .createSmartPsiElementPointer(element))
                            .toList();
            this.files = List.copyOf(files);
            this.modules = List.copyOf(modules);
        }

        public List<PsiElement> elements() {
            return pointers.stream()
                    .map(SmartPsiElementPointer::getElement)
                    .filter(Objects::nonNull)
                    .toList();
        }

        public List<VirtualFile> files() {
            return files;
        }

        public List<Module> modules() {
            return modules;
        }
    }

    public record TestClass(Module module, String name, VirtualFile root, VirtualFile file) {
        public TestClass(Module module, String name, VirtualFile root) {
            this(module, name, root, null);
        }
    }

    public record Collected(Map<Module, List<GenerationTarget>> targets, List<TestClass> tests) {}

    private TargetCollector() {}

    public static Selection snapshot(AnActionEvent event) {
        return snapshot(event, Mode.METHODS);
    }

    public static Selection snapshot(AnActionEvent event, Mode mode) {
        PsiElement[] array = event.getData(LangDataKeys.PSI_ELEMENT_ARRAY);
        List<PsiElement> elements =
                array == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(array));
        var editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        boolean editorContext =
                editor != null
                        && file != null
                        && !ActionPlaces.PROJECT_VIEW_POPUP.equals(event.getPlace())
                        && !event.getPlace().contains("Structure");
        if (editorContext) {
            elements.clear();
            if (mode == Mode.METHODS) {
                PsiElement at =
                        file.findElementAt(
                                Math.min(
                                        editor.getCaretModel().getOffset(),
                                        Math.max(0, file.getTextLength() - 1)));
                PsiMethod method = PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
                elements.add(method != null ? method : file);
            } else elements.add(file);
        } else if (elements.isEmpty()) {
            PsiElement element = event.getData(CommonDataKeys.PSI_ELEMENT);
            if (element != null) elements.add(element);
        }
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        Module[] modules = event.getData(LangDataKeys.MODULE_CONTEXT_ARRAY);
        if (modules == null) {
            Module module = event.getData(LangDataKeys.MODULE_CONTEXT);
            modules = module == null ? new Module[0] : new Module[] {module};
        }
        if (editorContext) modules = new Module[0];
        return new Selection(
                elements,
                files == null ? List.of() : List.of(files),
                List.of(modules));
    }

    public static boolean eligibleClass(Project project, PsiClass type, Mode mode) {
        if (type == null
                || type.getContainingClass() != null
                || type.getQualifiedName() == null
                || type.isInterface()
                || type.isAnnotationType()) return false;
        PsiFile source = type.getContainingFile();
        VirtualFile file = source == null ? null : source.getVirtualFile();
        var index = ProjectFileIndex.getInstance(project);
        if (file == null || !file.isInLocalFileSystem() || !index.isInSourceContent(file)) return false;
        if (usesExistingTests(mode))
            return index.isInTestSourceContent(file)
                    && !type.hasModifierProperty(PsiModifier.ABSTRACT)
                    && !type.getName().endsWith("_scaffolding")
                    && file.getNameWithoutExtension().equals(type.getName());
        return !index.isInTestSourceContent(file) && !generated(type);
    }

    public static boolean hasEligibleTarget(Project project, Selection selection, Mode mode) {
        var index = ProjectFileIndex.getInstance(project);
        boolean testSources = usesExistingTests(mode);
        if (mode != Mode.METHODS && selection.modules().stream()
                .filter(module -> !module.isDisposed())
                .flatMap(module -> Arrays.stream(ModuleRootManager.getInstance(module).getSourceRoots()))
                .anyMatch(root -> index.isInTestSourceContent(root) == testSources)) return true;

        List<PsiElement> elements = selection.elements();
        for (var element : elements) {
            if (mode == Mode.METHODS) {
                PsiMethod method = selectedMethod(element);
                if (method != null
                        && eligibleClass(project, method.getContainingClass(), mode)
                        && MethodRules.whyNotTestable(method) == null
                        && MethodRules.descriptorOrNull(method) != null) return true;
            } else if (element instanceof PsiPackage pkg) {
                if (Arrays.stream(pkg.getDirectories()).anyMatch(dir ->
                        eligibleFile(index, dir.getVirtualFile(), testSources))) return true;
            } else if (element instanceof PsiDirectory dir) {
                if (eligibleFile(index, dir.getVirtualFile(), testSources)) return true;
            } else if (element instanceof PsiJavaFile file) {
                if (Arrays.stream(file.getClasses()).anyMatch(type -> eligibleClass(project, type, mode)))
                    return true;
            } else if (eligibleClass(project, selectedClass(element), mode)) {
                return true;
            }
        }
        if (elements.isEmpty() && mode != Mode.METHODS)
            for (var file : selection.files()) {
                if (!eligibleFile(index, file, testSources)) continue;
                if (file.isDirectory()) return true;
                if (PsiManager.getInstance(project).findFile(file) instanceof PsiJavaFile java
                        && Arrays.stream(java.getClasses()).anyMatch(type -> eligibleClass(project, type, mode)))
                    return true;
            }
        return false;
    }

    public static Collected collect(Project project, Selection selected, Mode mode) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        Map<Module, LinkedHashMap<String, GenerationTarget>> byModule = new LinkedHashMap<>();
        Set<TestClass> tests = new LinkedHashSet<>();
        boolean testSources = usesExistingTests(mode);
        Consumer<PsiClass> accept = type -> {
            if (!eligibleClass(project, type, mode)) return;
            VirtualFile file = type.getContainingFile().getVirtualFile();
            Module module = index.getModuleForFile(file);
            if (module == null || module.isDisposed()) return;
            String name = type.getQualifiedName();
            if (testSources) {
                VirtualFile root = index.getSourceRootForFile(file);
                if (root != null) tests.add(new TestClass(module, name, root, file));
            } else {
                byModule.computeIfAbsent(module, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(name, GenerationTarget.wholeClass(name));
            }
        };
        Set<VirtualFile> visited = new HashSet<>();
        Set<Module> selectedModules = new HashSet<>(selected.modules());
        PsiManager psi = PsiManager.getInstance(project);
        Consumer<VirtualFile> visit = root -> VfsUtilCore.iterateChildrenRecursively(
                root,
                file -> {
                    if (!file.isValid() || index.isExcluded(file)
                            || !testSources && index.isInTestSourceContent(file)) return false;
                    Module owner = index.getModuleForFile(file);
                    return selectedModules.isEmpty() || owner == null || selectedModules.contains(owner);
                },
                file -> {
                    ProgressManager.checkCanceled();
                    if (!file.isDirectory() && "java".equals(file.getExtension()) && visited.add(file)
                            && psi.findFile(file) instanceof PsiJavaFile java)
                        for (PsiClass type : java.getClasses()) accept.accept(type);
                    return true;
                });
        List<PsiElement> elements = selected.elements();
        for (PsiElement element : elements) {
            if (mode == Mode.METHODS) {
                PsiMethod method = selectedMethod(element);
                if (method == null || !eligibleClass(project, method.getContainingClass(), mode)
                        || MethodRules.whyNotTestable(method) != null) continue;
                PsiClass type = method.getContainingClass();
                Module module = index.getModuleForFile(type.getContainingFile().getVirtualFile());
                if (module == null || module.isDisposed()) continue;
                String descriptor = MethodRules.descriptorOrNull(method);
                if (descriptor == null) continue;
                var targets = byModule.computeIfAbsent(module, ignored -> new LinkedHashMap<>());
                var old = targets.get(type.getQualifiedName());
                Set<String> descriptors =
                        new LinkedHashSet<>(old == null ? List.of() : old.methods());
                descriptors.add(descriptor);
                targets.put(
                        type.getQualifiedName(),
                        new GenerationTarget(type.getQualifiedName(), List.copyOf(descriptors)));
            } else if (element instanceof PsiPackage pkg) {
                for (PsiDirectory dir : pkg.getDirectories()) visit.accept(dir.getVirtualFile());
            } else if (element instanceof PsiDirectory dir) visit.accept(dir.getVirtualFile());
            else if (element instanceof PsiJavaFile file)
                for (PsiClass type : file.getClasses()) accept.accept(type);
            else accept.accept(selectedClass(element));
        }
        if (mode != Mode.METHODS) {
            if (elements.isEmpty()) selected.files().forEach(visit);
            for (Module module : selected.modules()) {
                if (module.isDisposed()) continue;
                for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots(testSources)) {
                    // Do not collect roots belonging to nested modules when a module was selected.
                    if (index.getModuleForFile(root) == module) visit.accept(root);
                }
            }
        }
        Map<Module, List<GenerationTarget>> targets = new LinkedHashMap<>();
        byModule.forEach((module, classes) -> targets.put(module, List.copyOf(classes.values())));
        return new Collected(targets, List.copyOf(tests));
    }

    private static boolean usesExistingTests(Mode mode) {
        return mode == Mode.EXTEND || mode == Mode.COVERAGE;
    }

    private static boolean eligibleFile(ProjectFileIndex index, VirtualFile file, boolean testSources) {
        return file.isValid() && index.isInSourceContent(file)
                && index.isInTestSourceContent(file) == testSources;
    }

    private static PsiMethod selectedMethod(PsiElement element) {
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
    }

    private static PsiClass selectedClass(PsiElement element) {
        PsiClass type = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        while (type != null && type.getContainingClass() != null) type = type.getContainingClass();
        return type;
    }

    public static boolean generated(PsiClass type) {
        return type.getName() != null && type.getName().endsWith("_scaffolding")
                || type.hasAnnotation("org.evosuite.runtime.EvoRunnerParameters");
    }
}
