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
package org.evosuite.instrumentation.testability;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ResourceList;
import org.evosuite.setup.TargetClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * <p>DescriptorMapping class.</p>
 *
 * @author Gordon Fraser
 */
public class DescriptorMapping {

    private static int id = 0;

    private final Map<String, String> descriptorMapping = new HashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(DescriptorMapping.class);

    private static DescriptorMapping instance = null;

    private DescriptorMapping() {

    }

    /**
     * <p>Getter for the field <code>instance</code>.</p>
     *
     * @return a {@link DescriptorMapping} object.
     */
    public static DescriptorMapping getInstance() {
        if (instance == null) {
            instance = new DescriptorMapping();
        }

        return instance;
    }

    final Map<String, String> originalDesc = new HashMap<>();

    final Map<String, String> originalName = new HashMap<>();

    private final Map<String, String> nameMapping = new HashMap<>();

    /**
     * <p>shouldTransform.</p>
     *
     * @param classNameUnknown a {@link java.lang.String} object.
     * @return a boolean.
     */
    public static boolean shouldTransform(String classNameUnknown) {
        String className = classNameUnknown.replace('/', '.');
        switch (Properties.TT_SCOPE) {
            case ALL:
                return true;
            case TARGET:
                if (TargetClasses.isCut(className)) {
                    return true;
                }
                break;
            case PREFIX:
                if (className.startsWith(Properties.PROJECT_PREFIX)) {
                    return true;
                }
                break;
            default:
                break;

        }
        return false;

    }

