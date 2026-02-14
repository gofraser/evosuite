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
package org.evosuite.assertion;

import org.evosuite.TestGenerationContext;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.utils.LoggingUtils;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A chained inspector that calls an outer method returning a complex object,
 * then calls an inner inspector on the result. This produces assertions like
 * {@code assertEquals(3, obj.getList().size())}.
 *
 * Exactly 2 levels deep — no arbitrary chaining.
 */
public class ChainedInspector extends Inspector {

    private static final long serialVersionUID = 1L;

    private transient Method outerMethod;
    private transient Class<?> outerClass;
    private final Inspector innerInspector;

    public ChainedInspector(Class<?> clazz, Method outerMethod, Inspector innerInspector) {
        // Pass the outer class and outer method to the superclass.
        // We override getValue and getMethodCall to implement chaining.
        super(clazz, outerMethod);
        this.outerClass = clazz;
        this.outerMethod = outerMethod;
        this.outerMethod.setAccessible(true);
        this.innerInspector = innerInspector;
    }

    @Override
    public Object getValue(Object object) throws IllegalArgumentException,
            IllegalAccessException, InvocationTargetException {
        // First, call the outer method to get the intermediate object
        Object intermediate = super.getValue(object);
        if (intermediate == null) {
            return null;
        }
        // Then call the inner inspector on the intermediate result
        return innerInspector.getValue(intermediate);
    }

    @Override
    public String getMethodCall() {
        // Returns e.g. "getList().size" so code gen produces "obj.getList().size()"
        return outerMethod.getName() + "()." + innerInspector.getMethodCall();
    }

    @Override
    public Class<?> getReturnType() {
        return innerInspector.getReturnType();
    }

    @Override
    public Method getMethod() {
        return innerInspector.getMethod();
    }

    public Method getOuterMethod() {
        return outerMethod;
    }

    public Inspector getInnerInspector() {
        return innerInspector;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((innerInspector == null) ? 0 : innerInspector.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ChainedInspector other = (ChainedInspector) obj;
        if (innerInspector == null) {
            return other.innerInspector == null;
        } else {
            return innerInspector.equals(other.innerInspector);
        }
    }

    @Override
    public void changeClassLoader(ClassLoader loader) {
        // Update the outer method via parent (which updates the super's method field)
        super.changeClassLoader(loader);
        // Also update the outer method reference from the super's field
        this.outerMethod = super.getMethod();
        // Update the inner inspector
        innerInspector.changeClassLoader(loader);
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();
        // Write outer method info for deserialization
        oos.writeObject(outerClass.getName());
        oos.writeObject(outerMethod.getDeclaringClass().getName());
        oos.writeObject(outerMethod.getName());
        oos.writeObject(Type.getMethodDescriptor(outerMethod));
    }

    private void readObject(ObjectInputStream ois) throws ClassNotFoundException,
            IOException {
        ois.defaultReadObject();

        this.outerClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
                .loadClass((String) ois.readObject());
        Class<?> methodClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
                .loadClass((String) ois.readObject());

        String methodName = (String) ois.readObject();
        String methodDesc = (String) ois.readObject();

        for (Method method : methodClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                if (Type.getMethodDescriptor(method).equals(methodDesc)) {
                    this.outerMethod = method;
                    this.outerMethod.setAccessible(true);
                    return;
                }
            }
        }
    }
}
