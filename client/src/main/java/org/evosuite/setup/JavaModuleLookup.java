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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Reflective access to a class's Java 9+ module, shared by {@link TestUsageChecker#isPlatformClass}
 * and {@link TestClusterUtils#isJdkClass}.
 *
 * <p>Both callers need to know which named module (if any) loaded a class, but this module
 * compiles against the Java 8 API ({@code --release 8}, project-wide), so {@code Class#getModule()}
 * cannot be called directly; it has to go through {@code Class.getMethod("getModule")} and then
 * {@code Module#isNamed()} / {@code Module#getName()} the same way. This class performs only that
 * lookup, not the classification each caller builds on top of it -- see {@link Result} for why an
 * unavailable module system and a named-but-absent module are kept distinct.
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

    /**
     * Names of every module in the running JDK's own install image, i.e. exactly the modules
     * {@code java.lang.module.ModuleFinder#ofSystem()} enumerates -- or {@code null} if that
     * enumeration itself is unavailable (plain Java 8, or a refusal). See
     * {@link #isSystemModule(String)} for why this, and not boot-layer membership, is the
     * signal that actually answers "did this module come from the running JDK's image?".
     */
    private static final Set<String> SYSTEM_MODULE_NAMES;

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

        Set<String> systemModuleNames;
        try {
            // java.lang.module.ModuleFinder is itself Java 9+, so even naming it in a
            // reflective Method/Class signature would not compile under this module's Java 8
            // release target; look everything up by name, independently of the Module
            // accessors above since it is resolved from entirely different classes.
            Class<?> moduleFinderClass = Class.forName("java.lang.module.ModuleFinder");
            Object systemFinder = moduleFinderClass.getMethod("ofSystem").invoke(null);
            Set<?> systemRefs = (Set<?>) moduleFinderClass.getMethod("findAll").invoke(systemFinder);

            Class<?> moduleReferenceClass = Class.forName("java.lang.module.ModuleReference");
            Method descriptorOf = moduleReferenceClass.getMethod("descriptor");
            Class<?> moduleDescriptorClass = Class.forName("java.lang.module.ModuleDescriptor");
            Method nameOf = moduleDescriptorClass.getMethod("name");

            Set<String> names = new HashSet<>();
            for (Object ref : systemRefs) {
                names.add((String) nameOf.invoke(descriptorOf.invoke(ref)));
            }
            systemModuleNames = Collections.unmodifiableSet(names);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Same Java 8 case as above (no such classes), resolved independently of
            // MODULES_SUPPORTED.
            systemModuleNames = null;
        }
        SYSTEM_MODULE_NAMES = systemModuleNames;
    }

    /**
     * The outcome of trying to read a class's module.
     *
     * <p>An unavailable module system ({@link #isUnavailable()}) and a lookup that succeeded but
     * found no name ({@link #isNamed()} false) both mean "no usable module name", but callers
     * need to tell them apart: the first means the module system could not be consulted at all
     * (no Java 9+ module system, or the reflective call itself threw), the second means it
     * answered and the class genuinely has no named module (the ordinary case for an
     * application/classpath class since Java 9). {@link TestClusterUtils#isJdkClass} treats the
     * first case as "assume JDK"; {@link TestUsageChecker#isPlatformClass} instead falls back to
     * a bootstrap-loader check -- so collapsing the two into a single {@code null}/boolean would
     * erase that distinction.
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
     * <p>A {@code getModule()} that returns null, a module reporting {@code isNamed() == false},
     * and a named module with a null name all collapse to {@link Result#isNamed()} {@code ==
     * false}; only a thrown exception (no module system, or a refusal) is reported separately as
     * {@link Result#isUnavailable()}. See {@link Result} for why that split matters to callers.
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

    /**
     * Whether {@code moduleName} names a module that ships in the <em>running JDK's own install
     * image</em> -- i.e. one {@code java.lang.module.ModuleFinder#ofSystem()} enumerates --
     * rather than one merely resolved by the JVM at some point, from wherever it was found.
     *
     * <p>This replaces an earlier, incorrect signal: whether the class's module was resolved
     * into {@code ModuleLayer.boot()}. Boot-layer membership was meant to catch a user module
     * that names itself {@code java.fake} or {@code jdk.whatever} to spoof {@link
     * TestUsageChecker#isPlatformClass} or {@code TestClusterUtils#isJdkClass}, on the reasoning
     * that an application cannot make a dynamically defined layer ({@code
     * ModuleLayer.defineModulesWithOneLoader} and friends) equal to the boot layer. That
     * reasoning only covers a layer the application builds itself at run time. It does not cover
     * a module placed on {@code --module-path} and named in {@code --add-modules} at JVM
     * <em>startup</em>: that module is resolved into {@code ModuleLayer.boot()} right alongside
     * every genuine JDK module, by the same startup resolution step, so a spoof launched that
     * way passed the boot-layer check -- measurably, on a module named {@code java.fake} started
     * with {@code --module-path}/{@code --add-modules} -- even though it is ordinary application
     * code with no connection to the running JDK.
     *
     * <p>Module name is a red herring here too: the question is not what a module calls itself,
     * nor which layer or loader it ends up in, but whether its name is one the running JDK
     * actually ships. {@code ModuleFinder.ofSystem()} answers exactly that, directly from the
     * JDK's own module graph, independently of how or when the class being classified was
     * resolved -- so it correctly accepts {@code jdk.jdi} (an app-loader-defined but genuine JDK
     * tool module) and correctly rejects {@code java.fake} whether that spoof is built into a
     * runtime-created layer or resolved by the JVM at startup. {@link
     * TestUsageChecker#isPlatformClass} combines this with the named-module and prefix checks so
     * a user module cannot pass for the JDK merely by naming itself {@code java.*} or {@code
     * jdk.*}.
     *
     * @param moduleName the module name to check, as returned by {@link Result#moduleName()}
     * @return true if a module of that name is part of the running JDK's install image
     */
    static boolean isSystemModule(String moduleName) {
        return SYSTEM_MODULE_NAMES != null && SYSTEM_MODULE_NAMES.contains(moduleName);
    }
}
