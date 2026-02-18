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
package org.evosuite.seeding;

import com.googlecode.gentyref.GenericTypeReflector;
import org.apache.commons.lang3.tuple.Pair;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.setup.*;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericClassUtils;
import org.evosuite.utils.generic.GenericUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Comparator.comparingInt;

/**
 * Searches primarily for classes that are used in casts in the ByteCode,
 * because typically at casts, the java compiler did type erasure.
 */
public class CastClassManager {

    static final Logger logger = LoggerFactory.getLogger(CastClassManager.class);
    private static final CastClassManager instance = new CastClassManager();

    /**
     * Store the cast classes in a sorted data structure to prevent multiple sorts on the same set of classes.
     */
    private final Prioritization<GenericClass<?>> prioritization =
            new Prioritization<>(comparingInt(GenericClass::getNumParameters));

    // Private constructor due to singleton pattern, use getInstance() instead
    private CastClassManager() {
        initDefaultClasses();
    }

    public static CastClassManager getInstance() {
        return instance;
    }


    /**
     * Chooses and returns a class among the given ones. Classes at the front of the list will
     * have a higher chance of being chosen than those at the back.
     *
     * @param candidates list of candidates to choose from
     * @return a candidate from the list of candidates
     */
    public static GenericClass<?> selectClass(List<GenericClass<?>> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Cannot select from an empty list of candidates");
        }

        double r = Randomness.nextDouble();
        double d = Properties.RANK_BIAS
                - Math.sqrt((Properties.RANK_BIAS * Properties.RANK_BIAS)
                - (4.0 * (Properties.RANK_BIAS - 1.0) * r));
        int length = candidates.size();

        d = d / 2.0 / (Properties.RANK_BIAS - 1.0);

