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
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.setup;

import org.evosuite.Properties;
import org.evosuite.runtime.instrumentation.InstrumentedClass;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides which types a generated test may name, as a {@code .class} literal, an enum
 * constant's qualifier, or a declared variable or cast type, from the package the test
 * is written to ({@code Properties.CLASS_PREFIX}).
 *
 * <p>The predicates differ on purpose; each answers the question of one rendering slot,
 * and reusing one for another changes generated output (see the javadoc of
 * each). They must not be merged.</p>
 */
public final class GeneratedTestAccess {

    private static final Logger logger = LoggerFactory.getLogger(GeneratedTestAccess.class);

    private GeneratedTestAccess() {
    }

    /**
     * Decides whether {@code classLiteral} may be written as a {@code Foo.class}
     * literal in the generated test, or must instead be obtained reflectively
     * via {@code Class.forName(...)}.
     *
     * <p>This is deliberately stricter than {@link #isTypeAccessibleFromGeneratedTest}
     * for nested classes. At rendering time the system under test is loaded through
     * EvoSuite's instrumenting class loader, and {@code AccessibleClassAdapter}
     * ORs {@code ACC_PUBLIC} into the class-file access flags of every non-private,
     * non-protected class. A source-level {@code private} nested class (e.g.
     * {@code org.jsoup.helper.HttpConnection.Base}) is not private at the class-file
     * level -- its {@code private} modifier lives only in the enclosing class's
     * {@code InnerClasses} attribute -- so it gets promoted and
     * {@link Class#getModifiers()} then reports it as public. The generated test is,
     * however, compiled against the real (uninstrumented) classes, where the
     * literal is rejected. A nested class is emitted reflectively instead: a nested
     * {@code .class} literal is never referenceable from outside its enclosing type
     * unless the nested class is public, and reflection never needs the compile-time
     * {@code Class<Foo>} typing anyway.
     *
     * <p>Besides feeding {@code getClassLiteralExpression}, this check is also used
     * directly wherever a nested type's referenceability needs to be known without
     * needing a {@code Class} expression at all -- e.g. the reflective-constructor
     * fallback choosing a declared/cast type (see {@code visitConstructorStatement}
     * and {@code findAccessibleRenderableSupertype}), and
     * {@link org.evosuite.utils.NumberFormatter} / the assertion classes deciding
     * whether an enum constant can be rendered as {@code Type.CONSTANT} or must be
     * compared by name instead. It is {@code public static} (rather than private)
     * so those classes can call it without needing a {@code TestCodeVisitor}
     * instance and without duplicating the logic.
     */
    public static boolean isClassLiteralReferenceableFromGeneratedTest(Class<?> classLiteral) {
        if (classLiteral == null) {
            return false;
        }
        // Nested classes cannot be trusted through getModifiers() at render time
        // (see javadoc): render them reflectively rather than risk an
        // uncompilable ".class" literal for a source-private member.
        if (isNestedClassAtRenderTime(classLiteral)) {
            return false;
        }
        return isTypeAccessibleFromGeneratedTest(classLiteral, getGeneratedTestPackageName());
    }

