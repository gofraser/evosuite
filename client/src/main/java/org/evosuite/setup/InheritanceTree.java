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
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.graph.EdgeReversedGraph;
import org.jgrapht.traverse.BreadthFirstIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * Represents the inheritance tree of the classes under test.
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

    /**
     * Checks if the class is defined in the tree.
     *
     * @param className the name of the class
     * @return true if the class is defined
     */
    public boolean isClassDefined(String className) {
        return analyzedMethods.containsKey(className);
    }

    /**
     * Checks if the class is an interface.
     *
     * @param classname the name of the class
     * @return true if the class is an interface
     */
    public boolean isInterface(String classname) {
        return interfacesSet.contains(classname);
    }

    /**
     * Checks if the class is an abstract class.
     *
     * @param classname the name of the class
     * @return true if the class is an abstract class
     */
    public boolean isAbstractClass(String classname) {
        return abstractClassesSet.contains(classname);
    }

    /**
     * Registers an abstract class.
     *
     * @param abstractClassName the name of the abstract class
     */
    public void registerAbstractClass(String abstractClassName) {
        abstractClassesSet.add(ResourceList.getClassNameFromResourcePath(abstractClassName));
    }

    /**
     * Registers an interface.
     *
     * @param interfaceName the name of the interface
     */
    public void registerInterface(String interfaceName) {
        interfacesSet.add(ResourceList.getClassNameFromResourcePath(interfaceName));
    }

    /**
     * Checks if the method is defined in the class.
     *
     * @param className             the name of the class
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
     * Checks if the method is defined in the class.
     *
     * @param className  the name of the class
     * @param methodName the name of the method
     * @param descriptor the descriptor of the method
     * @return true if the method is defined
     */
    public boolean isMethodDefined(String className, String methodName, String descriptor) {
        if (analyzedMethods.get(className) == null) {
            return false;
        }
        return analyzedMethods.get(className).contains(methodName + descriptor);
    }

    /**
     * Adds an analyzed method to the tree.
     *
     * @param classname  the name of the class
     * @param methodname the name of the method
     * @param descriptor the descriptor of the method
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
     * Adds a superclass relationship to the tree.
     *
     * @param className the name of the class
     * @param superName the name of the superclass
     * @param access    the access flags
     */
    public void addSuperclass(String className, String superName, int access) {
        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);
        String superNameWithDots = ResourceList.getClassNameFromResourcePath(superName);

        inheritanceGraph.addVertex(classNameWithDots);
        inheritanceGraph.addVertex(superNameWithDots);
        inheritanceGraph.addEdge(superNameWithDots, classNameWithDots);
    }

    /**
     * Adds an interface relationship to the tree.
     *
     * @param className     the name of the class
     * @param interfaceName the name of the interface
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
     * Gets the subclasses of the given class.
     *
     * @param className the name of the class
     * @return the set of subclasses
     */
    public Set<String> getSubclasses(String className) {
        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);

        if (subclassCache.containsKey(classNameWithDots)) {
            return subclassCache.get(classNameWithDots);
        }

        if (!inheritanceGraph.containsVertex(classNameWithDots)) {
            AtMostOnceLogger.warn(logger, "Class not in inheritance graph: " + classNameWithDots);
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
     * Gets the superclasses of the given class.
     *
     * @param className the name of the class
     * @return the set of superclasses
     */
    public Set<String> getSuperclasses(String className) {
        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);
        if (!inheritanceGraph.containsVertex(classNameWithDots)) {
            AtMostOnceLogger.warn(logger, "Class not in inheritance graph: " + classNameWithDots);
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
     * Gets the ordered superclasses of the given class.
     *
     * @param className the name of the class
     * @return the list of ordered superclasses
     */
    public List<String> getOrderedSuperclasses(String className) {
        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);
        if (!inheritanceGraph.containsVertex(classNameWithDots)) {
            AtMostOnceLogger.warn(logger, "Class not in inheritance graph: " + classNameWithDots);
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
     * Gets all classes in the tree.
     *
     * @return the set of all classes
     */
    public Set<String> getAllClasses() {
        return inheritanceGraph.vertexSet();
    }

    /**
     * Removes the class from the tree.
     *
     * @param className the name of the class
     */
    public void removeClass(String className) {
        inheritanceGraph.removeVertex(className);
    }

    /**
     * Checks if the class is in the tree.
     *
     * @param className the name of the class
     * @return true if the class is in the tree
     */
    public boolean hasClass(String className) {
        return inheritanceGraph.containsVertex(className);
    }

    /**
     * Gets the number of classes in the tree.
     *
     * @return the number of classes
     */
    public int getNumClasses() {
        return inheritanceGraph.vertexSet().size();
    }

}