    /**
     * <p>isTransformedMethod.</p>
     *
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param desc       a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean isTransformedMethod(String className, String methodName, String desc) {
        logger.info("Initiating transformation of " + methodName);
        getMethodDesc(className, methodName, desc);
        return descriptorMapping.containsKey(className.replace('.', '/') + "/"
                + methodName + desc);
    }

    /**
     * <p>isTransformedField.</p>
     *
     * @param className a {@link java.lang.String} object.
     * @param fieldName a {@link java.lang.String} object.
     * @param desc      a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean isTransformedField(String className, String fieldName, String desc) {
        getFieldDesc(className, fieldName, desc);
        return descriptorMapping.containsKey(className.replace('.', '/') + "/"
                + fieldName + desc);
    }

    /**
     * <p>isTransformedOrBooleanMethod.</p>
     *
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param desc       a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean isTransformedOrBooleanMethod(String className, String methodName,
                                                String desc) {
        logger.info("Checking method: " + className + "." + methodName + desc);
        String newDesc = getMethodDesc(className, methodName, desc);
        logger.info("Transformed desc is " + newDesc);
        String name = className.replace('.', '/') + "/" + methodName + desc;
        if (originalDesc.containsKey(name)) {
            logger.info("Desc is already transformed");
        }
        return originalDesc.containsKey(name) || isBooleanMethod(desc);
    }

    /**
     * <p>isTransformedOrBooleanReturnMethod.</p>
     *
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param desc       a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean isTransformedOrBooleanReturnMethod(String className,
                                                      String methodName, String desc) {
        logger.info("Checking method: " + className + "." + methodName + desc);
        String newDesc = getMethodDesc(className, methodName, desc);
        logger.info("Transformed desc is " + newDesc);
        String name = className.replace('.', '/') + "/" + methodName + desc;
        if (originalDesc.containsKey(name)) {
            return Type.getReturnType(originalDesc.get(name)).equals(Type.BOOLEAN_TYPE);
        } else {
            return Type.getReturnType(desc).equals(Type.BOOLEAN_TYPE);
        }
    }

    /**
     * <p>isTransformedOrBooleanField.</p>
     *
     * @param className a {@link java.lang.String} object.
     * @param fieldName a {@link java.lang.String} object.
     * @param desc      a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean isTransformedOrBooleanField(String className, String fieldName,
                                               String desc) {
        logger.info("Checking field: " + className + "." + fieldName + desc);
        String newDesc = getFieldDesc(className, fieldName, desc);
        logger.info("Transformed desc is " + newDesc);
        String name = className.replace('.', '/') + "/" + fieldName + desc;
        if (originalDesc.containsKey(name)) {
            logger.info("Desc is already transformed");
        }
        return originalDesc.containsKey(name) || isBooleanField(desc);
    }

    /**
     * <p>isBooleanMethod.</p>
     *
     * @param desc a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean isBooleanMethod(String desc) {
        Type[] types = Type.getArgumentTypes(desc);
        for (Type type : types) {
            if (type.equals(Type.BOOLEAN_TYPE)) {
                return true;
            } else if (type.getDescriptor().equals("[Z")) {
                return true;
            }
        }

        Type type = Type.getReturnType(desc);
        if (type.equals(Type.BOOLEAN_TYPE)) {
            return true;
        } else {
            return type.getDescriptor().equals("[Z");
        }
    }

    /**
     * <p>hasBooleanParameters.</p>
     *
     * @param desc a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean hasBooleanParameters(String desc) {
        for (Type t : Type.getArgumentTypes(desc)) {
            if (t.equals(Type.BOOLEAN_TYPE)) {
                return true;
            }
        }

        return false;
    }

    private boolean isInside(String className) {
        String classNameWithDots = className.replace('/', '.');
        switch (Properties.TT_SCOPE) {
            case ALL:
                if (!classNameWithDots.startsWith("java")
                        && !classNameWithDots.startsWith("sun")) {
                    return true;
                }
                // fall through
            case TARGET:
                if (TargetClasses.isCut(classNameWithDots)) {
                    return true;
                }
                break;
            case PREFIX:
                if (classNameWithDots.startsWith(Properties.PROJECT_PREFIX)) {
                    return true;
                }
                break;
            default:
                break;

        }
        return false;

    }

    private boolean isBooleanField(String desc) {
        logger.info("Checking type of field " + desc);
        return desc.endsWith("Z");
    }

    private boolean isOutsideMethod(String className, String methodName, String desc) {
        Set<String> visited = new HashSet<>();
        Queue<String> parents = new LinkedList<>();
        parents.add(className);

        while (!parents.isEmpty()) {
            String name = parents.poll();
            if (name == null) {
                continue;
            }

            visited.add(name);
            logger.info("Visiting class " + name + " while looking for source of "
                    + className + "." + methodName);
            ClassReader reader;
            try {
                reader = new ClassReader(ResourceList.getInstance(
                        TestGenerationContext.getInstance().getClassLoaderForSUT()).getClassAsStream(name));
                ClassNode parent = new ClassNode();
                reader.accept(parent, ClassReader.EXPAND_FRAMES);

                boolean isInside = isInside(parent.name);

                logger.info("Checking " + parent.name);
                for (Object o : parent.methods) {
                    MethodNode mn2 = (MethodNode) o;
                    if (mn2.name.equals(methodName) && mn2.desc.equals(desc)) {
                        if (!isInside) {
                            logger.info("Method " + name
                                    + " was defined outside the test package");
                            return true;
                        } else {
                            logger.info("Method " + name
                                    + " was defined inside the test package");
                        }
                    }
                }

                for (Object o : parent.interfaces) {
                    String par = (String) o;
                    if (!visited.contains(par) && !parents.contains(par)) {
                        parents.add(par);
                    }
                }
                if (!visited.contains(parent.superName)
                        && !parents.contains(parent.superName)) {
                    parents.add(parent.superName);
                }
            } catch (IOException e) {
                logger.info("Error reading class " + name);
            }
        }

        return false;
    }

    private String transformMethodName(String className, String methodName, String desc,
                                       String transformedDesc) {
        Set<String> visited = new HashSet<>();
        Queue<String> parents = new LinkedList<>();
        parents.add(className);

        while (!parents.isEmpty()) {
            String name = parents.poll();
            if (name == null) {
                continue;
            }

            visited.add(name);
            logger.info("Visiting class " + name + " while looking for name clashes of "
                    + className + "." + methodName + transformedDesc);
            ClassReader reader;
            try {
                reader = new ClassReader(ResourceList.getInstance(
                        TestGenerationContext.getInstance().getClassLoaderForSUT()).getClassAsStream(name));
                ClassNode parent = new ClassNode();
                reader.accept(parent, ClassReader.EXPAND_FRAMES);

                if (originalDesc.containsKey(className + "." + methodName
                        + transformedDesc)) {
                    logger.info("Method " + methodName
                            + " has conflicting transformed method");
                    return methodName + "_transformed" + (id++);
                }

                for (Object o : parent.methods) {
                    MethodNode mn2 = (MethodNode) o;
                    if (mn2.name.equals(methodName) && mn2.desc.equals(transformedDesc)) {
                        logger.info("Method " + methodName + " has conflicting method");
                        if (methodName.equals("<init>")) {
                            return null; // TODO: This should be a bit nicer
                        }
                        return methodName + "_transformed" + (id++);
                    }
                }

                for (Object o : parent.interfaces) {
                    String par = (String) o;
                    if (!visited.contains(par) && !parents.contains(par)) {
                        parents.add(par);
                    }
                }
                if (!visited.contains(parent.superName)
                        && !parents.contains(parent.superName)) {
                    parents.add(parent.superName);
                }
            } catch (IOException e) {
                logger.info("Error reading class " + name);
            }
        }

        return methodName;
    }

    private boolean isOutsideField(String className, String fieldName, String desc) {
        Set<String> visited = new HashSet<>();
        Queue<String> parents = new LinkedList<>();
        parents.add(className);

        while (!parents.isEmpty()) {
            String name = parents.poll();
            if (name == null) {
                continue;
            }

            visited.add(name);
            logger.info("Checking class " + name
                    + " while looking for definition of field " + fieldName);

            ClassReader reader;
            try {
                reader = new ClassReader(ResourceList.getInstance(
                        TestGenerationContext.getInstance().getClassLoaderForSUT()).getClassAsStream(name));
                ClassNode parent = new ClassNode();
                reader.accept(parent, ClassReader.EXPAND_FRAMES);

                boolean isInside = isInside(parent.name);

                for (Object o : parent.fields) {
                    FieldNode mn2 = (FieldNode) o;
                    if (mn2.name.equals(fieldName) && mn2.desc.equals(desc)) {
                        if (!isInside) {
                            logger.info("Field " + name
                                    + " was defined outside the test package - "
                                    + parent.name);
                            return true;
                        } else {
                            logger.info("Field " + name
                                    + " was defined inside the test package "
                                    + parent.name);
                            return false;

                        }
                    }
                }
                for (Object o : parent.interfaces) {
                    String par = (String) o;
                    if (!visited.contains(par) && !parents.contains(par)) {
                        parents.add(par);
                    }
                }
                if (!visited.contains(parent.superName)
                        && !parents.contains(parent.superName)) {
                    parents.add(parent.superName);
                }
            } catch (IOException e) {
                logger.info("Error reading class " + name);
            }
        }

        return false;
    }

    private String transformMethodDescriptor(String desc) {
        StringBuilder newDesc = new StringBuilder("(");

        Type[] types = Type.getArgumentTypes(desc);
        for (Type type : types) {
            if (type.equals(Type.BOOLEAN_TYPE)) {
                newDesc.append("I");
            } else if (type.getDescriptor().equals("[Z")) {
                newDesc.append("[I");
            } else {
                newDesc.append(type.getDescriptor());
            }
        }
        newDesc.append(")");

        Type type = Type.getReturnType(desc);
        if (type.equals(Type.BOOLEAN_TYPE)) {
            newDesc.append("I");
        } else if (type.getDescriptor().equals("[Z")) {
            newDesc.append("[I");
        } else {
            newDesc.append(type.getDescriptor());
        }

        return newDesc.toString();
    }

    private String transformFieldDescriptor(String className, String desc) {
        if (!shouldTransform(className)) {
            return desc;
        }
        logger.info("Transforming field instruction " + desc);
        if (isBooleanField(desc)) {
            // TODO: Check if this is actually transformed or not
            if (desc.equals("Z")) {
                return "I";
            } else if (desc.equals("[Z")) {
                return "[I";
            } else {
                return desc;
            }
        } else {
            return desc;
        }
    }

    /**
     * <p>getMethodName.</p>
     *
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param desc       a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     */
    public String getMethodName(String className, String methodName, String desc) {
        if (!shouldTransform(className)) {
            return methodName;
        }

        String old = className + "." + methodName + desc;
        old = old.replace('.', '/');
        if (isBooleanMethod(desc)) {
            getMethodDesc(className, methodName, desc);
            return nameMapping.get(old);
        } else {
            if (nameMapping.containsKey(old)) {
                return nameMapping.get(old);
            } else {
                return methodName;
            }
        }
    }

