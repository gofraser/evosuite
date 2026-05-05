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
package org.evosuite.utils.generic;

import com.googlecode.gentyref.GenericTypeReflector;
import org.evosuite.TestGenerationContext;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * GenericMethod class.
 *
 * @author Gordon Fraser
 */
public class GenericMethod extends GenericExecutable<GenericMethod, Method> {

    private static final long serialVersionUID = -2871328984922339230L;

    private static final Logger logger = LoggerFactory.getLogger(GenericMethod.class);

    private transient Method method;
    private transient boolean reflectionAccessible = true;

    /**
     * <p>Constructor for GenericMethod.</p>
     *
     * @param method a {@link java.lang.reflect.Method} object.
     * @param clazz  a {@link org.evosuite.utils.generic.GenericClass} object.
     */
    public GenericMethod(Method method, GenericClass<?> clazz) {
        super(clazz);
        this.method = method;
        this.reflectionAccessible = makeMethodAccessible(this.method);
    }

    /**
     * <p>Constructor for GenericMethod.</p>
     *
     * @param method a {@link java.lang.reflect.Method} object.
     * @param clazz  a {@link org.evosuite.utils.generic.GenericClass} object.
     */
    public GenericMethod(Method method, Class<?> clazz) {
        this(method, GenericClassFactory.get(clazz));
    }

    /**
     * <p>Constructor for GenericMethod.</p>
     *
     * @param method a {@link java.lang.reflect.Method} object.
     * @param type   a {@link java.lang.reflect.Type} object.
     */
    public GenericMethod(Method method, Type type) {
        this(method, GenericClassFactory.get(type));
    }

    /**
     * <p>Getter for the field <code>method</code>.</p>
     *
     * @return a {@link java.lang.reflect.Method} object.
     */
    public Method getMethod() {
        return method;
    }

