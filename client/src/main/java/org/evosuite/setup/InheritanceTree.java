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

package org.evosuite.setup;

import org.evosuite.classpath.ResourceList;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.graph.EdgeReversedGraph;
import org.jgrapht.traverse.BreadthFirstIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * This class represents the inheritance tree of the classes in the classpath.
 *
 * @author Gordon Fraser
 */
public class InheritanceTree {

    private static final Logger logger = LoggerFactory.getLogger(InheritanceTree.class);

    private final Map<String, Set<String>> subclassCache = new LinkedHashMap<>();

    private Set<String> interfacesSet = new LinkedHashSet<>();
    private Set<String> abstractClassesSet = new LinkedHashSet<>();

    private Map<String, Set<String>> analyzedMethods = new LinkedHashMap<>();

    private DirectedMultigraph<String, DefaultEdge> inheritanceGraph = new DirectedMultigraph<>(
            DefaultEdge.class);

    private transient boolean missingClassDetected = false;

    private Object readResolve() {
        if (analyzedMethods == null) {
            analyzedMethods = new LinkedHashMap<>();
        }
        if (interfacesSet == null) {
            interfacesSet = new LinkedHashSet<>();
        }
        if (abstractClassesSet == null) {
            abstractClassesSet = new LinkedHashSet<>();
        }
        if (inheritanceGraph == null) {
            inheritanceGraph = new DirectedMultigraph<>(DefaultEdge.class);
        }
        return this;
    }

    public boolean hasMissingClasses() {
        return missingClassDetected;
    }

    /**
     * Reset runtime-only caches and flags that can become stale across analysis phases.
     */
    public void resetRuntimeState() {
        subclassCache.clear();
        missingClassDetected = false;
    }

    public boolean isClassDefined(String className) {
        return analyzedMethods.containsKey(className);
    }

    public boolean isInterface(String classname) {
        return interfacesSet.contains(classname);
    }

    public boolean isAbstractClass(String classname) {
        return abstractClassesSet.contains(classname);
    }

    /**
     * Register a class as an abstract class.
     *
     * @param abstractClassName the name of the abstract class
     */
    public void registerAbstractClass(String abstractClassName) {
        abstractClassesSet.add(ResourceList.getClassNameFromResourcePath(abstractClassName));
    }

    /**
     * Register a class as an interface.
     *
     * @param interfaceName the name of the interface
     */
    public void registerInterface(String interfaceName) {
        interfacesSet.add(ResourceList.getClassNameFromResourcePath(interfaceName));
    }

    /**
     * Determine if a method is defined in a class.
     *
     * @param className the class name
     * @param methodNameWdescriptor the method name with descriptor
     * @return true if the method is defined
     */
    public boolean isMethodDefined(String className, String methodNameWdescriptor) {
        if (analyzedMethods.get(className) == null) {
            return false;
        }
        return analyzedMethods.get(className).contains(methodNameWdescriptor);
    }

    /**
     * Determine if a method is defined in a class.
     *
     * @param className the class name
     * @param methodName the method name
     * @param descriptor the method descriptor
     * @return true if the method is defined
     */
    public boolean isMethodDefined(String className, String methodName, String descriptor) {
        if (analyzedMethods.get(className) == null) {
            return false;
        }
        return analyzedMethods.get(className).contains(methodName + descriptor);
    }

    /**
     * Add a method to the list of analyzed methods.
     *
     * @param classname the class name
     * @param methodname the method name
     * @param descriptor the method descriptor
     */
    public void addAnalyzedMethod(String classname, String methodname, String descriptor) {
        classname = classname.replace(File.separator, ".");
        Set<String> tmp = analyzedMethods.get(classname);
        if (tmp == null) {
            analyzedMethods.put(classname, tmp = new LinkedHashSet<>());
        }
        tmp.add(methodname + descriptor);
    }


    /**
     * Add a superclass relationship to the inheritance tree.
     *
     * @param className the class name
     * @param superName the superclass name
     * @param access the access modifiers
     */
    public void addSuperclass(String className, String superName, int access) {
        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);
        String superNameWithDots = ResourceList.getClassNameFromResourcePath(superName);

