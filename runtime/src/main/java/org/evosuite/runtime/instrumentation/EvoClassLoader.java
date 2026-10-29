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
package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.util.Inputs;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * An instrumenting class loader used in special cases in the generated JUnit tests
 * when Java Agent is not used.
 */
public class EvoClassLoader extends TransformingClassLoader {
    private final RuntimeInstrumentation instrumentation;
    private final Set<String> skipInstrumentationForPrefix = new HashSet<>();

    public EvoClassLoader() {
        this(new RuntimeInstrumentation());
    }

    public EvoClassLoader(RuntimeInstrumentation instrumentation) {
        super(EvoClassLoader.class.getClassLoader(), EvoClassLoader.class.getClassLoader());
        this.instrumentation = instrumentation;
    }

    /**
     * Skip instrumentation for classes with the given prefix.
     *
     * @param prefix the prefix of classes to skip instrumentation for.
     * @throws java.lang.IllegalArgumentException if prefix is null.
     */
    public void skipInstrumentation(String prefix) throws IllegalArgumentException {
        Inputs.checkNull(prefix);
        skipInstrumentationForPrefix.add(prefix);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        if ("<evosuite>".equals(name)) {
            throw new ClassNotFoundException();
        }
        return super.loadClass(name);
    }

    @Override
    protected boolean canInstrument(String name) {
        return RuntimeInstrumentation.checkIfCanInstrument(name);
    }

    @Override
    protected InputStream openClass(String name) {
        return getParent().getResourceAsStream(name.replace('.', '/') + ".class");
    }

    @Override
    protected byte[] transform(String name, InputStream bytes) throws IOException {
        boolean shouldSkip = skipInstrumentationForPrefix.stream().anyMatch(name::startsWith);
        return instrumentation.transformBytes(this, name.replace('.', '/'), new ClassReader(bytes), shouldSkip);
    }

    /**
     * Returns the instrumentation instance.
     *
     * @return the instrumentation instance.
     */
    public RuntimeInstrumentation getInstrumentation() {
        return instrumentation;
    }
}
