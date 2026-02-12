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
package org.evosuite.testcarver.instrument;

import org.evosuite.PackageInfo;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

public class TransformerUtil {

    /**
     * Modifies the visibility of a class or member.
     *
     * @param access       the current access flags
     * @param targetAccess the target access flags
     * @return the modified access flags
     */
    public static int modifyVisibility(int access, final int targetAccess) {
        access &= ~Opcodes.ACC_PRIVATE;
        access &= ~Opcodes.ACC_PROTECTED;
        access &= ~Opcodes.ACC_PUBLIC;

        return access |= targetAccess;
    }

    /**
     * Checks if the access flags indicate a static member.
     *
     * @param access the access flags
     * @return true if static, false otherwise
     */
    public static boolean isStatic(final int access) {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    /**
     * Checks if the access flags indicate a private member.
     *
     * @param access the access flags
     * @return true if private, false otherwise
     */
    public static boolean isPrivate(final int access) {
        return (access & Opcodes.ACC_PRIVATE) != 0;
    }

    /**
     * Checks if the access flags indicate a public member.
     *
     * @param access the access flags
     * @return true if public, false otherwise
     */
    public static boolean isPublic(final int access) {
        return (access & Opcodes.ACC_PUBLIC) != 0;
    }

    /**
     * Checks if the access flags indicate a protected member.
     *
     * @param access the access flags
     * @return true if protected, false otherwise
     */
    public static boolean isProtected(final int access) {
        return (access & Opcodes.ACC_PROTECTED) != 0;
    }

    /**
     * Checks if the access flags indicate an abstract member.
     *
     * @param access the access flags
     * @return true if abstract, false otherwise
     */
    public static boolean isAbstract(final int access) {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    /**
     * Checks if the access flags indicate a native member.
     *
     * @param access the access flags
     * @return true if native, false otherwise
     */
    public static boolean isNative(final int access) {
        return (access & Opcodes.ACC_NATIVE) != 0;
    }


    /**
     * Determines if a class should be instrumented.
     *
     * @param className the name of the class
     * @return true if it should be instrumented, false otherwise
     */
    public static boolean isClassConsideredForInstrumentation(final String className) {
        // we exclude standard java and sun packages as well the Prototype package
        // TODO use regex for check
        // TODO use configuration for exclusion


        return !(
                className.startsWith("sun")
                        || className.startsWith("com/sun")
                        || className.startsWith("java")
                        || className.startsWith("java/lang")
                        || className.startsWith("$Proxy") // ignore all dynamic proxies (http://docs.oracle.com/javase/1.3/docs/guide/reflection/proxy.html)
                        || className.startsWith(PackageInfo.getEvoSuitePackageWithSlash() + "/testcarver")
                );
    }


    /**
     * Determines if a class is a dependency that should be ignored.
     *
     * @param className the name of the class
     * @return true if it is a dependency, false otherwise
     */
    public static boolean isDependency(final String className) {
        // TODO use regex for check
        // TODO use configuration for exclusion
        return (
                className.startsWith("sun")
                        || className.startsWith("com/sun")
                        || className.startsWith("java")
                        || className.startsWith("com/thoughtworks/xstream")
                        || className.startsWith("org/xmlpull")
                        || className.startsWith("org/eclipse/jdt")
                        || className.startsWith("org/slf4j")
                );
    }


    /**
     * Checks if a class node is accessible.
     *
     * @param cn the class node
     * @return true if accessible, false otherwise
     */
    public static boolean isClassAccessible(final ClassNode cn) {

        return (cn.access & Opcodes.ACC_INTERFACE) == 0
                && (
                ((cn.access & Opcodes.ACC_PUBLIC) != 0)
                        || ((cn.access & Opcodes.ACC_PROTECTED) != 0)
                );
    }
}