    /**
     * Decides whether an enum constant's declaring class may be named directly in a
     * generated-test assertion as {@code Type.CONSTANT}, or whether the comparison
     * must instead be rendered by {@link Enum#name()}.
     *
     * <p>This is deliberately <b>narrower</b> than, and must not be confused with,
     * {@link #isClassLiteralReferenceableFromGeneratedTest}. That method
     * blanket-rejects every nested class, public or not, because a {@code .class}
     * literal always has a reflective {@code Class.forName(...)} escape hatch, so
     * being unnecessarily conservative there is free. An enum constant used as a
     * <i>value</i> in an assertion has no such escape hatch -- there is no
     * reflective way to produce an expression that denotes the constant itself --
     * so unnecessary conservatism here is not free: reusing the blanket check would
     * force every nested enum, including public and same-package ones, into the
     * uglier by-name comparison, a corpus-wide change to generated assertions to
     * fix only the genuinely-private handful.
     *
     * <p>Unlike the {@code .class}-literal case, {@link Class#getModifiers()} can be
     * trusted here for the one thing that matters: privacy. {@code
     * AccessibleClassAdapter.visitInnerClass} only ORs {@code ACC_PUBLIC} into an
     * InnerClasses-attribute entry that is <b>not already</b> {@code ACC_PRIVATE} --
     * a source-private nested type keeps its {@code ACC_PRIVATE} flag through
     * instrumentation, and {@code Class.getModifiers()} for a member class reads
     * exactly that entry. So a private nested enum still reads back as private at
     * render time, and {@link #isTypeAccessibleFromGeneratedTest} -- which already
     * special-cases {@code Modifier.isPrivate} before falling through to the
     * same-package check -- can be used directly instead of the blanket check.
     *
     * <p><b>Known residual risk:</b> {@code getModifiers()} still lies
     * <i>upward</i> for package-private and protected nested types --
     * {@code AccessibleClassAdapter} promotes those to {@code ACC_PUBLIC}. That is
     * harmless when the generated test sits in the CUT's own package (the only case
     * {@link #isTypeAccessibleFromGeneratedTest} treats a non-public type as
     * accessible in anyway), but a package-private nested enum belonging to a
     * <i>foreign</i> package would be misreported as public/nameable here. This is
     * strictly more accurate than the previous blanket-conservative behaviour, but
     * is not a complete fix for that narrower cross-package case.
     *
     * @param enumClass the enum constant's declaring class (already unwrapped past
     *                   any anonymous constant-body subclass)
     * @return {@code true} if {@code Type.CONSTANT} can be written directly,
     *         {@code false} if the comparison must be rendered by name instead
     */
    public static boolean isEnumConstantNameableInGeneratedTest(Class<?> enumClass) {
        if (enumClass == null) {
            return false;
        }
        if (enumClass.getEnclosingClass() != null && Modifier.isPrivate(enumClass.getModifiers())) {
            return false;
        }
        return isTypeAccessibleFromGeneratedTest(enumClass, getGeneratedTestPackageName());
    }

