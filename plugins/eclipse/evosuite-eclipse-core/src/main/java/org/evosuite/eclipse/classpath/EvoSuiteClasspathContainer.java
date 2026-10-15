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
package org.evosuite.eclipse.classpath;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.evosuite.eclipse.EvoSuiteJars;

/**
 * "EvoSuite Runtime": the standalone runtime that generated tests run against (the JUnit
 * extension or runner, the mocks and the scaffolding support). It is the jar a Maven or Gradle
 * build gets as {@code org.evosuite:evosuite-standalone-runtime}, not the whole test generator.
 */
public class EvoSuiteClasspathContainer implements IClasspathContainer {

    /** Unchanged from earlier versions of the plug-in, so their build paths keep resolving. */
    public static final IPath PATH = IPath.fromOSString("org.evosuite.eclipse.classpathContainerInitializer");

    private final IClasspathEntry[] entries;

    public EvoSuiteClasspathContainer() throws CoreException {
        IPath jar = IPath.fromFile(EvoSuiteJars.runtime());
        entries = new IClasspathEntry[]{JavaCore.newLibraryEntry(jar, null, null)};
    }

    @Override
    public IClasspathEntry[] getClasspathEntries() {
        return entries;
    }

    @Override
    public String getDescription() {
        return "EvoSuite Runtime";
    }

    @Override
    public int getKind() {
        return K_APPLICATION;
    }

    @Override
    public IPath getPath() {
        return PATH;
    }
}
