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
package org.evosuite.symbolic.instrument;


import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ResourceList;
import org.evosuite.dse.MainConfig;
import org.evosuite.runtime.instrumentation.TransformingClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.net.URL;

import static org.evosuite.dse.util.Assertions.check;
import static org.evosuite.dse.util.Assertions.notNull;

/**
 * A ClassLoader very similar to the <code>org.evosuite.javaagent.InstrumentingClassLoader</code>
 * It must instrument java bytecode to allow recording constraints on the program.
 *
 * @author galeotti
 */
public class ConcolicInstrumentingClassLoader extends TransformingClassLoader {

    private final ConcolicBytecodeInstrumentation instrumentation;

    /**
     * Constructor.
     *
     * @param instrumentation a {@link org.evosuite.symbolic.instrument.ConcolicBytecodeInstrumentation} object.
     */
    public ConcolicInstrumentingClassLoader(ConcolicBytecodeInstrumentation instrumentation) {
        super(ConcolicInstrumentingClassLoader.class.getClassLoader(),
                ConcolicInstrumentingClassLoader.class.getClassLoader());

        this.instrumentation = instrumentation;
    }

    @Override
    protected boolean canInstrument(String name) {
        return !MainConfig.get().isIgnored(name);
    }

    @Override
    protected InputStream openClass(String name) throws IOException {
        String resource = name.replace('.', '/') + ".class";
        InputStream is = ClassLoader.getSystemResourceAsStream(resource);
        if (is != null) {
            return is;
        }
        URL url = targetResource(resource);
        return url == null ? null : url.openStream();
    }

    @Override
    protected URL getClassSource(String name) {
        String resource = name.replace('.', '/') + ".class";
        URL url = ClassLoader.getSystemResource(resource);
        return url != null ? url : targetResource(resource);
    }

    private static URL targetResource(String resource) {
        return ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                .findResource(resource);
    }

    @Override
    protected byte[] transform(String name, InputStream bytes) throws IOException {
        return instrumentation.transformBytes(name.replace('.', '/'), new ClassReader(bytes));
    }


    /**
     * Loads class named className, without initializing it.
     *
     * @param className either as p/q/MyClass or as p.q.MyClass.
     * @return a {@link java.lang.Class} object.
     */
    public Class<?> getClassForName(String className) {
        notNull(className);

        Class<?> res = null;
        String classNameDot = className.replace('/', '.');
        try {
            res = this.loadClass(classNameDot);
        } catch (ClassNotFoundException cnfe) {
            check(false, cnfe);
        }
        return notNull(res);
    }


    /**
     * Loads class whose type is type, without initializing it.
     *
     * @param type a {@link org.objectweb.asm.Type} object.
     * @return a {@link java.lang.Class} object.
     */
    public Class<?> getClassForType(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return Boolean.TYPE;
            case Type.BYTE:
                return Byte.TYPE;
            case Type.CHAR:
                return Character.TYPE;
            case Type.DOUBLE:
                return Double.TYPE;
            case Type.FLOAT:
                return Float.TYPE;
            case Type.INT:
                return Integer.TYPE;
            case Type.LONG:
                return Long.TYPE;
            case Type.SHORT:
                return Short.TYPE;
            case Type.VOID:
                return Void.TYPE;
            case Type.ARRAY: {
                Class<?> elementClass = this.getClassForType(type.getElementType());
                int dimensions = type.getDimensions();
                int[] lenghts = new int[dimensions];
                Class<?> arrayClass = Array.newInstance(elementClass, lenghts)
                        .getClass();
                return arrayClass;

            }
            default:
                return this.getClassForName(type.getInternalName());

        }
    }


}
