/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.fm;

import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.runtime.util.Inputs;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericMethod;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;


/**
 * Created by Andrea Arcuri on 27/07/15.
 */
public class MethodDescriptor implements Comparable<MethodDescriptor>, Serializable {

    private static final long serialVersionUID = -6747363265640233704L;

    protected static final Logger logger = LoggerFactory.getLogger(MethodDescriptor.class);

    private final String methodName;
    private final String inputParameterMatchers;
    private final String className;
    /**
     * How often the method was called.
     */
    private int counter;

    private GenericMethod method;

    private String id; //derived field
    private final Class<?>[] matcherParameterTypes;


    /**
     * Summary.
     * @param method     the one that is going to be mocked
     * @param retvalType type of the class the mocked method belongs to. The type might be parameterized (ie generics)
     */
    public MethodDescriptor(Method method, GenericClass<?> retvalType) {
        Inputs.checkNull(method, retvalType);
        this.method = new GenericMethod(method, retvalType);
        methodName = method.getName();
        className = method.getDeclaringClass().getName();
        matcherParameterTypes = determineMatcherParameterTypes(this.method);
        inputParameterMatchers = initMatchers(matcherParameterTypes);
    }

    private MethodDescriptor(GenericMethod m, String methodName, String className,
                             String inputParameterMatchers, Class<?>[] matcherParameterTypes) {
        this.method = m;
        this.methodName = methodName;
        this.className = className;
        this.inputParameterMatchers = inputParameterMatchers;
        this.matcherParameterTypes = matcherParameterTypes;
    }

    private String initMatchers(Class<?>[] paramTypes) {

        StringJoiner matchers = new StringJoiner(", ");
        for (Class<?> type : paramTypes) {
            matchers.add(matcherStringForType(type));
        }

        return matchers.toString();
    }

