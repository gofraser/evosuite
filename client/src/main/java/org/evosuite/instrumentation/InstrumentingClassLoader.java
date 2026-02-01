/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.instrumentation;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ResourceList;
import org.evosuite.runtime.instrumentation.RuntimeInstrumentation;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * <em>Note:</em> Do not inadvertently use multiple instances of this class in
 * the application! This may lead to hard to detect and debug errors. Yet this
 * class cannot be an singleton as it might be necessary to do so...
 *
 * @author roessler
 * @author Gordon Fraser
 */
public class InstrumentingClassLoader extends ClassLoader {

    private final static Logger logger = LoggerFactory.getLogger(InstrumentingClassLoader.class);

    private final BytecodeInstrumentation instrumentation;
    private final Map<String, Class<?>> classes = new ConcurrentHashMap<>();

    /**
     * <p>
     * Constructor for InstrumentingClassLoader.
     * </p>
     */
    public InstrumentingClassLoader() {
        this(new BytecodeInstrumentation());
        setClassAssertionStatus(Properties.TARGET_CLASS, true);
        logger.debug("STANDARD classloader running now");
    }

    /**
     * <p>
     * Constructor for InstrumentingClassLoader.
     * </p>
     *
     * @param instrumentation a {@link org.evosuite.instrumentation.BytecodeInstrumentation}
     *                        object.
     */
    public InstrumentingClassLoader(BytecodeInstrumentation instrumentation) {
        super(InstrumentingClassLoader.class.getClassLoader());
        this.instrumentation = instrumentation;
    }

    public List<String> getViewOfInstrumentedClasses() {
        return new ArrayList<>(classes.keySet());
    }


    public Class<?> loadClassFromFile(String fullyQualifiedTargetClass, String fileName) throws ClassNotFoundException {

        String className = fullyQualifiedTargetClass.replace('.', '/');

        try (InputStream is = new FileInputStream(new File(fileName))) {

            byte[] byteBuffer = getTransformedBytes(className, is);
            Class<?> result = defineInstrumentedClass(fullyQualifiedTargetClass, byteBuffer);

            logger.info("Loaded class " + fullyQualifiedTargetClass + " directly from " + fileName);
            return result;
        } catch (Throwable t) {
            logger.error("Error while loading class " + fullyQualifiedTargetClass + " : " + t.getMessage(), t);
            throw new ClassNotFoundException(t.getMessage(), t);
        }
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            if ("<evosuite>".equals(name)) {
                throw new ClassNotFoundException();
            }

            if (!RuntimeInstrumentation.checkIfCanInstrument(name)) {
                Class<?> result = findLoadedClass(name);
                if (result != null) {
                    return result;
                }
                // Delegate to parent (which was passed as InstrumentingClassLoader.class.getClassLoader())
                return getParent().loadClass(name);
            }

            Class<?> result = classes.get(name);
            if (result != null) {
                return result;
            } else {
                logger.info("Seeing class for first time: " + name);
                return instrumentClass(name);
            }
        }
    }

    //This is needed, as it is overridden in subclasses
    protected byte[] getTransformedBytes(String className, InputStream is) throws IOException {
        return instrumentation.transformBytes(this, className, new ClassReader(is));
    }

    private Class<?> instrumentClass(String fullyQualifiedTargetClass) throws ClassNotFoundException {
        String className = fullyQualifiedTargetClass.replace('.', '/');

        try (InputStream is = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getClassAsStream(fullyQualifiedTargetClass)) {
            if (is == null) {
                throw new ClassNotFoundException("Class '" + className + ".class"
                        + "' should be in target project, but could not be found!");
            }

            byte[] byteBuffer = getTransformedBytes(className, is);
            Class<?> result = defineInstrumentedClass(fullyQualifiedTargetClass, byteBuffer);

            logger.info("Loaded class: " + fullyQualifiedTargetClass);
            return result;
        } catch (Throwable t) {
            logger.error("Error while loading class: " + t.getMessage(), t);
            throw new ClassNotFoundException(t.getMessage(), t);
        }
    }

    private Class<?> defineInstrumentedClass(String fullyQualifiedTargetClass, byte[] byteBuffer) {
        createPackageDefinition(fullyQualifiedTargetClass);
        Class<?> result = defineClass(fullyQualifiedTargetClass, byteBuffer, 0, byteBuffer.length);
        classes.put(fullyQualifiedTargetClass, result);
        return result;
    }

    /**
     * Before a new class is defined, we need to create a package definition for it
     *
     * @param className a {@link java.lang.String} object.
     */
    private void createPackageDefinition(String className) {
        int i = className.lastIndexOf('.');
        if (i != -1) {
            String pkgname = className.substring(0, i);
            // Check if package already loaded.
            if (getPackage(pkgname) == null) {
                try {
                    definePackage(pkgname, null, null, null, null, null, null, null);
                    logger.info("Defined package (3): " + getPackage(pkgname) + ", " + getPackage(pkgname).hashCode());
                } catch (IllegalArgumentException e) {
                    // Ignore if already defined by another thread
                }
            }
        }
    }

    public BytecodeInstrumentation getInstrumentation() {
        return instrumentation;
    }

    public Set<String> getLoadedClasses() {
        return new HashSet<>(this.classes.keySet());
    }

}
