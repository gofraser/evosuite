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
package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.classhandling.ClassResetter;
import org.evosuite.runtime.classhandling.ModifiedTargetStaticFields;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * This visitor duplicates static initializers (i.e. {@code <clinit>}) into a new method
 * __STATIC_RESET() method, such that we can explicitly restore the initial
 * state of the class.
 *
 * @author Gordon Fraser
 */
public class CreateClassResetClassAdapter extends ClassVisitor {

    /**
     * This flag indicates that final static fields should be transformed into
     * non-final static fields.
     */
    private final boolean removeFinalModifierOnStaticFields;

    /**
     * This flag indicates that any update on final static fields should be
     * removed.
     */
    private boolean removeUpdatesOnFinalFields = true;

    /**
     * Allows to define if the current transformation should remove any updated
     * on those static fields that are final fields. If the
     * <code>removeFinalModifierOnStaticFields</code> is active, then no update
     * is removed since all static final fields are transformed into non-final
     * fields.
     *
     * @param removeUpdatesOnFinalFields true if updates on final fields should be removed
     */
    public void setRemoveUpdatesOnFinalFields(boolean removeUpdatesOnFinalFields) {
        this.removeUpdatesOnFinalFields = removeUpdatesOnFinalFields;
    }

    /**
     * The current class name being visited.
     */
    private final String className;

    /**
     * Constant <code>staticClasses</code>.
     */
    public static List<String> staticClasses = new ArrayList<>();

    /**
     * Classes for which {@code __STATIC_RESET()} generation was skipped
     * because {@link ClinitResetSafetyAnalyzer} judged their {@code
     * <clinit>} unsafe to replay (classpath/resource scan building an
     * identity-keyed or singleton registry). Visible for testing and for
     * measuring how often the rule fires.
     */
    public static final Set<String> unsafeClinitSkippedClasses =
            Collections.synchronizedSet(new LinkedHashSet<>());

    private static final Logger logger = LoggerFactory.getLogger(CreateClassResetClassAdapter.class);

    /**
     * Indicates if the current class being visited is an interface.
     */
    private boolean isInterface = false;

    /**
     * Indicates if the current class being visited is an anonymous classs.
     */
    private boolean isAnonymous = false;

    /**
     * Indicates if the {@code <clinit>} method was already found during the visit of
     * this class.
     */
    private boolean clinitFound = false;

    private boolean definesUid = false;
    private long serialUid = -1L;

    /**
     * Indicates if the __STATIC_RESET() method has been already added to this
     * class definition.
     */
    private boolean resetMethodAdded = false;

    /**
     * The final fields of this class.
     */
    private final List<String> finalFields = new ArrayList<>();

    /**
     * Indicates if the current class being visited is an enumeration.
     */
    private boolean isEnum = false;

    private static final Pattern ANONYMOUS_MATCHER1 = Pattern.compile(".*\\$\\d+.*$");

    /**
     * True if {@link ClinitResetSafetyAnalyzer} judged this class's {@code
     * <clinit>} unsafe to replay. When true, no {@code __STATIC_RESET()} is
     * generated at all for this class, so its static state simply persists
     * across tests for the lifetime of the JVM - the same trade-off already
     * made deliberately for {@code SKIP_RESET_PREFIXES} in {@link
     * ClassResetter}.
     */
    private final boolean unsafeToReplayClinit;

    /**
     * Creates a new <code>CreateClassResetClassAdapter</code> instance.
     *
     * @param visitor                           the visitor
     * @param className                         the class name to be visited
     * @param removeFinalModifierOnStaticFields if this parameter is true, all final static fields are
     *                                          translated into non-final static fields
     */
    public CreateClassResetClassAdapter(ClassVisitor visitor, String className,
                                        boolean removeFinalModifierOnStaticFields) {
        this(visitor, className, removeFinalModifierOnStaticFields, null);
    }

    /**
     * Creates a new <code>CreateClassResetClassAdapter</code> instance.
     *
     * @param visitor                           the visitor
     * @param className                         the class name to be visited
     * @param removeFinalModifierOnStaticFields if this parameter is true, all final static fields are
     *                                          translated into non-final static fields
     * @param originalClassReader               a fresh {@link ClassReader} over the original,
     *                                          uninstrumented bytecode of this class, used to decide
     *                                          whether {@code <clinit>} is safe to replay. May be null,
     *                                          in which case the safety check is skipped and {@code
     *                                          __STATIC_RESET()} is always generated (previous behavior).
     */
    public CreateClassResetClassAdapter(ClassVisitor visitor, String className,
                                        boolean removeFinalModifierOnStaticFields,
                                        ClassReader originalClassReader) {
        super(Opcodes.ASM9, visitor);
        this.className = className;
        this.removeFinalModifierOnStaticFields = removeFinalModifierOnStaticFields;
        boolean unsafe = false;
        if (originalClassReader != null) {
            try {
                unsafe = ClinitResetSafetyAnalyzer.isUnsafeToReplayClinit(originalClassReader);
            } catch (RuntimeException e) {
                // Detection is a heuristic best-effort pass over the
                // bytecode; if it fails for some reason, fall back to the
                // previous behavior (generate the reset method) rather than
                // letting a bug in the analyzer break instrumentation.
                logger.debug("ClinitResetSafetyAnalyzer failed for {}: {}", className, e.toString());
                unsafe = false;
            }
        }
        this.unsafeToReplayClinit = unsafe;
    }