        inheritanceGraph.addVertex(classNameWithDots);
        inheritanceGraph.addVertex(superNameWithDots);
        inheritanceGraph.addEdge(superNameWithDots, classNameWithDots);
    }

    /**
     * Add an interface relationship to the inheritance tree.
     *
     * @param className the class name
     * @param interfaceName the interface name
     */
    public void addInterface(String className, String interfaceName) {
        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);
        String interfaceNameWithDots = ResourceList.getClassNameFromResourcePath(interfaceName);

        inheritanceGraph.addVertex(classNameWithDots);
        inheritanceGraph.addVertex(interfaceNameWithDots);
        inheritanceGraph.addEdge(interfaceNameWithDots, classNameWithDots);
        interfacesSet.add(interfaceNameWithDots);
    }

    /**
     * Get all subclasses of a given class.
     *
     * @param className the class name
     * @return the set of subclasses
     */
    public Set<String> getSubclasses(String className) {
        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);

        if (subclassCache.containsKey(classNameWithDots)) {
            return subclassCache.get(classNameWithDots);
        }

        if (!inheritanceGraph.containsVertex(classNameWithDots)) {
            missingClassDetected = true;
            logger.debug("Class not in inheritance graph: " + classNameWithDots);
            return new LinkedHashSet<>();
        }

        // TreeSet so that classes are sorted by name and thus deterministic across platforms
        Set<String> result = new TreeSet<>();
        BreadthFirstIterator<String, DefaultEdge> bfi = new BreadthFirstIterator<>(
                inheritanceGraph, classNameWithDots);
        while (bfi.hasNext()) {
            result.add(bfi.next());
        }
        subclassCache.put(classNameWithDots, result);
        return result;
    }

    /**
     * Get all superclasses of a given class.
     *
     * @param className the class name
     * @return the set of superclasses
     */
    public Set<String> getSuperclasses(String className) {
        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);
        if (!inheritanceGraph.containsVertex(classNameWithDots)) {
            missingClassDetected = true;
            logger.debug("Class not in inheritance graph: " + classNameWithDots);
            return new LinkedHashSet<>();
        }
        EdgeReversedGraph<String, DefaultEdge> reverseGraph = new EdgeReversedGraph<>(
                inheritanceGraph);

        // TreeSet so that classes are sorted by name and thus deterministic across platforms
        Set<String> result = new TreeSet<>();
        BreadthFirstIterator<String, DefaultEdge> bfi = new BreadthFirstIterator<>(
                reverseGraph, classNameWithDots);
        while (bfi.hasNext()) {
            result.add(bfi.next());
        }
        return result;
    }

    /**
     * Get a list of superclasses in order.
     *
     * @param className the class name
     * @return the ordered list of superclasses
     */
    public List<String> getOrderedSuperclasses(String className) {
        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);
        if (!inheritanceGraph.containsVertex(classNameWithDots)) {
            missingClassDetected = true;
            logger.debug("Class not in inheritance graph: " + classNameWithDots);
            return new LinkedList<>();
        }
        EdgeReversedGraph<String, DefaultEdge> reverseGraph = new EdgeReversedGraph<>(
                inheritanceGraph);
        List<String> orderedList = new LinkedList<>();
        BreadthFirstIterator<String, DefaultEdge> bfi = new BreadthFirstIterator<>(
                reverseGraph, classNameWithDots);
        while (bfi.hasNext()) {
            orderedList.add(bfi.next());
        }
        return orderedList;
    }


    /**
     * Get all classes in the inheritance tree.
     *
     * @return the set of all classes
     */
    public Set<String> getAllClasses() {
        return inheritanceGraph.vertexSet();
    }

    /**
     * Remove a class from the inheritance tree.
     *
     * @param className the name of the class to remove
     */
    public void removeClass(String className) {
        inheritanceGraph.removeVertex(className);
    }

    /**
     * Determine if a class is in the inheritance tree.
     *
     * @param className the class name
     * @return true if the class is in the tree
     */
    public boolean hasClass(String className) {
        return inheritanceGraph.containsVertex(className);
    }

    /**
     * Get the number of classes in the inheritance tree.
     *
     * @return the number of classes
     */
    public int getNumClasses() {
        return inheritanceGraph.vertexSet().size();
    }

}
