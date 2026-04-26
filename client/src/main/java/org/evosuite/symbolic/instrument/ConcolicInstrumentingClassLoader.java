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


import org.evosuite.dse.MainConfig;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.evosuite.dse.util.Assertions.check;
import static org.evosuite.dse.util.Assertions.notNull;

/**
 * A ClassLoader very similar to the <code>org.evosuite.javaagent.InstrumentingClassLoader</code>
 * It must instrument java bytecode to allow recording constraints on the program.
 *
 * @author galeotti
 */
public class ConcolicInstrumentingClassLoader extends ClassLoader {

    //private final static Logger logger = LoggerFactory.getLogger(DscInstrumentingClassLoader.class);

    private final ClassLoader classLoader;
    private final ConcolicBytecodeInstrumentation instrumentation;
    private final Map<String, Class<?>> classes = new HashMap<>();

    /**
     * Constructor.
     *
     * @param instrumentation a {@link org.evosuite.symbolic.instrument.ConcolicBytecodeInstrumentation} object.
     */
    public ConcolicInstrumentingClassLoader(ConcolicBytecodeInstrumentation instrumentation) {
        super(ConcolicInstrumentingClassLoader.class.getClassLoader());

        this.instrumentation = instrumentation;
        this.classLoader = ConcolicInstrumentingClassLoader.class.getClassLoader();
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        if (!checkIfCanInstrument(name)) {
            Class<?> result = findLoadedClass(name);
            if (result != null) {
                return result;
            }
            result = classLoader.loadClass(name);
            return result;

        } else {
            Class<?> result = findLoadedClass(name);
            if (result != null) {
                return result;
            } else {

                result = classes.get(name);
                if (result != null) {
                    return result;
                } else {
                    //logger.info("Seeing class for first time: " + name);
                    Class<?> instrumentedClass = null;
                    instrumentedClass = instrumentClass(name);
                    return instrumentedClass;
                }
            }

        }

    }

    private boolean checkIfCanInstrument(String name) {
        return !MainConfig.get().isIgnored(name);
    }

    private Class<?> instrumentClass(String fullyQualifiedTargetClass)
            throws ClassNotFoundException {
        //logger.info("Instrumenting class '" + fullyQualifiedTargetClass + "'.");
        try {
            String className = fullyQualifiedTargetClass.replace('.', '/');
            InputStream is = ClassLoader.getSystemResourceAsStream(className + ".class");
            URL sourceUrl = ClassLoader.getSystemResource(className + ".class");
            if (is == null) {
                try {
                    is = findTargetResource(className + ".class");
                    sourceUrl = findTargetResourceUrl(className + ".class");
                } catch (FileNotFoundException e) {
                    throw new ClassNotFoundException("Class '" + className + ".class"
                            + "' should be in target project, but could not be found!");
                }
            }

            byte[] byteBuffer = instrumentation.transformBytes(className, new ClassReader(is));
            ProtectionDomain protectionDomain = createProtectionDomain(sourceUrl);
            Class<?> result = protectionDomain == null
                    ? defineClass(fullyQualifiedTargetClass, byteBuffer, 0, byteBuffer.length)
                    : defineClass(fullyQualifiedTargetClass, byteBuffer, 0, byteBuffer.length, protectionDomain);
            classes.put(fullyQualifiedTargetClass, result);
            //logger.info("Keeping class: " + fullyQualifiedTargetClass);
            return result;
        } catch (Exception e) {
            throw new ClassNotFoundException(e.getMessage(), e);
        }
    }

    private InputStream findTargetResource(String name) throws FileNotFoundException {
        Collection<String> resources = ResourceList.findResourceInClassPath(name);
        if (resources.isEmpty()) {
            throw new FileNotFoundException(name);
        } else {
            String fileName = resources.iterator().next();
            return new FileInputStream(fileName);
        }
    }

    private URL findTargetResourceUrl(String name) {
        Collection<String> resources = ResourceList.findResourceInClassPath(name);
        if (resources.isEmpty()) {
            return null;
        }
        try {
            return new java.io.File(resources.iterator().next()).toURI().toURL();
        } catch (Exception ignored) {
            return null;
        }
    }

    private ProtectionDomain createProtectionDomain(URL sourceUrl) {
        if (sourceUrl == null) {
            return null;
        }
        try {
            CodeSource codeSource = new CodeSource(sourceUrl, (Certificate[]) null);
            return new ProtectionDomain(codeSource, null, this, null);
        } catch (Throwable ignored) {
            return null;
        }
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