    /**
     * Detects if the current class is an anonymous class.
     */
    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        isInterface = ((access & Opcodes.ACC_INTERFACE) == Opcodes.ACC_INTERFACE);
        if (ANONYMOUS_MATCHER1.matcher(name).matches()) {
            isAnonymous = true;
        }
        if (superName.equals(java.lang.Enum.class.getName().replace(".", "/"))) {
            isEnum = true;
        }
    }

    /**
     * This class represents a static field that was declared in the current
     * visited class.
     *
     * @author galeotti
     */
    static class StaticField {
        /**
         * Name of the static field.
         */
        String name;

        /**
         * Field descriptor (ie type) of the static field.
         */
        String desc;

        /**
         * Initial value (if any) for the static field.
         */
        Object value;

        @Override
        public String toString() {
            return "StaticField [name=" + name + "]";
        }
    }

    /**
     * The list of the static fields declared in the class being visited.
     */
    private final List<StaticField> staticFields = new LinkedList<>();

    /**
     * This list saves the static fields whose <code>final</code> modifier was
     * removed in the target class.
     */
    private final ArrayList<String> modifiedStaticFields = new ArrayList<>();

    /**
     * During the visit of each field, static fields are collected. If the
     * <code>removeFinalModifierOnStaticFields</code> is active, final static
     * fields are transformed into non-final static fields.
     */
    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {

        if (name.equals("serialVersionUID")) {
            definesUid = true;
            // We must not remove final from serialVersionUID or else the
            // class cannot be serialised and de-serialised any more
            return super.visitField(access, name, desc, signature, value);
        }

        if (hasStaticModifier(access)) {
            StaticField staticField = new StaticField();
            staticField.name = name;
            staticField.desc = desc;
            staticField.value = value;
            staticFields.add(staticField);
        }

        if (!isEnum && !isInterface && removeFinalModifierOnStaticFields) {
            int newAccess = access & (~Opcodes.ACC_FINAL);
            if (newAccess != access) {
                // this means that the field was modified
                modifiedStaticFields.add(name);
            }
            return super.visitField(newAccess, name, desc, signature, value);
        } else {
            if (hasFinalModifier(access)) {
                finalFields.add(name);
            }

            return super.visitField(access, name, desc, signature, value);
        }
    }

    /**
     * Returns true iif the access modifiers has a final modifier.
     *
     * @param access the access flags
     * @return true if final
     */
    private boolean hasFinalModifier(int access) {
        return (access & Opcodes.ACC_FINAL) == Opcodes.ACC_FINAL;
    }

    /**
     * Returns true iif the access modifiers has a static modifier.
     *
     * @param access the access flags
     * @return true if static
     */
    private boolean hasStaticModifier(int access) {
        return (access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MethodVisitor visitMethod(int methodAccess, String methodName, String descriptor, String signature,
                                     String[] exceptions) {

        MethodVisitor mv = super.visitMethod(methodAccess, methodName, descriptor, signature, exceptions);

        if (methodName.equals("<clinit>") && !isInterface && !isAnonymous && !resetMethodAdded) {
            clinitFound = true;
            resetMethodAdded = true;

            if (unsafeToReplayClinit) {
                // Don't emit __STATIC_RESET() at all: replaying this
                // <clinit> can succeed while rebuilding a registry wrong
                // (e.g. an empty resource scan, or a freshly built object
                // that no longer matches the identity used to key an
                // existing map), which throws nothing and so is invisible
                // to ClassResetter's poison-on-throw path. Leaving the
                // class's static state as populated by its one real
                // <clinit> run is the safer choice. See
                // ClinitResetSafetyAnalyzer for the detection rule.
                unsafeClinitSkippedClasses.add(className.replace('/', '.'));
                logger.info("Skipping __STATIC_RESET generation for {}: <clinit> reaches a "
                        + "classpath/resource-scan call and builds a registry field; replay is not "
                        + "safe (idempotent)", className);
                return mv;
            }

            logger.info("Found static initializer in class " + className);
            // determineSerialisableUID();

            // duplicates existing <clinit>
            // TODO: Removed | Opcodes.ACC_PUBLIC
            // Does __STATIC_RESET need to be public?
            // <clinit> apparently can be private, resulting
            // in illegal modifiers
            MethodVisitor visitMethod = super.visitMethod(methodAccess | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    ClassResetter.STATIC_RESET, descriptor, signature, exceptions);

            CreateClassResetMethodAdapter staticResetMethodAdapter = new CreateClassResetMethodAdapter(visitMethod,
                    className, this.staticFields, finalFields);

            if (this.removeUpdatesOnFinalFields) {
                MethodVisitor mv2 = new RemoveFinalMethodAdapter(className, staticResetMethodAdapter, finalFields);

                return new MultiMethodVisitor(mv2, mv);
            } else {
                return new MultiMethodVisitor(staticResetMethodAdapter, mv);
            }
        } else if (methodName.equals(ClassResetter.STATIC_RESET)) {
            if (resetMethodAdded) {
                // Do not add reset method a second time
            } else {
                resetMethodAdded = true;
            }
        }
        return mv;
    }

    /**
     * After all the class code was visited, If no {@code <clinit>} was found, an empty
     * __STATIC_RESET() method is synthesized.
     */
    @Override
    public void visitEnd() {
        if (!clinitFound && !isInterface && !isAnonymous && !resetMethodAdded) {
            // create brand new __STATIC_RESET
            if (!definesUid) {
                // determineSerialisableUID();
                // createSerialisableUID();
            }
            createEmptyStaticReset();
        } else if (clinitFound) {
            if (!definesUid) {
                // createSerialisableUID();
            }
        }
        if (!modifiedStaticFields.isEmpty()) {
            ModifiedTargetStaticFields.getInstance().addFinalFields(className, modifiedStaticFields);
        }
        super.visitEnd();
    }

    /**
     * <p>determineSerialisableUID.</p>
     */
    @Deprecated
    private void determineSerialisableUID() {
        try {
            Class<?> clazz = Class.forName(className.replace('/', '.'), false,
                    MethodCallReplacementClassAdapter.class.getClassLoader());
            if (Serializable.class.isAssignableFrom(clazz)) {
                ObjectStreamClass c = ObjectStreamClass.lookup(clazz);
                serialUid = c.getSerialVersionUID();
            }
        } catch (ClassNotFoundException e) {
            logger.info("Failed to add serialId to class " + className + ": " + e.getMessage());
        }

    }

    /**
     * <p>createSerialisableUID.</p>
     */
    @Deprecated
    // This method is a code clone from MethodCallReplacementClassAdapter
    private void createSerialisableUID() {
        // Only add this for serialisable classes
        if (serialUid < 0) {
            return;
        }
        /*
         * If the class is serializable, then adding a hashCode will change the
         * serialVersionUID if it is not defined in the class. Hence, if it is
         * not defined, we have to define it to avoid problems in serialising
         * the class.
         */
        logger.info("Adding serialId to class " + className);
        visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "serialVersionUID", "J", null,
                serialUid);
    }

    /**
     * Creates an empty __STATIC_RESET method where no {@code <clinit>} was found.
     */
    private void createEmptyStaticReset() {
        logger.info("Creating brand-new static initializer in class " + className);
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                ClassResetter.STATIC_RESET, "()V", null, null);
        mv.visitCode();
        for (StaticField staticField : staticFields) {

            if (!finalFields.contains(staticField.name) && !staticField.name.startsWith("__cobertura")
                    && !staticField.name.startsWith("$jacoco") && !staticField.name.startsWith("$VRc") // Old
                    // Emma
                    && !staticField.name.startsWith("$gzoltar")
            ) {

                logger.info("Adding bytecode for initializing field " + staticField.name);

                if (staticField.value != null) {
                    mv.visitLdcInsn(staticField.value);
                } else {
                    Type type = Type.getType(staticField.desc);
                    switch (type.getSort()) {
                        case Type.BOOLEAN:
                        case Type.BYTE:
                        case Type.CHAR:
                        case Type.SHORT:
                        case Type.INT:
                            mv.visitInsn(Opcodes.ICONST_0);
                            break;
                        case Type.FLOAT:
                            mv.visitInsn(Opcodes.FCONST_0);
                            break;
                        case Type.LONG:
                            mv.visitInsn(Opcodes.LCONST_0);
                            break;
                        case Type.DOUBLE:
                            mv.visitInsn(Opcodes.DCONST_0);
                            break;
                        case Type.ARRAY:
                        case Type.OBJECT:
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            break;
                        default:
                            // Do nothing
                            break;
                    }
                }
                mv.visitFieldInsn(Opcodes.PUTSTATIC, className, staticField.name, staticField.desc);

            }
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

    }
}