        int index = Math.min((int) (length * d), length - 1);
        return candidates.get(index);
    }

    /**
     * Requests the concrete classes from the {@link ConcreteClassAnalyzer} for a given class.
     *
     * @param clazz the class.
     * @return A collection containing {@code clazz} and the concrete classes.
     */
    private static Collection<Class<?>> withConcreteClasses(Class<?> clazz) {
        final InheritanceTree inheritanceTree = DependencyAnalysis.getInheritanceTree();
        Set<Class<?>> candidates = new HashSet<>(ConcreteClassAnalyzer.getInstance().getConcreteClasses(clazz,
                inheritanceTree));
        candidates.add(clazz);
        return candidates;
    }

    /**
     * Add a cast class to this manager.
     * The class is loaded with the class loader from the {@link TestGenerationContext}.
     *
     * <p>{@code className} is the binary name of the class as specified by the Java Language Specification.
     *
     * <p>From the oracle documentation of binary names:
     * Examples of valid class names include:
     *
     * <p>"java.lang.String"
     * "javax.swing.JSpinner$DefaultEditor"
     * "java.security.KeyStore$Builder$FileBuilder$1"
     * "java.net.URLClassLoader$3$1"
     *
     * @param className The binary name of the class
     * @param depth     The secondary sorting criteria for the cast classes.
     */
    public void addCastClass(String className, int depth) {
        final ClassLoader cl = TestGenerationContext.getInstance().getClassLoaderForSUT();
        final Class<?> clazz;
        try {
            clazz = cl.loadClass(className);
        } catch (ClassNotFoundException e) {
            // Ignore
            logger.debug("Could not load cast class {}: {}", className, e.getMessage());
            return;
        }
        final GenericClass<?> castClazz = GenericClassFactory.get(clazz);
        addCastClass(castClazz.getWithWildcardTypes(), depth);
    }

    /**
     * Converts the given {@code type} to a {@link GenericClass} and passes it
     * to {@link CastClassManager#addCastClass(GenericClass, int)}.
     *
     * @param type  The type to be converted and added
     * @param depth The secondary sorting criteria for the cast classes.
     */
    public void addCastClass(Type type, int depth) {
        GenericClass<?> castClazz = GenericClassFactory.get(type);
        addCastClass(castClazz.getWithWildcardTypes(), depth);
    }

    /**
     * Adds a given {@link GenericClass} to this manager.
     *
     * <p>If the class is abstract, this method searches for concrete classes in the {@link InheritanceTree}.
     * {@link TestUsageChecker#canUse(Type)} is used to check whether EvoSuite can use the class in tests.
     *
     * @param clazz The class to be added to this cast class manager.
     * @param depth The secondary sorting criteria for the cast classes.
     */
    public void addCastClass(final GenericClass<?> clazz, final int depth) {
        logger.debug("Adding cast class for {}", clazz.getSimpleName());
        final Class<?> rawClass = clazz.getRawClass();

        if (rawClass == null) {
            throw new IllegalArgumentException("Cannot add cast class: rawClass is null for " + clazz);
        }

        // If we have an abstract class, try to find concrete subclasses we can use instead.
        if (clazz.isAbstract()) {
            final ConcreteClassAnalyzer analyzer = ConcreteClassAnalyzer.getInstance();
            final InheritanceTree tree = TestCluster.getInheritanceTree();
            final Set<Class<?>> concreteClasses = analyzer.getConcreteClasses(rawClass, tree);

            for (final Class<?> concreteClass : concreteClasses) {
                final GenericClass<?> c = GenericClassFactory.get(concreteClass).getWithWildcardTypes();
                if (TestUsageChecker.canUse(c.getRawClass())) {
                    logger.debug("Adding concrete class {} for abstract class {}", c.getSimpleName(), clazz);
                    putCastClass(c, depth);
                }
            }

            // If mocking is enabled, we can simply mock the abstract class in the generated tests.
            if (Properties.P_FUNCTIONAL_MOCKING > 0.0) {
                if (TestUsageChecker.canUse(rawClass)) {
                    logger.debug("Abstract class {} is functional mockable", clazz.getSimpleName());
                    putCastClass(clazz, depth);
                }
            }
        } else if (TestUsageChecker.canUse(rawClass)) {
            logger.debug("Adding concrete class {}", clazz);
            putCastClass(clazz.getWithWildcardTypes(), depth);
        }
    }

    /**
     * Selects a cast class for a type variable.
     *
     * @param typeVariable     the type variable to resolve
     * @param allowRecursion   whether recursive types are allowed
     * @param ownerVariableMap the mapping of type variables
     * @return the selected cast class
     * @throws ConstructionFailedException if no assignable class is found
     */
    public GenericClass<?> selectCastClass(final TypeVariable<?> typeVariable, final boolean allowRecursion,
                                           final Map<TypeVariable<?>, Type> ownerVariableMap)
            throws ConstructionFailedException {
        final Map<TypeVariable<?>, Type> sanitizedOwnerVariableMap = sanitizeOwnerVariableMap(ownerVariableMap);
        logger.debug("Selecting cast class for type variable {} with bounds {}, owner var map: {}",
                typeVariable, Arrays.toString(typeVariable.getBounds()),
                GenericUtils.stableTypeVariableMapToString(sanitizedOwnerVariableMap));
        GenericDeclaration genericDeclaration = typeVariable.getGenericDeclaration();
        String declarationSimpleName = "<Unknown generic declaration>";
        if (genericDeclaration instanceof Class<?>) {
            declarationSimpleName = ((Class<?>) genericDeclaration).getSimpleName();
        } else if (genericDeclaration instanceof Method) {
            declarationSimpleName = ((Method) genericDeclaration).getDeclaringClass().getSimpleName() + "#"
                    + ((Method) genericDeclaration).getName();
        } else if (genericDeclaration instanceof Constructor) {
            declarationSimpleName = ((Constructor) genericDeclaration).getDeclaringClass().getSimpleName() + "#"
                    + "<init>";
        }
        List<GenericClass<?>> assignableClasses = getAssignableClasses(typeVariable, allowRecursion,
                sanitizedOwnerVariableMap);

        logger.debug("Found {} assignable classes for type variable {}", assignableClasses.size(), typeVariable);

        // If no non-recursive candidate is found, retry with recursive candidates.
        // This is necessary for owner-parameterized member types (e.g. Outer<E>.Inner),
        // which can be valid bounds but are filtered out by the non-recursive check.
        if (assignableClasses.isEmpty() && allowRecursion) {
            assignableClasses = getAssignableClasses(typeVariable, true, sanitizedOwnerVariableMap);
        }

        if (assignableClasses.isEmpty()) {
            logger.debug("No assignable classes found, attempting to add one for type variable {}", typeVariable);
            GenericClass<?> genericClass = addAssignableClass(typeVariable, sanitizedOwnerVariableMap);
            if (genericClass != null) {
                assignableClasses = getAssignableClasses(typeVariable, allowRecursion, sanitizedOwnerVariableMap);
                if (assignableClasses.isEmpty() && allowRecursion) {
                    assignableClasses = getAssignableClasses(typeVariable, true, sanitizedOwnerVariableMap);
                }
                if (assignableClasses.isEmpty()) {
                    logger.warn("No assignable class found after adding {} for type variable {} with bounds {} "
                                    + "of declaration {}. Owner var map: {}",
                            genericClass, typeVariable, Arrays.toString(typeVariable.getBounds()),
                            declarationSimpleName,
                            GenericUtils.stableTypeVariableMapToString(sanitizedOwnerVariableMap));
                    throw new ConstructionFailedException("Nothing is assignable to " + typeVariable);
                }
            } else {
                logger.warn("No assignable class could be added for type variable {} with bounds {}",
                        typeVariable, Arrays.toString(typeVariable.getBounds()));
                throw new ConstructionFailedException("Nothing is assignable to " + typeVariable);
            }
        }
        logger.debug("Selecting from {} assignable classes for type variable {}", assignableClasses.size(),
                typeVariable);

        return selectClass(assignableClasses);
    }

    /**
     * Selects a cast class for a wildcard type.
     *
     * @param wildcardType     the wildcard type to resolve
     * @param allowRecursion   whether recursive types are allowed
     * @param ownerVariableMap the mapping of type variables
     * @return the selected cast class
     * @throws ConstructionFailedException if no assignable class is found
     */
    public GenericClass<?> selectCastClass(final WildcardType wildcardType, final boolean allowRecursion,
                                           Map<TypeVariable<?>, Type> ownerVariableMap)
            throws ConstructionFailedException {
        logger.debug("Getting assignable classes for wildcard {}", wildcardType);
        List<GenericClass<?>> assignableClasses = getAssignableClasses(wildcardType, false, ownerVariableMap);
        logger.debug("Found {} assignable classes for wildcard {}", assignableClasses.size(), wildcardType);

        // If we were not able to find an assignable class without recursive types
        // we try again but allowing recursion
        if (assignableClasses.isEmpty()) {
            List<GenericClass<?>> recursiveAssignable = getAssignableClasses(wildcardType, true, ownerVariableMap);
            if (allowRecursion) {
                assignableClasses.addAll(recursiveAssignable);
            } else if (!recursiveAssignable.isEmpty()) {
                logger.debug("Falling back to recursive assignable classes for wildcard {} despite depth limit: {}",
                        wildcardType, recursiveAssignable);
                assignableClasses = recursiveAssignable;
            }
        }

        if (assignableClasses.isEmpty()) {
            logger.debug("No assignable classes found, attempting to add one for wildcard {}", wildcardType);

            GenericClass<?> genericClass = addAssignableClass(wildcardType, ownerVariableMap);
            if (genericClass != null) {
                assignableClasses = getAssignableClasses(wildcardType, allowRecursion, ownerVariableMap);

                if (assignableClasses.isEmpty()) {
                    List<GenericClass<?>> recursiveAssignable = getAssignableClasses(wildcardType, true,
                            ownerVariableMap);
                    if (!recursiveAssignable.isEmpty()) {
                        logger.debug("No non-recursive class assignable after adding {} for wildcard {}. "
                                        + "Using recursive assignable classes: {}",
                                genericClass, wildcardType, recursiveAssignable);
                        assignableClasses = recursiveAssignable;
                    } else {
                        logger.warn("Nothing is assignable after adding {} for wildcard {}", genericClass, wildcardType);
                    }
                    if (assignableClasses.isEmpty()) {
                        throw new ConstructionFailedException("Nothing is assignable to " + wildcardType);
                    }
                }
            } else {
                logger.warn("No assignable class could be added for wildcard {}", wildcardType);
                throw new ConstructionFailedException("Nothing is assignable to " + wildcardType);
            }
        }

        logger.debug("Selecting from {} assignable classes for wildcard {}", assignableClasses.size(), wildcardType);
        return selectClass(assignableClasses);
    }

    /**
     * Get a view on the contained classes.
     *
     * @return the view.
     */
    public Set<GenericClass<?>> getCastClasses() {
        return Collections.unmodifiableSet(prioritization.getElements());
    }

    /**
     * Clears all mappings.
     */
    public void clear() {
        prioritization.clear();
        initDefaultClasses();
    }

    /**
     * Fills the class map with some default classes.
     */
    private void initDefaultClasses() {
        putCastClass(GenericClassFactory.get(Object.class), 10);
        putCastClass(GenericClassFactory.get(String.class), 1);
        putCastClass(GenericClassFactory.get(Integer.class), 1);
        putCastClass(GenericClassFactory.get(java.util.LinkedList.class), 1);
        putCastClass(GenericClassFactory.get(java.util.ArrayList.class), 1);
    }

    /**
     * Filters the analyzed classes of the test cluster for assignable classes.
     *
     * <p>Additionally to {@code filter}, only classes that can be used in test cases are returned
     * ({@link TestUsageChecker#canUse(Type)}).
     *
     * @param filter A predicate whether a class is assignable.
     * @return The set of classes that match the aforementioned criteria.
     */
    private Set<Class<?>> getAssignableClassesFromTestCluster(Predicate<Class<?>> filter) {
        // TODO why is this function deprecated? Because it is an accessor? Shall we replace it with a view and make it
        // not deprecated anymore
        final Set<Class<?>> classes = TestCluster.getInstance().getAnalyzedClasses();
        return classes.stream() //
                .filter(TestUsageChecker::canUse) //
                .filter(filter) //
                .collect(Collectors.toSet());
    }

    /**
     * Filters the values of {@code typeMap} for assignable classes.
     *
     * <p>Additionally to {@code filter}, only classes that can be used in test cases are returned
     * ({@link TestUsageChecker#canUse(Type)}.
     *
     * @param filter  A predicate whether a class is assignable.
     * @param typeMap the type map to be filtered.
     * @return The set of classes that match the aforementioned criteria.
     */
    private Set<Class<?>> getAssignableClassesFromTypeVariableMap(Predicate<Class<?>> filter,
                                                                  Map<TypeVariable<?>, Type> typeMap) {
        return typeMap.values().stream() //
                .filter(t -> !(t instanceof WildcardType)) //
                .map(GenericTypeReflector::erase) //
                .filter(TestUsageChecker::canUse) //
                .filter(filter) //
                .collect(Collectors.toSet());
    }

    /**
     * Convert given boundaries to a Pair&lt;{@link GenericClass}, {@link Class}&gt;, where the class is the
     * erasure of the generic class.
     *
     * <p>Before computing the resulting pair, the types are mapped with the function {@code replaceTypeVariable}
     * that shall replace type variables with their instantiation.
     *
     * <p>Additionally, a pair is only returned, if the erasure is usable in test cases according to
     * {@link TestUsageChecker#canUse(Type)}.
     *
     * @param bounds              The bounds to be converted.
     * @param replaceTypeVariable The function to replace type variables.
     * @return The set of pairs meeting the aforementioned criteria.
     */
    private Set<Pair<GenericClass<?>, Class<?>>> getBoundariesWithGenericClass(Type[] bounds,
            Function<Type, Type> replaceTypeVariable) {
        final Function<Type, Pair<Type, Class<?>>> getWithErasure = t -> Pair.of(t, GenericTypeReflector.erase(t));
        final Function<Pair<Type, Class<?>>, Pair<GenericClass<?>, Class<?>>> convertTypeToGenericClass =
                p -> Pair.of(GenericClassFactory.get(p.getLeft()), p.getRight());
        return Arrays.stream(bounds) //
                .map(replaceTypeVariable) //
                .map(getWithErasure) //
                .filter(p -> TestUsageChecker.canUse(p.getRight())) //
                .map(convertTypeToGenericClass) //
                .collect(Collectors.toSet());
    }

    /**
     * Select from a Collection of assignable classes one element and add it to the class map, if at least
     * one assignable class is in the collection.
     *
     * @param assignableClasses the collection of assignable classes.
     * @param priority          the priority stored in the class map.
     * @return The class that has been added. Null if no class has been added.
     */
    private GenericClass<?> addToClassMapIfNotEmpty(Collection<Class<?>> assignableClasses, int priority) {
        if (!assignableClasses.isEmpty()) {
            // Ensure deterministic selection across JVM runs by stabilizing iteration order.
            List<Class<?>> sortedAssignableClasses = new ArrayList<>(assignableClasses);
            sortedAssignableClasses.sort(Comparator.comparing(Class::getName));
            final Class<?> choice = Randomness.choice(sortedAssignableClasses);
            final GenericClass<?> castClass = GenericClassFactory.get(choice);
            logger.debug("Adding cast class {}", castClass);
            putCastClass(castClass, priority);
            return castClass;
        }
        return null;
    }

    /**
     * Compute the candidate instantiations from the boundaries of a {@link WildcardType}.
     * Type variables are replaced with their value stored in {@param typeMap} (if present).
     *
     * @param wildcardType The boundaries of this type are looked at.
     * @param typeMap      The type map for the instantiation.
     * @return A set of the candidate boundaries.
     */
    private Set<Pair<GenericClass<?>, Class<?>>> candidateBoundariesForWildcard(WildcardType wildcardType,
                                                                                Map<TypeVariable<?>, Type> typeMap) {
        final Function<Type, Type> replaceTypeVariable = t -> t instanceof TypeVariable && typeMap.containsKey(t)
                ? typeMap.get(t) : t;

        return getBoundariesWithGenericClass(wildcardType.getUpperBounds(), replaceTypeVariable);
    }

    /**
     * Filter a set of candidate boundaries for the ones with at least one type variable.
     * Additionally, only assignable candidates are returned.
     *
     * @param candidateBounds     the set of candidate bounds.
     * @param satisfiesBoundaries predicate to decide whether a {@link GenericClass} is assignable.
     * @return The assignable candidate bounds with at least one type variable.
     */
    private Set<Class<?>> onlyAssignableAllowTypeVariables(
            Set<Pair<GenericClass<?>, Class<?>>> candidateBounds,
            Predicate<Pair<GenericClass<?>, Class<?>>> satisfiesBoundaries) {
        return candidateBounds.stream() //
                .filter(p -> p.getLeft().hasTypeVariables()) //
                .filter(satisfiesBoundaries) //
                .map(Pair::getRight) //
                .collect(Collectors.toSet());
    }

    /**
     * Filter a set of candidate boundaries for the ones without type variables.
     * A candidate bound is a Pair of {@link GenericClass} and the corresponding {@link Class}
     *
     * @param candidateBounds the set of candidate bounds.
     * @return The candidate bounds without type variables.
     */
    private Set<Pair<GenericClass<?>, Class<?>>> onlyAssignableForbidTypeVariables(
            Set<Pair<GenericClass<?>, Class<?>>> candidateBounds) {
        return candidateBounds.stream() //
                .filter(p -> !p.getLeft().hasTypeVariables()) //
                .collect(Collectors.toSet());
    }

    /**
     * Add an assignable class for a wildcard type for a given type variable map.
     *
     * <p>A type is only added to the classMap, if it is usable according to {@link TestUsageChecker#canUse(Type)}.
     *
     * @param wildcardType the wildcard type to be instantiated.
     * @param typeMap      the type map.
     * @return The assignable class that was added. Null if none was added.
     */
    private GenericClass<?> addAssignableClass(final WildcardType wildcardType,
                                               final Map<TypeVariable<?>, Type> typeMap) {
        // Predicate to decide if a class is assignable to the wildcard type
        final Predicate<Class<?>> isAssignableToWildcard =
                c -> GenericClassFactory.get(c).getWithWildcardTypes().satisfiesBoundaries(wildcardType, typeMap);


        // Filter what classes from the TestCluster can be assigned to the wildcard type
        Set<Class<?>> assignableClassesFromTestCluster = getAssignableClassesFromTestCluster(isAssignableToWildcard);
        final Set<Class<?>> assignableClasses = new LinkedHashSet<>(assignableClassesFromTestCluster);
        logger.debug("Found {} assignable classes from test cluster for wildcard",
                assignableClassesFromTestCluster.size());

        // Filter from the value set of the type variable map.
        Set<? extends Class<?>> assignableTypeVariables =
                getAssignableClassesFromTypeVariableMap(isAssignableToWildcard, typeMap);
        assignableClasses.addAll(assignableTypeVariables);
        logger.debug("Found {} assignable classes from type variable map for wildcard",
                assignableTypeVariables.size());

        Set<Pair<GenericClass<?>, Class<?>>> candidateBounds = candidateBoundariesForWildcard(wildcardType, typeMap);

        // Compute boundaries with type variables that are assignable to the wildcard
        Set<Class<?>> assignableBoundariesWithTypeVariables = onlyAssignableAllowTypeVariables(candidateBounds,
                p -> p.getLeft().getWithWildcardTypes().satisfiesBoundaries(wildcardType, typeMap));

        // Compute boundaries without type variables that are assignable to the wildcard.
        Set<Pair<GenericClass<?>, Class<?>>> assignableBoundariesWithoutTypeVariables =
                onlyAssignableForbidTypeVariables(candidateBounds);

        logger.debug("From the upper bounds of the wildcard type {} are {} assignable and have type variables",
                Arrays.toString(wildcardType.getUpperBounds()), assignableBoundariesWithTypeVariables);
        logger.debug("From the upper bounds of the wildcard type {} are {} assignable and have no type variables. "
                        + "Those are added directly", Arrays.toString(wildcardType.getUpperBounds()),
                assignableBoundariesWithoutTypeVariables);

        assignableClasses.addAll(assignableBoundariesWithoutTypeVariables.stream()
                .map(Pair::getRight).collect(Collectors.toSet()));
        assignableClasses.addAll(assignableBoundariesWithTypeVariables);
        assignableBoundariesWithoutTypeVariables.stream().map(Pair::getLeft).forEach(gc -> putCastClass(gc, 10));

        logger.debug("Found {} total assignable classes for wildcard {}", assignableClasses.size(), wildcardType);

        // random selection of the assignable classes is added to class map with priority 10
        return addToClassMapIfNotEmpty(assignableClasses, 10);
    }

    /**
     * Check if a class is assignable to the type variable.
     * If the class is Parameterized type containing the type variable it is considered not assignable.
     *
     * @param typeVariable the type variable to be resolved.
     * @param clazz        the class to be checked.
     * @return Whether the class can be used as the type variable.
     */
    private boolean classIsAssignable(TypeVariable<?> typeVariable, Class<?> clazz) {
        for (final Type bound : typeVariable.getBounds()) {
            if (GenericTypeReflector.erase(bound).equals(Enum.class) && clazz.isEnum()) {
                continue;
            }

            if (!GenericClassUtils.isAssignable(bound, clazz)) {
                return false;
            }

            if (bound instanceof ParameterizedType) {
                final Type[] typeArgs = ((ParameterizedType) bound).getActualTypeArguments();
                if (Arrays.asList(typeArgs).contains(typeVariable)) {
                    return false;
                }
            }
        }
        return true;
    }


    /**
     * Add an assignable class for a type variable for a given type variable map.
     *
     * <p>A type is only added to the classMap, if it is usable according to {@link TestUsageChecker#canUse(Type)}.
     *
     * @param typeVariable the type variable to be instantiated.
     * @param typeMap      the type map.
     * @return Whether an assignable class was added.
     */
    private GenericClass<?> addAssignableClass(final TypeVariable<?> typeVariable,
                                               final Map<TypeVariable<?>, Type> typeMap) {
        // Predicate to decide if a class is assignable to the wildcard type
        final Predicate<Class<?>> satisfiesBoundaries =
                c -> GenericClassFactory.get(c).getWithWildcardTypes().satisfiesBoundaries(typeVariable, typeMap);

        // Filter what classes from the TestCluster can be assigned to the wildcard type
        Set<Class<?>> assignableClassesFromTestCluster = getAssignableClassesFromTestCluster(satisfiesBoundaries);
        logger.debug("Found {} assignable classes from test cluster for type variable {}",
                assignableClassesFromTestCluster.size(), typeVariable);

        final Set<Class<?>> assignableClasses = new LinkedHashSet<>(assignableClassesFromTestCluster);

        // Filter from the value set of the type variable map.
        Set<? extends Class<?>> assignableTypeVariables =
                getAssignableClassesFromTypeVariableMap(satisfiesBoundaries, typeMap);
        assignableClasses.addAll(assignableTypeVariables);
        logger.debug("Found {} assignable classes from type variable map for type variable {}",
                assignableTypeVariables.size(), typeVariable);

        GenericClass<?> genericClass = addToClassMapIfNotEmpty(assignableClasses, 10);
        if (genericClass != null) {
            return genericClass;
        }

        // Compute the bound candidates of the type variable.
        final Set<Class<?>> boundCandidates = Arrays.stream(typeVariable.getBounds()) //
                .map(GenericTypeReflector::erase) //
                .filter(Objects::nonNull) //
                .map(CastClassManager::withConcreteClasses) //
                .flatMap(Collection::stream) //
                .collect(Collectors.toSet());

        logger.debug("Bound candidate for the type variable are: {}", boundCandidates);

        // Filter the bound candidates such that only the assignable remain.
        Set<Class<?>> assignableBoundCandidates = boundCandidates.stream() //
                .filter(TestUsageChecker::canUse) //
                .filter(c -> classIsAssignable(typeVariable, c)) //
                .collect(Collectors.toSet());
        assignableClasses.addAll(assignableBoundCandidates);

        logger.debug("Found {} total assignable classes for type variable {} after adding bounds",
                assignableClasses.size(), typeVariable);

        // random selection of the assignable classes is added to class map with priority 10
        return addToClassMapIfNotEmpty(assignableClasses, 10);
    }

    /**
     * Search for the assignable classes for a wildcard type stored in this manager.
     * The elements are sorted first by the number of parameters, then by the assigned priority.
     *
     * @param wildcardType     The wildcard type to be resolved.
     * @param allowRecursion   Whether classes are allowed to contain type variables or wildcards.
     * @param ownerVariableMap A mapping from the type variable to the type of the owner.
     * @return Sorted list of classes being assignable (may be empty).
     */
    private List<GenericClass<?>> getAssignableClasses(final WildcardType wildcardType, final boolean allowRecursion,
            final Map<TypeVariable<?>, Type> ownerVariableMap) {
        // Filter, whether a class is assignable.
        Predicate<GenericClass<?>> keepClass =
                gc -> gc.satisfiesBoundaries(wildcardType, ownerVariableMap)
                        && (allowRecursion || !gc.hasWildcardOrTypeVariables());
        return prioritization.toSortedList(keepClass);
    }

    /**
     * Search for the assignable classes for a wildcard type stored in this manager.
     * The elements are sorted first by the number of parameters, then by the assigned priority.
     *
     * @param typeVariable     The type variable to be resolved.
     * @param allowRecursion   Whether classes are allowed to contain type variables or wildcards.
     * @param ownerVariableMap A mapping from the type variable to the type of the owner.
     * @return Sorted list of classes being assignable (may be empty).
     */
    private List<GenericClass<?>> getAssignableClasses(final TypeVariable<?> typeVariable,
            final boolean allowRecursion, final Map<TypeVariable<?>, Type> ownerVariableMap) {
        Predicate<GenericClass<?>> keepClass =
                gc -> gc.satisfiesBoundaries(typeVariable, ownerVariableMap)
                        && (allowRecursion || !gc.hasWildcardOrTypeVariables());
        return prioritization.toSortedList(keepClass);
    }

    private void putCastClass(GenericClass<?> clazz, int priority) {
        prioritization.add(clazz, priority);
    }

    /**
     * Remove self-referential type-variable entries (e.g. E -> E) as they do not add information
     * and can make assignability checks spuriously fail.
     */
    private static Map<TypeVariable<?>, Type> sanitizeOwnerVariableMap(Map<TypeVariable<?>, Type> ownerVariableMap) {
        Map<TypeVariable<?>, Type> sanitized = new LinkedHashMap<>(ownerVariableMap);
        sanitized.entrySet().removeIf(e -> e.getValue() instanceof TypeVariable<?> && e.getKey().equals(e.getValue()));
        return sanitized;
    }

}