    /**
     * {@inheritDoc}
     */
    public int getModifiers() {
        return method.getModifiers();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return method.getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Type[] getParameterTypes() {
        return getExactParameterTypes(method, owner.getType());
    }

    @Override
    public Type[] getGenericParameterTypes() {
        return getParameterTypes();
    }

    /**
     * Returns the list of parameter classes.
     *
     * @return the list of parameter classes
     */
    public List<GenericClass<?>> getParameterClasses() {
        List<GenericClass<?>> classes = new ArrayList<>();
        for (Type type : getParameterTypes()) {
            classes.add(GenericClassFactory.get(type));
        }
        return classes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Type getReturnType() {
        return getExactReturnType(method, owner.getType());
    }

    /**
     * Returns the exact return type of the given method in the given type.
     * This may be different from <tt>m.getGenericReturnType()</tt> when the
     * method was declared in a superclass, or <tt>type</tt> has a type
     * parameter that is used in the return type, or <tt>type</tt> is a raw
     * type.
     */
    public Type getExactReturnType(Method m, Type type) {
        Type returnType = m.getGenericReturnType();
        // capture(type) is not a subtype of m.getDeclaringClass()
        // logger.info("The method " + m + " is not a member of type " + type
        //        + " - declared in " + m.getDeclaringClass());
        Type exactDeclaringType = null;
        try {
            exactDeclaringType = GenericTypeReflector.getExactSuperType(GenericTypeReflector.capture(type),
                    m.getDeclaringClass());
        } catch (java.lang.TypeNotPresentException e) {
            // May happen in completely intransparent circumstances when there are dependency issues with annotations:
            // https://bugs.java.com/view_bug.do?bug_id=JDK-7183985
        }

        if (exactDeclaringType == null) { // capture(type) is not a subtype of m.getDeclaringClass()
            Class<?> rawType = getRawClass(type);
            if (isAssignableToDeclaringClass(rawType, m.getDeclaringClass())) {
                logger.info("The method " + m + " is not a member of type " + type
                        + " - declared in " + m.getDeclaringClass()
                        + " [unexpected, rawType=" + rawType + "]");
            }
            return m.getReturnType();
        }

        // if (exactDeclaringType.equals(type)) {
        //     logger.debug("Returntype: " + returnType + ", " + exactDeclaringType);
        //     return returnType;
        // }

        return mapTypeParameters(returnType, exactDeclaringType);
    }

    /**
     * Returns the exact parameter types of the given method in the given type.
     * This may be different from <tt>m.getGenericParameterTypes()</tt> when the
     * method was declared in a superclass, or <tt>type</tt> has a type
     * parameter that is used in one of the parameters, or <tt>type</tt> is a
     * raw type.
     */
    public Type[] getExactParameterTypes(Method m, Type type) {
        Type[] parameterTypes = m.getGenericParameterTypes();
        Type exactDeclaringType = GenericTypeReflector.getExactSuperType(GenericTypeReflector.capture(type),
                m.getDeclaringClass());
        if (exactDeclaringType == null) { // capture(type) is not a subtype of m.getDeclaringClass()
            Class<?> rawType = getRawClass(type);
            if (isAssignableToDeclaringClass(rawType, m.getDeclaringClass())) {
                logger.info("The method " + m + " is not a member of type " + type
                        + " - declared in " + m.getDeclaringClass()
                        + " [unexpected, rawType=" + rawType + "]");
            }
            return m.getParameterTypes();
        }

        Type[] result = new Type[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            result[i] = mapTypeParameters(parameterTypes[i], exactDeclaringType);
        }
        return result;
    }

    @Override
    public TypeVariable<?>[] getTypeParameters() {
        return method.getTypeParameters();
    }

    @Override
    public boolean isAccessible() {
        return reflectionAccessible && TestUsageChecker.canUse(method);
    }

    @Override
    public boolean isConstructor() {
        return false;
    }

    @Override
    public boolean isMethod() {
        return true;
    }

    public boolean isAbstract() {
        return Modifier.isAbstract(method.getModifiers());
    }

    @Override
    public boolean isStatic() {
        return Modifier.isStatic(method.getModifiers());
    }

    /**
     * Checks if the method is overloaded.
     *
     * @return true if overloaded
     */
    public boolean isOverloaded() {
        String methodName = getName();
        Class<?> declaringClass = method.getDeclaringClass();
        try {
            for (java.lang.reflect.Method otherMethod : getCandidateMethods(declaringClass)) {
                if (otherMethod.equals(method)) {
                    continue;
                }

                if (otherMethod.getName().equals(methodName)) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            // ignored
        } catch (NoClassDefFoundError e) {
            // ignored
        }

        return false;
    }

    /**
     * Guard to reduce noise: only log when the owner type should be assignable
     * to the declaring class, yet generic resolution still failed.
     */
    private boolean isAssignableToDeclaringClass(Class<?> rawType, Class<?> declaringClass) {
        return rawType != null && declaringClass.isAssignableFrom(rawType);
    }

    private Class<?> getRawClass(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof java.lang.reflect.ParameterizedType) {
            Type raw = ((java.lang.reflect.ParameterizedType) type).getRawType();
            if (raw instanceof Class<?>) {
                return (Class<?>) raw;
            }
        }
        return null;
    }

    @Override
    public GenericMethod getGenericInstantiation(GenericClass<?> calleeType) throws ConstructionFailedException {
        return (GenericMethod) super.getGenericInstantiation(calleeType);
    }

    @Override
    public boolean isOverloaded(List<VariableReference> parameters) {
        String methodName = getName();
        Class<?> declaringClass = method.getDeclaringClass();
        Class<?>[] parameterTypes = method.getParameterTypes();
        boolean isExact = true;
        boolean hasNullArgument = false;
        Class<?>[] parameterClasses = new Class<?>[parameters.size()];
        for (int num = 0; num < parameters.size(); num++) {
            VariableReference parameter = parameters.get(num);
            if (isNullArgument(parameter)) {
                hasNullArgument = true;
            }
            parameterClasses[num] = parameter.getVariableClass();
            if (!parameterClasses[num].equals(parameterTypes[num])) {
                isExact = false;
                break;
            }

        }

        // A null literal is typeless in source code. Even if the tracked variable type
        // exactly matches this method, we still need overload disambiguation casts.
        if (isExact && !hasNullArgument) {
            return false;
        }

        List<Method> applicable = new ArrayList<>();
        boolean thisMethodApplicable = false;
        boolean hasSiblingWithSameName = false;
        try {
            for (java.lang.reflect.Method otherMethod : getCandidateMethods(declaringClass)) {
                if (!otherMethod.getName().equals(methodName)) {
                    continue;
                }
                if (!otherMethod.equals(method)) {
                    hasSiblingWithSameName = true;
                }

                if (parameterTypes.length != otherMethod.getParameterCount()) {
                    continue;
                }

                Class<?>[] otherParameterTypes = otherMethod.getParameterTypes();
                boolean applicableToOther = true;
                for (int i = 0; i < parameterClasses.length; i++) {
                    if (!isAssignableToParameter(parameters.get(i), otherParameterTypes[i])) {
                        applicableToOther = false;
                        break;
                    }
                }
                if (!applicableToOther) {
                    continue;
                }

                applicable.add(otherMethod);
                if (otherMethod.equals(method)) {
                    thisMethodApplicable = true;
                }
            }

            // No other method shares this name, so there is no overload to disambiguate.
            if (!hasSiblingWithSameName) {
                return false;
            }

            // If current variable types cannot call this overload directly,
            // a cast/disambiguation is required to represent it.
            if (!thisMethodApplicable) {
                return true;
            }

            if (applicable.size() <= 1) {
                return false;
            }

            List<Method> maximalApplicable = new ArrayList<>();
            for (Method candidate : applicable) {
                boolean dominated = false;
                for (Method other : applicable) {
                    if (candidate.equals(other)) {
                        continue;
                    }
                    if (isMoreSpecific(other, candidate)) {
                        dominated = true;
                        break;
                    }
                }
                if (!dominated) {
                    maximalApplicable.add(candidate);
                }
            }

            // No cast needed only when this method is the unique most-specific
            // applicable overload chosen by Java compile-time resolution.
            return maximalApplicable.size() != 1 || !maximalApplicable.get(0).equals(method);
        } catch (SecurityException | NoClassDefFoundError e) {
            // ignored
        }

        return false;
    }

    private boolean isMoreSpecific(Method left, Method right) {
        Class<?>[] leftParams = left.getParameterTypes();
        Class<?>[] rightParams = right.getParameterTypes();
        if (leftParams.length != rightParams.length) {
            return false;
        }

        boolean strictlyMoreSpecific = false;
        for (int i = 0; i < leftParams.length; i++) {
            Class<?> lp = leftParams[i];
            Class<?> rp = rightParams[i];
            if (lp.equals(rp)) {
                continue;
            }
            if (rp.isAssignableFrom(lp)) {
                strictlyMoreSpecific = true;
                continue;
            }
            return false;
        }
        return strictlyMoreSpecific;
    }

    private Set<Method> getCandidateMethods(Class<?> declaringClass) {
        Set<Method> candidates = new LinkedHashSet<>();
        try {
            candidates.addAll(Arrays.asList(declaringClass.getMethods()));
        } catch (SecurityException | NoClassDefFoundError ignored) {
            // Best effort: fall through to declared methods below.
        }
        Class<?> current = declaringClass;
        while (current != null) {
            try {
                candidates.addAll(Arrays.asList(current.getDeclaredMethods()));
            } catch (SecurityException | NoClassDefFoundError ignored) {
                // Keep scanning remaining parents/interfaces when possible.
            }
            current = current.getSuperclass();
        }
        return candidates;
    }

    @Override
    public int getNumParameters() {
        return method.getGenericParameterTypes().length;
    }

    public boolean isGenericMethod() {
        return getNumParameters() > 0;
    }


    @Override
    public String getNameWithDescriptor() {
        return method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method);
    }

    @Override
    public String getDescriptor() {
        return org.objectweb.asm.Type.getMethodDescriptor(method);
    }

    @Override
    public String toString() {
        return method.toGenericString();
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();
        // Write/save additional fields
        oos.writeObject(method.getDeclaringClass().getName());
        oos.writeObject(method.getName());
        oos.writeObject(org.objectweb.asm.Type.getMethodDescriptor(method));
    }

    // assumes "static java.util.Date aDate;" declared
    private void readObject(ObjectInputStream ois) throws ClassNotFoundException,
            IOException {
        ois.defaultReadObject();

        // Read/initialize additional fields
        String className = (String) ois.readObject();
        // TODO: What was the point of this??
        // methodClass = TestCluster.classLoader.loadClass(methodClass.getName());

        String methodName = (String) ois.readObject();
        String methodDesc = (String) ois.readObject();

        ClassLoader[] candidateLoaders = new ClassLoader[]{
                TestGenerationContext.getInstance().getClassLoaderForSUT(),
                Thread.currentThread().getContextClassLoader(),
                GenericMethod.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        ClassLoader previous = null;
        Throwable lastError = null;

        for (ClassLoader loader : candidateLoaders) {
            if (loader == null || loader == previous) {
                continue;
            }
            previous = loader;

            try {
                Class<?> methodClass = GenericClassImpl.getClass(className, loader);
                this.method = findMethod(methodClass, methodName, methodDesc);
                if (this.method != null) {
                    this.reflectionAccessible = makeMethodAccessible(this.method);
                    return;
                }
            } catch (ClassNotFoundException | LinkageError e) {
                lastError = e;
            }
        }

        IllegalStateException exception =
                new IllegalStateException("Unknown method " + methodName + " in class " + className);
        if (lastError != null) {
            exception.initCause(lastError);
        }
        throw exception;
    }

    private Method findMethod(Class<?> clazz, String name, String desc) {
        Class<?> current = clazz;
        while (current != null) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    if (org.objectweb.asm.Type.getMethodDescriptor(m).equals(desc)) {
                        return m;
                    }
                }
            }
            current = current.getSuperclass();
        }
        // If not found in class hierarchy, try interfaces (e.g. for default methods)
        for (Class<?> iface : clazz.getInterfaces()) {
            Method m = findMethod(iface, name, desc);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    @Override
    public void changeClassLoader(ClassLoader loader) {
        super.changeClassLoader(loader);
        try {
            Class<?> oldClass = method.getDeclaringClass();
            Class<?> newClass = loader.loadClass(oldClass.getName());
            for (Method newMethod : TestClusterUtils.getMethods(newClass)) {
                if (newMethod.getName().equals(this.method.getName())) {
                    boolean equals = true;
                    Class<?>[] oldParameters = this.method.getParameterTypes();
                    Class<?>[] newParameters = newMethod.getParameterTypes();
                    if (oldParameters.length != newParameters.length) {
                        continue;
                    }

                    if (!newMethod.getDeclaringClass().getName().equals(method.getDeclaringClass().getName())) {
                        continue;
                    }

                    if (!newMethod.getReturnType().getName().equals(method.getReturnType().getName())) {
                        continue;
                    }


                    for (int i = 0; i < newParameters.length; i++) {
                        if (!oldParameters[i].getName().equals(newParameters[i].getName())) {
                            equals = false;
                            break;
                        }
                    }
                    if (equals) {
                        this.method = newMethod;
                        this.reflectionAccessible = makeMethodAccessible(this.method);
                        return;
                    }
                }
            }
            LoggingUtils.getEvoLogger().info("Method not found - keeping old class loader ");
        } catch (ClassNotFoundException e) {
            LoggingUtils.getEvoLogger().info("Class not found - keeping old class loader ", e);
        } catch (SecurityException e) {
            LoggingUtils.getEvoLogger().info("Class not found - keeping old class loader ", e);
        }
    }

    private static boolean makeMethodAccessible(Method method) {
        if (Modifier.isPublic(method.getModifiers())
                && Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            return true;
        }
        try {
            method.setAccessible(true);
            return true;
        } catch (RuntimeException e) {
            logger.debug("Cannot make method accessible: {}", method, e);
            return false;
        }
    }

    @Override
    public boolean isPublic() {
        return Modifier.isPublic(method.getModifiers());
    }

    @Override
    public boolean isPrivate() {
        return Modifier.isPrivate(method.getModifiers());
    }

    @Override
    public boolean isProtected() {
        return Modifier.isProtected(method.getModifiers());
    }

    @Override
    public boolean isDefault() {
        return !isPublic() && !isPrivate() && !isProtected();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((method == null) ? 0 : method.hashCode());
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
        GenericMethod other = (GenericMethod) obj;
        if (method == null) {
            return other.method == null;
        } else {
            return method.equals(other.method);
        }
    }

    // Implementations of abstract methods from GenericExecutable

    @Override
    public GenericMethod copy() {
        GenericMethod copy = new GenericMethod(method, GenericClassFactory.get(owner));
        copyTypeVariables(copy);
        return copy;
    }

    @Override
    public GenericMethod copyWithNewOwner(GenericClass<?> newOwner) {
        GenericMethod copy = new GenericMethod(method, newOwner);
        copyTypeVariables(copy);
        return copy;
    }

    @Override
    public GenericMethod copyWithOwnerFromReturnType(GenericClass<?> returnType) {
        // For methods (unlike constructors), the return type is NOT the owner.
        // Instantiate the owner's type variables using the return type's type variable map,
        // but keep the owner as the declaring class.
        GenericClass<?> newOwner;
        try {
            newOwner = getOwnerClass().getGenericInstantiation(returnType.getTypeVariableMap());
        } catch (ConstructionFailedException e) {
            // If instantiation fails, keep the original owner unchanged
            newOwner = GenericClassFactory.get(owner);
        }
        GenericMethod copy = new GenericMethod(method, newOwner);
        copyTypeVariables(copy);
        return copy;
    }

    @Override
    public AccessibleObject getAccessibleObject() {
        return method;
    }

    @Override
    public Class<?> getDeclaringClass() {
        return method.getDeclaringClass();
    }

    @Override
    public Type getGeneratedType() {
        return getReturnType();
    }

    @Override
    public Class<?> getRawGeneratedType() {
        return method.getReturnType();
    }

    @Override
    public Type getGenericGeneratedType() {
        return getExactReturnType(method, owner.getType());
    }

    @Override
    protected Type getGenericReturnTypeForInstantiation() {
        return method.getGenericReturnType();
    }

    @Override
    public Parameter[] getParameters() {
        return method.getParameters();
    }

    @Override
    public Class<?>[] getRawParameterTypes() {
        return method.getParameterTypes();
    }
}
