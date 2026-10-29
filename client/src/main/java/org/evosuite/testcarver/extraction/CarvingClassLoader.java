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
package org.evosuite.testcarver.extraction;

import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ResourceList;
import org.evosuite.runtime.instrumentation.JSRInlinerClassVisitor;
import org.evosuite.runtime.instrumentation.RuntimeInstrumentation;
import org.evosuite.runtime.instrumentation.TransformingClassLoader;
import org.evosuite.testcarver.instrument.Instrumenter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * Custom class loader for carving tests.
 *
 * @author Gordon Fraser
 */
public class CarvingClassLoader extends TransformingClassLoader {
    private final Instrumenter instrumenter = new Instrumenter();

    /**
     * Constructor for CarvingClassLoader.
     */
    public CarvingClassLoader() {
        super(ClassLoader.getSystemClassLoader(), CarvingClassLoader.class.getClassLoader());
    }

    @Override
    protected boolean canInstrument(String name) {
        return RuntimeInstrumentation.checkIfCanInstrument(name);
    }

    @Override
    protected InputStream openClass(String name) {
        return ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                .getClassAsStream(name.replace('.', '/'));
    }

    @Override
    protected URL getClassSource(String name) {
        return TestGenerationContext.getInstance().getClassLoaderForSUT()
                .getResource(name.replace('.', '/') + ".class");
    }

    @Override
    protected byte[] transform(String name, InputStream bytes) throws IOException {
        ClassNode classNode = new ClassNode();
        new ClassReader(bytes).accept(classNode, ClassReader.SKIP_FRAMES);
        instrumenter.transformClassNode(classNode, name.replace('.', '/'));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(new JSRInlinerClassVisitor(writer));
        return writer.toByteArray();
    }
}