    /**
     * Decides whether {@code type} can be written by name as a declared variable
     * type or cast target in the generated test -- as opposed to whether a
     * <em>constructor</em> of it is source-accessible (see
     * {@code TestCodeVisitor.isSourceAccessibleFromGeneratedTest}), which is a different question:
     * a private constructor on an otherwise perfectly nameable public class is a
     * common idiom, and reflection is needed for the constructor while the type
     * name itself is fine to write.
     *
     * <p>This predicate is deliberately narrower than both of the other
     * referenceability checks in this class, and must not be confused with either:
     * <ul>
     * <li>{@link #isClassLiteralReferenceableFromGeneratedTest} blanket-rejects
     * every nested class, because a {@code .class} literal always has a reflective
     * {@code Class.forName(...)} escape hatch, so being unnecessarily conservative
     * there is free.
     * <li>{@link #isEnumConstantNameableInGeneratedTest} defers to
     * {@link #isTypeAccessibleFromGeneratedTest}, which also requires each
     * enclosing class in the chain to be accessible (public, or in the generated
     * test's own package per {@code Properties.CLASS_PREFIX}) -- appropriate there
     * because the enum case targets real CUT classes, where that chain is
     * meaningful.
     * </ul>
     * Reusing either of those here would break valid output: a
     * {@code public static} nested class with an implicit public constructor,
     * declared inside a package-private outer class, with no
     * {@code Properties.CLASS_PREFIX} configured (exactly the shape of several
     * EvoSuite-internal test fixtures, e.g. {@code TestRepairLoopSalvageTest}'s
     * package-private {@code TestRepairLoopSalvageTest} enclosing a {@code public
     * static class SalvageTarget}) is perfectly nameable as
     * {@code TestRepairLoopSalvageTest.SalvageTarget} -- exactly as
     * {@link org.evosuite.testcase.TestCodeVisitor#getClassName} has always optimistically rendered it, with no
     * enclosing-class accessibility check at all -- but
     * {@link #isTypeAccessibleFromGeneratedTest}'s conservative
     * {@code isEmptyPackageName(testPackageName) -> false} default for the
     * package-private enclosing class would call it unnameable, substituting
     * {@code Object} and breaking every statement that uses the variable's real
     * type.
     *
     * <p>The one signal that can be trusted post-instrumentation is a type's own
     * {@code private} flag: {@code AccessibleClassAdapter.visitInnerClass} only ORs
     * in {@code ACC_PUBLIC} for an InnerClasses-attribute entry that is not already
     * {@code ACC_PRIVATE}, so a source-private nested type keeps reading as private
     * even at render time. This method therefore checks only that, walking the full
     * enclosing-class chain (a public member of a private enclosing type is just as
     * unnameable as the private type itself), and otherwise defers to the same
     * long-standing optimism {@link org.evosuite.testcase.TestCodeVisitor#getClassName} already applies to every other
     * nested type -- package-private, protected or public alike.
     *
     * @param type the declaring class of a constructor, which may or may not itself
     *             be source-accessible
     * @return {@code true} if {@code type} (and its enclosing chain) can be written
     *         by name, {@code false} only when some level of that chain is
     *         source-private
     */
    public static boolean isDeclaredTypeNameableInGeneratedTest(Class<?> type) {
        String testPackageName = getGeneratedTestPackageName();
        if (hasInaccessibleEnclosingMetadata(type, testPackageName)) {
            return false;
        }
        if (TestUsageChecker.isInUnexportedPackage(type)) {
            // Public, but in a package its JDK module does not export (XMLDSigRI).
            return false;
        }
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            int modifiers = TestUsageChecker.getSourceModifiers(current);
            if (Modifier.isPrivate(modifiers)) {
                return false;
            }
            // A non-public type of another package cannot be named either (xerces'
            // package-private PSVIErrorList, built through the reflective constructor
            // fallback). Only types of the class under test's package are promoted by the
            // instrumentation, so outside that package the public flag is truthful. With no
            // known test package the long-standing optimism stays.
            if (!Modifier.isPublic(modifiers) && !isEmptyPackageName(testPackageName)
                    && !isSamePackage(current, testPackageName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the nearest accessible type that the generated test may use in place of
     * {@code inaccessibleType} as a declared variable type or cast target, when
     * {@code inaccessibleType} itself cannot be named from the generated test (e.g.
     * a source-private nested class instantiated through the reflective-constructor
     * fallback in {@code visitConstructorStatement}). Unlike a {@code Class}
     * literal (see {@code TestCodeVisitor.getClassLiteralExpression}), a type used for a
     * declaration or cast has no reflective escape hatch -- {@code Class.forName}
     * yields a {@code Class}, not a type the compiler can use -- so an accessible
     * supertype has to be found instead.
     *
     * <p>Interfaces are preferred over the superclass chain: a source-private
     * helper/proxy class is typically declared {@code private} specifically to hide
     * an implementation behind a public interface it implements for its callers
     * (e.g. {@code IconManager.IconProxy implements Icon}), so an implemented
     * interface is far more likely to carry the API that later statements in the
     * generated test actually invoke on the variable. The superclass chain, by
     * contrast, commonly bottoms out at {@code Object}, which offers nothing useful.
     * A marker interface with no instance methods is replaced by the nearest
     * accessible superclass only when that superclass also implements the marker.
     * This keeps Jython's inherited PyObject/PyBuiltinCallable API visible without
     * losing the Serializable/Cloneable typing of classes that implement a marker
     * directly. Direct interfaces are tried before inherited ones (breadth-first).
     * {@code Object} -- always accessible -- is the ultimate fallback, so this
     * method never returns {@code null}.
     *
     * @param inaccessibleType the (possibly source-private) declaring class of a
     *                          constructor that is not source-accessible
     * @return the nearest accessible interface or superclass, defaulting to
     *         {@code Object.class}
     */
    public static Class<?> findAccessibleRenderableSupertype(Class<?> inaccessibleType) {
        String testPackageName = getGeneratedTestPackageName();
        Deque<Class<?>> interfaceQueue = new ArrayDeque<>();
        Set<Class<?>> seenInterfaces = new LinkedHashSet<>();
        for (Class<?> current = inaccessibleType; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                if (seenInterfaces.add(iface)) {
                    interfaceQueue.addLast(iface);
                }
            }
        }
        while (!interfaceQueue.isEmpty()) {
            Class<?> iface = interfaceQueue.removeFirst();
            // Instrumentation adds this marker to the classes it loads; javac compiles the test
            // against the uninstrumented class, which does not implement it.
            if (iface == InstrumentedClass.class) {
                continue;
            }
            if (isTypeAccessibleFromGeneratedTest(iface, testPackageName)) {
                if (!hasInstanceMethods(iface)) {
                    Class<?> superclass = findAccessibleSuperclass(inaccessibleType, testPackageName);
                    if (iface.isAssignableFrom(superclass)) {
                        return superclass;
                    }
                }
                return iface;
            }
            for (Class<?> superInterface : iface.getInterfaces()) {
                if (seenInterfaces.add(superInterface)) {
                    interfaceQueue.addLast(superInterface);
                }
            }
        }

        return findAccessibleSuperclass(inaccessibleType, testPackageName);
    }

    private static Class<?> findAccessibleSuperclass(Class<?> type, String testPackageName) {
        for (Class<?> current = type.getSuperclass(); current != null; current = current.getSuperclass()) {
            if (isTypeAccessibleFromGeneratedTest(current, testPackageName)) {
                return current;
            }
        }
        return Object.class;
    }

    private static boolean hasInstanceMethods(Class<?> iface) {
        try {
            for (Method method : iface.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            // A type whose API cannot be linked is not a useful declared receiver.
            logger.debug("Cannot inspect interface methods for {}", iface.getName(), exception);
        }
        return false;
    }

    /**
     * Reliably decides whether {@code classLiteral} is a nested (member) class at
     * rendering time.
     *
     * <p>{@link Class#getEnclosingClass()}, {@link Class#getModifiers()} and
     * {@link Class#getCanonicalName()} all derive nesting from the {@code
     * InnerClasses} attribute, which EvoSuite's instrumenting class loader can
     * strip (for example a nested class whose
     * {@code getEnclosingClass()} returns {@code null} once instrumented). We
     * therefore fall back to the binary name: a nested class is {@code Outer$Inner}
     * where {@code Outer} is itself a loadable class. A top-level identifier that
     * merely contains {@code $} (e.g. Gson's {@code $Gson$Types}) has no loadable
     * enclosing prefix and is correctly treated as top-level.
     */
    private static boolean isNestedClassAtRenderTime(Class<?> classLiteral) {
        if (classLiteral.getEnclosingClass() != null) {
            return true;
        }
        // getEnclosingClass() returned null, but under instrumentation that is not
        // conclusive. The binary name is the one signal that reliably survives:
        // a nested class's simple name contains a '$' that is not the leading
        // character (a leading '$' is a top-level identifier such as Gson's
        // $Gson$Types, whose simple name legitimately starts with '$'). This can
        // over-report a top-level type that merely embeds '$' mid-identifier, but
        // for the reflective PrivateAccess class literals this feeds, rendering
        // such a class via Class.forName is always safe and compilable.
        String binaryName = classLiteral.getName();
        int simpleNameStart = binaryName.lastIndexOf('.') + 1;
        int dollar = binaryName.indexOf('$', simpleNameStart);
        return dollar > simpleNameStart;
    }

    public static boolean isTypeAccessibleFromGeneratedTest(Class<?> type, String testPackageName) {
        if (type == null) {
            return false;
        }
        // Public, but in a package its module does not export (java.awt.peer,
        // sun.security.validator, ...): javac rejects any reference, imports included.
        if (TestUsageChecker.isInUnexportedPackage(type)) {
            return false;
        }

        if (hasInaccessibleEnclosingMetadata(type, testPackageName)) {
            return false;
        }
        Class<?> enclosing = type.getEnclosingClass();
        if (enclosing != null && !isTypeAccessibleFromGeneratedTest(enclosing, testPackageName)) {
            return false;
        }

        int modifiers = TestUsageChecker.getSourceModifiers(type);
        if (Modifier.isPublic(modifiers)) {
            return true;
        }
        if (Modifier.isPrivate(modifiers)) {
            return false;
        }
        // Without a concrete output package we cannot safely assume package-private
        // access is legal; be conservative to avoid uncompilable tests.
        if (isEmptyPackageName(testPackageName)) {
            return false;
        }
        return isSamePackage(type, testPackageName);
    }

    /**
     * Some generated member classes (Jython's exposers) omit their own InnerClasses
     * attribute. Reflection then reports a public top-level class, although javac
     * reads the enclosing class's table and rejects the private member. Consult that
     * table only when reflection has lost the enclosing type; a dollar in an ordinary
     * top-level identifier alone does not establish nesting or make it inaccessible.
     */
    private static boolean hasInaccessibleEnclosingMetadata(Class<?> type, String testPackageName) {
        if (type.getEnclosingClass() != null || type.getClassLoader() == null) {
            return false;
        }
        String binaryName = type.getName();
        if (binaryName.lastIndexOf('$') <= binaryName.lastIndexOf('.') + 1) {
            return false;
        }
        int memberChainAccess = ENCLOSING_METADATA_ACCESS.get(type);
        if ((memberChainAccess & ENCLOSING_METADATA_PRIVATE) != 0) {
            return true;
        }
        return (memberChainAccess & ENCLOSING_METADATA_NON_PUBLIC) != 0
                && !isEmptyPackageName(testPackageName)
                && !isSamePackage(type, testPackageName);
    }

    private static final int ENCLOSING_METADATA_PRIVATE = 1;
    private static final int ENCLOSING_METADATA_NON_PUBLIC = 2;

    /**
     * Per class, which access restrictions the InnerClasses tables of its binary-name
     * prefixes record along its member chain. Reading those tables means parsing class
     * files, so the result is kept; only the package comparison depends on the run.
     */
    private static final ClassValue<Integer> ENCLOSING_METADATA_ACCESS = new ClassValue<Integer>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            return readEnclosingMetadataAccess(type);
        }
    };

    private static int readEnclosingMetadataAccess(Class<?> type) {
        String binaryName = type.getName();
        int simpleNameStart = binaryName.lastIndexOf('.') + 1;
        String internalName = binaryName.replace('.', '/');
        ClassLoader loader = type.getClassLoader();
        Map<String, String> enclosingTypes = new HashMap<>();
        Map<String, Integer> memberAccess = new HashMap<>();
        for (int dollar = binaryName.lastIndexOf('$'); dollar > simpleNameStart;
                dollar = binaryName.lastIndexOf('$', dollar - 1)) {
            String resource = binaryName.substring(0, dollar).replace('.', '/') + ".class";
            try (InputStream input = loader.getResourceAsStream(resource)) {
                if (input == null) {
                    continue;
                }
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInnerClass(String name, String outerName, String innerName,
                                                int access) {
                        if (outerName != null) {
                            enclosingTypes.putIfAbsent(name, outerName);
                            memberAccess.putIfAbsent(name, access);
                        }
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (IOException | RuntimeException exception) {
                // Missing/unreadable metadata keeps the established reflection fallback.
                logger.debug("Cannot inspect enclosing metadata for {}", binaryName, exception);
            }
        }
        // Only an exact member entry establishes nesting. A private binary-name
        // prefix can belong to an unrelated class when an identifier contains '$'.
        int restrictions = 0;
        Set<String> visited = new HashSet<>();
        for (String member = internalName; enclosingTypes.containsKey(member)
                && visited.add(member); member = enclosingTypes.get(member)) {
            int access = memberAccess.get(member);
            if (Modifier.isPrivate(access)) {
                restrictions |= ENCLOSING_METADATA_PRIVATE;
            } else if (!Modifier.isPublic(access)) {
                restrictions |= ENCLOSING_METADATA_NON_PUBLIC;
            }
        }
        return restrictions;
    }

    public static boolean isEmptyPackageName(String packageName) {
        return packageName == null || packageName.isEmpty();
    }

    public static String getGeneratedTestPackageName() {
        String classPrefix = Properties.CLASS_PREFIX;
        if (classPrefix == null) {
            return "";
        }
        return classPrefix.trim();
    }

    public static boolean isSamePackage(Class<?> clazz, String packageName) {
        if (clazz == null) {
            return false;
        }
        Package pkg = clazz.getPackage();
        String classPackage = (pkg == null) ? "" : pkg.getName();
        String testPackage = packageName == null ? "" : packageName;
        return classPackage.equals(testPackage);
    }
}
