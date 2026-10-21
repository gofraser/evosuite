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

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.vfs.*;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;

import org.evosuite.intellij.actions.TargetCollector;
import org.evosuite.intellij.run.ClasspathResolver;
import org.evosuite.intellij.setup.ProjectSetupService;

import java.nio.file.*;
import java.util.*;

public class SetupAndClasspathTest extends JavaCodeInsightFixtureTestCase {
    public void testRootRegistrationIsIdempotentAndSelectionSkipsTests() throws Exception {
        var file =
                myFixture.addFileToProject(
                        "generated/demo/VaultTest.java", "package demo; public class VaultTest {}");
        Path folder = file.getVirtualFile().getParent().getParent().toNioPath();
        ProjectSetupService.registerTestRoot(getModule(), folder);
        ProjectSetupService.registerTestRoot(getModule(), folder);
        assertTrue(
                ProjectFileIndex.getInstance(getProject())
                        .isInTestSourceContent(file.getVirtualFile()));
        long roots =
                Arrays.stream(ModuleRootManager.getInstance(getModule()).getContentEntries())
                        .flatMap(e -> Arrays.stream(e.getSourceFolders()))
                        .filter(f -> f.getUrl().equals(VfsUtilCore.pathToUrl(folder.toString())))
                        .count();
        assertEquals(1L, roots);
        var selected = new TargetCollector.Selection(List.of(file), List.of(), List.of());
        assertTrue(
                TargetCollector.collect(getProject(), selected, TargetCollector.Mode.CLASSES)
                        .targets()
                        .isEmpty());
        var tests =
                TargetCollector.collect(getProject(), selected, TargetCollector.Mode.EXTEND)
                        .tests();
        assertEquals(1, tests.size());
        assertEquals("demo.VaultTest", tests.getFirst().name());
    }

    public void testOrdinaryClasspathExcludesTestsAndTestLibraries() throws Exception {
        Path root = Path.of(myFixture.getTempDirPath());
        Path main = Files.createDirectories(root.resolve("main-output"));
        Path tests = Files.createDirectories(root.resolve("test-output"));
        Path junit = root.resolve("junit-4.13.2.jar"),
                runtime = root.resolve("evosuite-standalone-runtime.jar");
        for (Path jar : List.of(junit, runtime))
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {}
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root).refresh(false, true);
        WriteCommandAction.runWriteCommandAction(
                getProject(),
                () ->
                        ModuleRootModificationUtil.updateModel(
                                getModule(),
                                model -> {
                                    var compiler =
                                            model.getModuleExtension(CompilerModuleExtension.class);
                                    compiler.inheritCompilerOutputPath(false);
                                    compiler.setCompilerOutputPath(
                                            VfsUtilCore.pathToUrl(main.toString()));
                                    compiler.setCompilerOutputPathForTests(
                                            VfsUtilCore.pathToUrl(tests.toString()));
                                    for (Path jar : List.of(junit, runtime)) {
                                        var library =
                                                model.getModuleLibraryTable()
                                                        .createLibrary(
                                                                jar.getFileName().toString());
                                        var lib = library.getModifiableModel();
                                        lib.addRoot(
                                                VfsUtil.getUrlForLibraryRoot(jar.toFile()),
                                                OrderRootType.CLASSES);
                                        lib.commit();
                                        for (OrderEntry entry : model.getOrderEntries())
                                            if (entry instanceof LibraryOrderEntry order
                                                    && library.equals(order.getLibrary()))
                                                order.setScope(DependencyScope.TEST);
                                    }
                                }));
        var ordinary = ClasspathResolver.resolve(getModule(), false);
        assertTrue(ordinary.toString(), ordinary.contains(main.toString()));
        assertFalse(ordinary.toString(), ordinary.contains(tests.toString()));
        assertFalse(
                ordinary.toString(),
                ordinary.stream()
                        .anyMatch(
                                s ->
                                        s.contains("junit-4")
                                                || s.contains("evosuite-standalone-runtime")));
        var extension = ClasspathResolver.resolve(getModule(), true);
        assertTrue(extension.toString(), extension.contains(tests.toString()));
        assertTrue(extension.toString(), extension.contains(junit.toString()));
        assertFalse(extension.toString(), extension.contains(runtime.toString()));
    }
}
