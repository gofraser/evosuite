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
package org.evosuite.setup;

import java.lang.reflect.Method;

/**
 * Reflective access to a class's Java 9+ module, shared by {@link TestUsageChecker#isPlatformClass}
 * and {@link TestClusterUtils#isJdkClass}.
 *
 * <p>Both callers need to know which named module (if any) loaded a class, but this module
 * compiles against the Java 8 API ({@code --release 8}, project-wide), so {@code Class#getModule()}
 * cannot be called directly; it has to go through {@code Class.getMethod("getModule")} and then
 * {@code Module#isNamed()} / {@code Module#getName()} the same way. This class performs only that
 * lookup, not the classification that each caller builds on top of it: the two callers disagree
 * on purpose about what an unresolvable module system means (one treats it as "assume JDK", the
 * other as "fall back to the bootstrap-loader check"), and about which module-name prefixes count,
 * so unifying the decision itself would silently change one of them.
 *
 * <p>The three {@link Method} objects are resolved once, in a static initializer, rather than on
 * every lookup: {@code getMethod} itself -- a scan of the public method table plus a fresh
 * {@code Method} allocation -- is the expensive part, not the three {@code invoke} calls that
 * follow. Resolution can fail wholesale (a real Java 8 JVM has no module system at all), which is
 * recorded once in {@link #MODULES_SUPPORTED} instead of being rediscovered on every call.
 */
final class JavaModuleLookup {

    private JavaModuleLookup() {
    }

    private static final Method GET_MODULE;
    private static final Method IS_NAMED;
    private static final Method GET_NAME;
    private static final boolean MODULES_SUPPORTED;

    static {
        Method getModule;
        Method isNamed;
        Method getName;
        try {
            getModule = Class.class.getMethod("getModule");
            Class<?> moduleClass = getModule.getReturnType();
            isNamed = moduleClass.getMethod("isNamed");
            getName = moduleClass.getMethod("getName");
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No module system on this JVM (plain Java 8), or something refused to resolve.
            // Leave all three null; every lookup will then report UNAVAILABLE below.
            getModule = null;
            isNamed = null;
            getName = null;
        }
        GET_MODULE = getModule;
        IS_NAMED = isNamed;
        GET_NAME = getName;
        MODULES_SUPPORTED = GET_MODULE != null;
    }

    /**
     * The outcome of trying to read a class's module.
     *
     * <p>{@link #isUnavailable()} and {@link #isUnnamed()} both mean "no usable module name",
     * but callers need to tell them apart: one means the module system could not be consulted at
     * all (no Java 9+ module system, or the reflective call itself threw), the other means it
     * answered and the class genuinely has no named module (the ordinary case for an
     * application/classpath class since Java 9). {@link TestClusterUtils#isJdkClass} and
     * {@link TestUsageChecker#isPlatformClass} each pick a different fallback for the first case,
     * so collapsing the two into a single {@code null}/boolean would erase that distinction.
     */
    static final class Result {

        private enum Kind {UNAVAILABLE, UNNAMED, NAMED}

        private static final Result UNAVAILABLE = new Result(Kind.UNAVAILABLE, null);
        private static final Result UNNAMED = new Result(Kind.UNNAMED, null);

        private final Kind kind;
        private final String moduleName;

        private Result(Kind kind, String moduleName) {
            this.kind = kind;
            this.moduleName = moduleName;
        }

        private static Result named(String moduleName) {
            return new Result(Kind.NAMED, moduleName);
        }

        /**
         * @return true if the module system could not be consulted: no Java 9+ module system on
         * this JVM, or the reflective lookup itself failed.
         */
        boolean isUnavailable() {
            return kind == Kind.UNAVAILABLE;
        }

        /**
         * @return true if the class's module was looked up successfully and has a name.
         */
        boolean isNamed() {
            return kind == Kind.NAMED;
        }

        /**
         * @return the module's name; only valid when {@link #isNamed()} is true.
         */
        String moduleName() {
            return moduleName;
        }
    }

    /**
     * Looks up the named module that loaded {@code c}, if any.
     *
     * <p>Mirrors, but does not decide between, the two things an absent name can mean: an
     * unnamed module (the lookup worked; the class simply is not in one) collapses here with a
     * {@code null} module object or module name, since both read the same way to a caller -- a
     * {@code getModule()} that returns null, or a module reporting a null name, come from the
     * same "no name to classify by" situation that {@code isNamed() == false} already covers.
     * Only a thrown exception -- no module system, or a refusal -- is reported separately as
     * {@link Result#isUnavailable()}.
     *
     * @param c the class to look up
     * @return the lookup outcome; see {@link Result}
     */
    static Result lookupModule(Class<?> c) {
        if (!MODULES_SUPPORTED) {
            return Result.UNAVAILABLE;
        }
        try {
            Object module = GET_MODULE.invoke(c);
            if (module == null) {
                return Result.UNNAMED;
            }
            boolean named = (Boolean) IS_NAMED.invoke(module);
            if (!named) {
                return Result.UNNAMED;
            }
            String name = (String) GET_NAME.invoke(module);
            return name == null ? Result.UNNAMED : Result.named(name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Result.UNAVAILABLE;
        }
    }
}