    /**
     * <p>getMethodDesc.</p>
     *
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param desc       a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     */
    public String getMethodDesc(String className, String methodName, String desc) {
        if (!shouldTransform(className)) {
            return desc;
        }

        if (isBooleanMethod(desc)) {
            String old = className.replace('.', '/') + "/" + methodName + desc;

            if (!descriptorMapping.containsKey(old)) {
                if (isOutsideMethod(className, methodName, desc)) {
                    logger.info("Is outside method: " + className + "." + methodName);
                    descriptorMapping.put(old, desc);
                    nameMapping.put(old, methodName);
                } else {
                    logger.info("Is inside method: " + className + "." + methodName);
                    String newDesc = transformMethodDescriptor(desc);
                    String newName = transformMethodName(className, methodName, desc,
                            newDesc);
                    if (newName != null) {
                        descriptorMapping.put(old, newDesc);
                        nameMapping.put(old, newName);
                        logger.info("Keeping transformation from " + old + " to "
                                + descriptorMapping.get(old) + " with new name "
                                + newName);
                        originalDesc.put(className.replace('.', '/') + "/" + newName
                                + newDesc, desc);
                        originalName.put(className.replace('.', '/') + "/" + newName
                                + newDesc, methodName);
                    } else {
                        descriptorMapping.put(old, desc);
                        nameMapping.put(old, methodName);
                        originalDesc.put(className.replace('.', '/') + "/" + methodName
                                + newDesc, desc);
                        originalName.put(className.replace('.', '/') + "/" + methodName
                                + newDesc, methodName);
                    }
                }
            }
            return descriptorMapping.get(old);
        } else {
            return desc;
        }
    }