    private static Class<?>[] determineMatcherParameterTypes(GenericMethod method) {
        Class<?>[] raw = method.getMethod().getParameterTypes();
        if (!hasUnresolvedGenericParameterTypes(method.getMethod())) {
            return raw;
        }

        Class<?>[] resolved = raw.clone();
        try {
            List<GenericClass<?>> parameterClasses = method.getParameterClasses();
            for (int i = 0; i < raw.length && i < parameterClasses.size(); i++) {
                GenericClass<?> parameterClass = parameterClasses.get(i);
                if (parameterClass == null || parameterClass.getRawClass() == null) {
                    continue;
                }
                Class<?> candidate = parameterClass.getRawClass();
                // Only adopt strictly more specific resolved types.
                if (!raw[i].equals(candidate) && raw[i].isAssignableFrom(candidate)) {
                    resolved[i] = candidate;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            logger.debug("Falling back to raw matcher parameter types for {}.{}: {}",
                    method.getMethod().getDeclaringClass().getName(),
                    method.getMethod().getName(),
                    e.getMessage());
            return raw;
        }
        return resolved;
    }

    private static boolean hasUnresolvedGenericParameterTypes(Method method) {
        for (Type type : method.getGenericParameterTypes()) {
            if (type instanceof TypeVariable || type instanceof WildcardType) {
                return true;
            }
        }
        return false;
    }

    static String matcherStringForType(Class<?> type) {
        if (type.equals(Integer.TYPE) || type.equals(Integer.class)) {
            return "anyInt()";
        } else if (type.equals(Long.TYPE) || type.equals(Long.class)) {
            return "anyLong()";
        } else if (type.equals(Boolean.TYPE) || type.equals(Boolean.class)) {
            return "anyBoolean()";
        } else if (type.equals(Double.TYPE) || type.equals(Double.class)) {
            return "anyDouble()";
        } else if (type.equals(Float.TYPE) || type.equals(Float.class)) {
            return "anyFloat()";
        } else if (type.equals(Short.TYPE) || type.equals(Short.class)) {
            return "anyShort()";
        } else if (type.equals(Character.TYPE) || type.equals(Character.class)) {
            return "anyChar()";
        } else if (type.equals(String.class)) {
            return "anyString()";
        } else if (type.equals(List.class)) {
            return "anyList()";
        } else if (type.equals(Set.class)) {
            return "anySet()";
        } else if (type.equals(Map.class)) {
            return "anyMap()";
        } else if (type.equals(Collection.class)) {
            return "anyCollection()";
        } else if (type.equals(Iterable.class)) {
            return "anyIterable()";
        } else if (type.equals(Object.class)) {
            return "any()";
        } else {
            String name = type.getCanonicalName();
            if (name == null) {
                name = type.getName();
            }
            return "nullable(" + name + ".class)";
        }
    }


    /**
     * Changes the class loader.
     *
     * @param loader the new class loader
     */
    public void changeClassLoader(ClassLoader loader) {
        method.changeClassLoader(loader);
    }

    /**
     * For example, do not mock methods with no return value.
     *
     * @return .
     */
    public boolean shouldBeMocked() {

        int modifiers = method.getMethod().getModifiers();

        if (method.getReturnType().equals(Void.TYPE)
                || method.getName().equals("equals")
                || method.getName().equals("hashCode")
                || method.getName().equals("toString")
                || Modifier.isPrivate(modifiers)) {

            return false;
        }

        if (Properties.hasTargetClassBeenLoaded()) {
            //null can happen in some unit tests

            if (!Modifier.isPublic(modifiers)) {
                assert !Modifier.isPrivate(modifiers); //previous checks

                String sutName = Properties.TARGET_CLASS;

                int lastIndexMethod = className.lastIndexOf('.');
                int lastIndexSUT = sutName.lastIndexOf('.');

                boolean samePackage;
                if (lastIndexMethod != lastIndexSUT) {
                    samePackage = false;
                } else if (lastIndexMethod < 0) {
                    samePackage = true; //default package
                } else {
                    samePackage = className.substring(0, lastIndexMethod).equals(sutName.substring(0, lastIndexSUT));
                }

                return samePackage;
            }
        } else {
            logger.warn("The target class should be loaded before invoking this method");
        }

        return true;
    }

    /**
     * Creates a copy of this descriptor.
     *
     * @return a copy of this descriptor
     */
    public MethodDescriptor getCopy() {
        MethodDescriptor copy = new MethodDescriptor(method, methodName, className,
                inputParameterMatchers, matcherParameterTypes.clone());
        copy.counter = this.counter;
        return copy;
    }

    public int getNumberOfInputParameters() {
        return method.getNumParameters();
    }

    /**
     * Executes the matcher for the parameter at the given index.
     *
     * @param i the index of the parameter
     * @return the result of the matcher execution
     * @throws IllegalArgumentException if the index is invalid
     */
    public Object executeMatcher(int i) throws IllegalArgumentException {
        if (i < 0 || i >= getNumberOfInputParameters()) {
            throw new IllegalArgumentException("Invalid index: " + i);
        }

        Class<?> type = matcherParameterTypes[i];

        try {
            if (type.equals(Integer.TYPE) || type.equals(Integer.class)) {
                return Mockito.anyInt();
            } else if (type.equals(Long.TYPE) || type.equals(Long.class)) {
                return Mockito.anyLong();
            } else if (type.equals(Boolean.TYPE) || type.equals(Boolean.class)) {
                return Mockito.anyBoolean();
            } else if (type.equals(Double.TYPE) || type.equals(Double.class)) {
                return Mockito.anyDouble();
            } else if (type.equals(Float.TYPE) || type.equals(Float.class)) {
                return Mockito.anyFloat();
            } else if (type.equals(Short.TYPE) || type.equals(Short.class)) {
                return Mockito.anyShort();
            } else if (type.equals(Character.TYPE) || type.equals(Character.class)) {
                return Mockito.anyChar();
            } else if (type.equals(String.class)) {
                return Mockito.anyString();
            } else if (type.equals(List.class)) {
                return Mockito.anyList();
            } else if (type.equals(Set.class)) {
                return Mockito.anySet();
            } else if (type.equals(Map.class)) {
                return Mockito.anyMap();
            } else if (type.equals(Collection.class)) {
                return Mockito.anyCollection();
            } else if (type.equals(Iterable.class)) {
                return Mockito.anyIterable();
            } else {
                return Mockito.nullable(type);
            }
        } catch (Exception e) {
            logger.error("Failed to executed Mockito matcher n{} of type {} in {}.{}: {}",
                    i, type, className, methodName, e.getMessage());
            throw new EvosuiteError(e);
        }
    }



    public GenericMethod getGenericMethodFor(GenericClass<?> clazz) throws ConstructionFailedException {
        return method.getGenericInstantiation(clazz);
    }

    public GenericClass<?> getReturnClass() {
        return method.getGeneratedClass();
    }

    public Method getMethod() {
        assert method != null;
        return method.getMethod();
    }

    public GenericMethod getGenericMethod() {
        return method;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getInputParameterMatchers() {
        return inputParameterMatchers;
    }

    /**
     * Gets the ID.
     *
     * @return the ID
     */
    public String getID() {
        if (id == null) {
            id = className + "." + getMethodName() + "#" + getInputParameterMatchers();
        }
        return id;
    }

    public int getCounter() {
        return counter;
    }

    public void increaseCounter() {
        counter++;
    }


    @Override
    public int compareTo(MethodDescriptor o) {
        int com = this.className.compareTo(o.className);
        if (com != 0) {
            return com;
        }
        com = this.methodName.compareTo(o.methodName);
        if (com != 0) {
            return com;
        }
        com = this.inputParameterMatchers.compareTo(o.inputParameterMatchers);
        if (com != 0) {
            return com;
        }
        return this.counter - o.counter;
    }
}
