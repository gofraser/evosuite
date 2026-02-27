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
package org.evosuite.utils.generic;

import com.googlecode.gentyref.GenericTypeReflector;
import org.evosuite.ga.ConstructionFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.*;
import java.util.*;

/**
 * This class is meant to mimic {@link java.lang.reflect.AccessibleObject AccessibleObject} from
 * the Java Reflections API, enhanced with a few additions and convenience methods to work
 * around the limitations of type erasure with regards to generics and to provide means for
 * serialization. A {@code GenericAccessibleObject} is the object-representation of one of the
 * following: a reflected field, reflected method, or reflected constructor of a class.
 *
 * @author Gordon Fraser
 */
public abstract class GenericAccessibleObject<T extends GenericAccessibleObject<T>>
        implements Serializable {

    protected static final Logger logger = LoggerFactory.getLogger(GenericAccessibleObject.class);

    private static final long serialVersionUID = 7069749492563662621L;

    /**
     * The class in which this GenericAccessibleObject (i.e. field, method or constructor) is
     * located in.
     */
    protected GenericClass<?> owner;

    protected List<GenericClass<?>> typeVariables = new ArrayList<>();



    /**
     * Checks if the given type is a class that is supposed to have type
     * parameters, but doesn't. In other words, if it's a really raw type.
     */
    protected static boolean isMissingTypeParameters(Type type) {
        if (type instanceof Class) {
            for (Class<?> clazz = (Class<?>) type; clazz != null; clazz = clazz.getEnclosingClass()) {
                if (clazz.getTypeParameters().length != 0) {
                    return true;
                }
            }
            return false;
        } else if (type instanceof ParameterizedType) {
            return false;
        } else {
            throw new AssertionError("Unexpected type " + type.getClass());
        }
    }

    /**
     * Constructs a new GenericAccessibleObject with the given {@code owner} class.
     *
     * @param owner the class where this accessible object is located in
     */
    public GenericAccessibleObject(GenericClass<?> owner) {
        this.owner = owner;
    }

    /**
     * Changes the class loader for the owning class of this {@code GenericAccessibleObject} and for
     * all of its type variables.
     *
     * @param loader the new class loader to set
     */
    public void changeClassLoader(ClassLoader loader) {
        owner.changeClassLoader(loader);
        for (GenericClass<?> typeVariable : typeVariables) {
            typeVariable.changeClassLoader(loader);
        }
    }

    protected void copyTypeVariables(GenericAccessibleObject<?> copy) {
        for (GenericClass<?> variable : typeVariables) {
            copy.typeVariables.add(GenericClassFactory.get(variable));
        }
    }

    /**
     * Creates and returns a copy of this {@code GenericAccessibleObject}.
     *
     * @return a copy of this {@code GenericAccessibleObject}
     */
    public abstract T copy();

    public abstract T copyWithNewOwner(GenericClass<?> newOwner);

    public abstract T copyWithOwnerFromReturnType(GenericClass<?> returnType)
            throws ConstructionFailedException;

    public abstract AccessibleObject getAccessibleObject();

    public abstract Class<?> getDeclaringClass();

    public abstract Type getGeneratedType();

    public GenericClass<?> getGeneratedClass() {
        return GenericClassFactory.get(getGeneratedType());
    }

    public Type[] getGenericParameterTypes() {
        return new Type[]{};
    }

    public abstract Type getGenericGeneratedType();

    /**
     * Return the generic return type used for matching during
     * {@link #getGenericInstantiationFromReturnValue(GenericClass)}.
     * Subclasses can override this to preserve method-level type variables.
     */
    protected Type getGenericReturnTypeForInstantiation() {
        return getGenericGeneratedType();
    }

    /**
     * Instantiate all generic type parameters.
     *
     * @return the generic instantiation
     * @throws ConstructionFailedException if instantiation fails
     */
    public T getGenericInstantiation() throws ConstructionFailedException {
        T copy = copy();

        if (!hasTypeParameters()) {
            copy.owner = copy.getOwnerClass().getGenericInstantiation();
            return copy;
        }

        Map<TypeVariable<?>, Type> typeMap = copy.getOwnerClass().getTypeVariableMap();

        logger.debug("Getting random generic instantiation of method: " + this
                + " with owner type map: " + GenericUtils.stableTypeVariableMapToString(typeMap));
        List<GenericClass<?>> typeParameters = new ArrayList<>();

        // TODO: The bounds of this type parameter need to be updated for the owner of the call
        // which may instantiate some of the type parameters
        for (TypeVariable<?> parameter : getTypeParameters()) {
            GenericClass<?> genericType = GenericClassFactory.get(parameter);
            GenericClass<?> concreteType = genericType.getGenericInstantiation(typeMap);
            logger.debug("Setting parameter " + parameter + " to type "
                    + concreteType.getTypeName());
            typeParameters.add(concreteType);
        }
        copy.setTypeParameters(typeParameters);
        copy.owner = copy.getOwnerClass().getGenericInstantiation(typeMap);
        return copy;
    }

    /**
     * Instantiate all generic type parameters based on a new callee type.
     *
     * @param calleeType the callee type
     * @return the generic instantiation
     * @throws ConstructionFailedException if instantiation fails
     */
    public T getGenericInstantiation(GenericClass<?> calleeType)
            throws ConstructionFailedException {

        T copy = copy();

        logger.debug("Getting generic instantiation for callee " + calleeType
                + " of method: " + this + " for callee " + calleeType);
        Map<TypeVariable<?>, Type> typeMap = calleeType.getTypeVariableMap();
        if (!hasTypeParameters()) {
            logger.debug("Have no type parameters, just using typeMap of callee");
            copy.owner = copy.getOwnerClass().getGenericInstantiation(typeMap);
            return copy;
        }

        List<GenericClass<?>> typeParameters = new ArrayList<>();
        for (TypeVariable<?> parameter : getTypeParameters()) {
            GenericClass<?> concreteType = GenericClassFactory.get(parameter);
            logger.debug("(I) Setting parameter " + parameter + " to type "
                    + concreteType.getTypeName());
            typeParameters.add(concreteType.getGenericInstantiation(typeMap));
        }
        copy.setTypeParameters(typeParameters);
        copy.owner = copy.getOwnerClass().getGenericInstantiation(typeMap);

        return copy;
    }

    /**
     * Set type parameters based on return type.
     *
     * @param generatedType the generated type
     * @return the generic instantiation
     * @throws ConstructionFailedException if instantiation fails
     */
    public T getGenericInstantiationFromReturnValue(GenericClass<?> generatedType)
            throws ConstructionFailedException {

        logger.debug("Instantiating generic return for generated Type " + generatedType);
        T copy = copy();

        // We just want to have the type variables defined in the generic method here
        // and not type variables defined in the owner
        Map<TypeVariable<?>, Type> concreteTypes = new HashMap<>();
        logger.debug("Getting type map of generated type");
        Map<TypeVariable<?>, Type> generatorTypes = generatedType.getTypeVariableMap();
        logger.debug("Got type map of generated type: " + GenericUtils.stableTypeVariableMapToString(generatorTypes));
        Type genericReturnType = getGenericReturnTypeForInstantiation();

        logger.debug("Getting generic instantiation for return type " + generatedType
                + " of method: " + this);

        if (genericReturnType instanceof ParameterizedType
                && generatedType.isParameterizedType()) {
            logger.debug("Return value is a parameterized type, matching variables");
            generatorTypes.putAll(GenericUtils.getMatchingTypeParameters(
                    (ParameterizedType) generatedType.getType(),
                    (ParameterizedType) genericReturnType));
        } else if (genericReturnType instanceof TypeVariable<?>) {
            generatorTypes.put((TypeVariable<?>) genericReturnType,
                    generatedType.getType());
        }

        if (genericReturnType instanceof ParameterizedType) {
            for (Type parameterType : getGenericParameterTypes()) {
                logger.debug("Checking parameter " + parameterType);
                if (parameterType instanceof ParameterizedType) {
                    Map<TypeVariable<?>, Type> matchedMap = GenericUtils.getMatchingTypeParameters(
                            (ParameterizedType) parameterType,
                            (ParameterizedType) genericReturnType);
                    for (TypeVariable<?> var : matchedMap.keySet()) {
                        if (!generatorTypes.containsKey(var)) {
                            generatorTypes.put(var, matchedMap.get(var));
                        }
                    }
                    logger.debug("Map is now " + GenericUtils.stableTypeVariableMapToString(generatorTypes));
                }
            }
        }
        logger.debug("GeneratorTypes is now: " + GenericUtils.stableTypeVariableMapToString(generatorTypes));
        List<TypeVariable<?>> parameters = Arrays.asList(getTypeParameters());
        for (TypeVariable<?> var : GenericUtils.sortedTypeVariables(generatorTypes)) {
            if (parameters.contains(var) && !(generatorTypes.get(var) instanceof WildcardType)) {
                logger.debug("Parameter " + var + " in map, adding to concrete types: "
                        + generatorTypes.get(var));
                concreteTypes.put(var, generatorTypes.get(var));
            } else {
                logger.debug("Parameter " + var + " not in map, not adding to concrete types: "
                        + generatorTypes.get(var));
                logger.debug("Key: " + var.getGenericDeclaration());
                for (TypeVariable<?> k : parameters) {
                    logger.debug("Param: " + k.getGenericDeclaration());
                }
            }
        }

        // When resolving the type variables on a non-static generic method
        // we need to look at the owner type, and not the return type!

        List<GenericClass<?>> typeParameters = new ArrayList<>();
        logger.debug("Setting parameters with map: " + GenericUtils.stableTypeVariableMapToString(concreteTypes));
        for (TypeVariable<?> parameter : getTypeParameters()) {
            GenericClass<?> concreteType = GenericClassFactory.get(parameter);
            logger.debug("(I) Setting parameter " + parameter + " to type "
                    + concreteType.getTypeName());
            GenericClass<?> instantiation = concreteType.getGenericInstantiation(concreteTypes);
            logger.debug("Got instantiation for " + parameter + ": " + instantiation);

            Map<TypeVariable<?>, Type> ownerVariableMap = new HashMap<>();
            ownerVariableMap.putAll(this.getOwnerClass().getTypeVariableMap());
            if (!instantiation.satisfiesBoundaries(parameter, ownerVariableMap)) {
                logger.info("Type parameter does not satisfy boundaries: " + parameter
                        + " " + instantiation);
                logger.info(Arrays.asList(parameter.getBounds()).toString());
                logger.info(instantiation.toString());
                throw new ConstructionFailedException(
                        "Type parameter does not satisfy boundaries: " + parameter);
            }
            typeParameters.add(instantiation);
        }
        copy.setTypeParameters(typeParameters);
        copy.owner = copy.getOwnerClass().getGenericInstantiation(concreteTypes);

        return copy;
    }

    public abstract String getName();

    public int getNumParameters() {
        return 0;
    }

    public GenericClass<?> getOwnerClass() {
        return owner;
    }

    public Type getOwnerType() {
        return owner.getType();
    }

    public abstract Class<?> getRawGeneratedType();

    public abstract TypeVariable<?>[] getTypeParameters();

    protected Map<TypeVariable<?>, GenericClass<?>> getTypeVariableMap() {
        Map<TypeVariable<?>, GenericClass<?>> typeMap = new HashMap<>();
        int pos = 0;
        for (TypeVariable<?> variable : getTypeParameters()) {
            if (typeVariables.size() <= pos) {
                break;
            }
            typeMap.put(variable, typeVariables.get(pos));
            pos++;
        }
        return typeMap;
    }

    public boolean hasTypeParameters() {
        return getTypeParameters().length != 0;
    }

    public abstract boolean isAccessible();

    public abstract boolean isConstructor();

    public abstract boolean isField();

    public abstract boolean isMethod();

    public abstract boolean isStatic();

    public abstract boolean isPublic();

    public abstract boolean isPrivate();

    public abstract boolean isProtected();

    public abstract boolean isDefault();

    /**
     * Maps type parameters in a type to their values.
     *
     * @param toMapType     Type possibly containing type arguments
     * @param typeAndParams must be either ParameterizedType, or (in case there are no
     *                      type arguments, or it's a raw type) Class
     * @return toMapType, but with type parameters from typeAndParams replaced.
     */
    protected Type mapTypeParameters(Type toMapType, Type typeAndParams) {
        if (isMissingTypeParameters(typeAndParams)) {
            logger.debug("Is missing type parameters, so erasing types");
            return GenericTypeReflector.erase(toMapType);
        } else {
            VarMap varMap = new VarMap();
            Type handlingTypeAndParams = typeAndParams;
            while (handlingTypeAndParams instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) handlingTypeAndParams;
                Class<?> clazz = (Class<?>) parameterizedType.getRawType(); // getRawType should always be Class
                varMap.addAll(clazz.getTypeParameters(), parameterizedType.getActualTypeArguments());
                handlingTypeAndParams = parameterizedType.getOwnerType();
            }
            varMap.addAll(getTypeVariableMap());
            return varMap.map(toMapType);
        }
    }

    /**
     * Sets the type parameters.
     *
     * @param parameterTypes the type parameters
     */
    public void setTypeParameters(List<GenericClass<?>> parameterTypes) {
        typeVariables.clear();
        for (GenericClass<?> parameter : parameterTypes) {
            typeVariables.add(GenericClassFactory.get(parameter));
        }
    }

    @Override
    public abstract String toString();

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        GenericAccessibleObject<?> other = (GenericAccessibleObject<?>) obj;
        if (owner == null) {
            if (other.owner != null) {
                return false;
            }
        } else if (!owner.equals(other.owner)) {
            return false;
        }
        if (typeVariables == null) {
            if (other.typeVariables != null) {
                return false;
            }
        } else if (!typeVariables.equals(other.typeVariables)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((owner == null) ? 0 : owner.hashCode());
        result = prime * result + ((typeVariables == null) ? 0 : typeVariables.hashCode());
        return result;
    }
}