    /**
     * <p>getFieldDesc.</p>
     *
     * @param className a {@link java.lang.String} object.
     * @param fieldName a {@link java.lang.String} object.
     * @param desc      a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     */
    public String getFieldDesc(String className, String fieldName, String desc) {
        if (!shouldTransform(className)) {
            return desc;
        }
        if (isBooleanField(desc)) {
            String old = className.replace('.', '/') + "/" + fieldName + desc;

            if (!descriptorMapping.containsKey(old)) {
                if (isOutsideField(className, fieldName, desc)) {
                    descriptorMapping.put(old, desc);
                } else {
                    descriptorMapping.put(old, transformFieldDescriptor(className, desc));
                    originalDesc.put(className.replace('.', '/') + "/" + fieldName
                            + descriptorMapping.get(old), desc);
                }
            }
            return descriptorMapping.get(old);
        } else {
            return desc;
        }
    }

    /**
     * <p>Getter for the field <code>originalName</code>.</p>
     *
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param desc       a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     */
    public String getOriginalName(String className, String methodName, String desc) {
        String key = className.replace('.', '/') + "/" + methodName + desc;
        if (originalName.containsKey(key)) {
            logger.info("Found transformed version of " + className + "." + methodName
                    + desc);
            return originalName.get(key);
        } else {
            logger.info("Don't have transformed version of " + className + "."
                    + methodName + desc);
            return methodName;
        }
    }
}
