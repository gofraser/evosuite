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
import java.net.URI;
import java.util.Optional;
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
 * <p>The {@link Method} objects are resolved once, in static initializers, rather than on every
 * lookup: {@code getMethod} itself -- a scan of the public method table plus a fresh {@code Method}
 * allocation -- is the expensive part, not the {@code invoke} calls that follow. Resolution can fail
 * wholesale (a real Java 8 JVM has no module system at all), which is recorded once in
 * {@link #MODULES_SUPPORTED} instead of being rediscovered on every call.
 */
final class JavaModuleLookup {

    private JavaModuleLookup() {
    }

    private static final Method GET_MODULE;
    private static final Method IS_NAMED;
    private static final Method GET_NAME;
    private static final boolean MODULES_SUPPORTED;

    /**
     * Reflective access to the chain {@code Module#getLayer()} -> {@code ModuleLayer#configuration()}
     * -> {@code Configuration#findModule(String)} -> {@code ResolvedModule#reference()} ->
     * {@code ModuleReference#location()} used by {@link #isFromSystemImage(Result)} to find where a
     * module's own resolved reference actually came from, plus {@code ModuleLayer.boot()} and
     * {@code ModuleLayer#modules()} used there to recover a boot-layer module whose
     * {@code getLayer()} reports {@code null} (see that method). Resolved independently of
     * {@link #GET_MODULE} and friends, the same way the rest of this class's Java 9+ accessors are.
     */
    private static final Method GET_LAYER;
    private static final Method LAYER_BOOT;
    private static final Method LAYER_MODULES;
    private static final Method LAYER_CONFIGURATION;
    private static final Method CONFIGURATION_FIND_MODULE;
    private static final Method RESOLVED_MODULE_REFERENCE;
    private static final Method MODULE_REFERENCE_LOCATION;
    private static final boolean IMAGE_ORIGIN_LOOKUP_SUPPORTED;

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

        Method getLayer;
        Method layerBoot;
        Method layerModules;
        Method layerConfiguration;
        Method configurationFindModule;
        Method resolvedModuleReference;
        Method moduleReferenceLocation;
        try {
            // Named independently of the Module/Class accessors above -- same Java 8 floor, same
            // "look it up by name because it cannot be named in a reflective signature" reasoning.
            Class<?> moduleClass = Class.forName("java.lang.Module");
            Class<?> moduleLayerClass = Class.forName("java.lang.ModuleLayer");
            Class<?> configurationClass = Class.forName("java.lang.module.Configuration");
            Class<?> resolvedModuleClass = Class.forName("java.lang.module.ResolvedModule");
            Class<?> moduleReferenceClass = Class.forName("java.lang.module.ModuleReference");

            getLayer = moduleClass.getMethod("getLayer");
            layerBoot = moduleLayerClass.getMethod("boot");
            layerModules = moduleLayerClass.getMethod("modules");
            layerConfiguration = moduleLayerClass.getMethod("configuration");
            configurationFindModule = configurationClass.getMethod("findModule", String.class);
            resolvedModuleReference = resolvedModuleClass.getMethod("reference");
            moduleReferenceLocation = moduleReferenceClass.getMethod("location");
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Same Java 8 case as above (no such classes), resolved independently of
            // MODULES_SUPPORTED.
            getLayer = null;
            layerBoot = null;
            layerModules = null;
            layerConfiguration = null;
            configurationFindModule = null;
            resolvedModuleReference = null;
            moduleReferenceLocation = null;
        }
        GET_LAYER = getLayer;
        LAYER_BOOT = layerBoot;
        LAYER_MODULES = layerModules;
        LAYER_CONFIGURATION = layerConfiguration;
        CONFIGURATION_FIND_MODULE = configurationFindModule;
        RESOLVED_MODULE_REFERENCE = resolvedModuleReference;
        MODULE_REFERENCE_LOCATION = moduleReferenceLocation;
        IMAGE_ORIGIN_LOOKUP_SUPPORTED = GET_LAYER != null;
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
     *
     * <p>A named result also carries the raw {@code Module} instance (as an untyped {@code
     * Object}, since this module's Java 8 release target cannot name {@code java.lang.Module} in a
     * field signature) so {@link #isFromSystemImage(Result)} can trace that specific module's own
     * resolved reference, rather than only its name.
     */
    static final class Result {

        private enum Kind {UNAVAILABLE, UNNAMED, NAMED}

        private static final Result UNAVAILABLE = new Result(Kind.UNAVAILABLE, null, null);
        private static final Result UNNAMED = new Result(Kind.UNNAMED, null, null);

        private final Kind kind;
        private final String moduleName;
        private final Object rawModule;

        private Result(Kind kind, String moduleName, Object rawModule) {
            this.kind = kind;
            this.moduleName = moduleName;
            this.rawModule = rawModule;
        }

        private static Result named(String moduleName, Object rawModule) {
            return new Result(Kind.NAMED, moduleName, rawModule);
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

        /**
         * @return the underlying {@code java.lang.Module} instance, untyped; only valid when
         * {@link #isNamed()} is true.
         */
        Object rawModule() {
            return rawModule;
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
            return name == null ? Result.UNNAMED : Result.named(name, module);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Result.UNAVAILABLE;
        }
    }

    /**
     * Whether {@code result}'s module is one the <em>running JDK's own install image</em> actually
     * shipped it from, rather than merely one with a {@code java.}/{@code jdk.}-prefixed name.
     *
     * <p>Neither a module's name nor its boot-layer membership proves this. A user module can
     * reuse the name of a module the image does contain but that is not part of the default boot
     * layer -- {@code jdk.hotspot.agent} is such a name on both Corretto 17 and OpenJDK 27 -- and
     * resolve its own, separately compiled copy of that name into a layer of its own. And a module
     * placed on {@code --module-path} and named in {@code --add-modules} at JVM <em>startup</em>
     * resolves into {@code ModuleLayer.boot()} the same as any genuine JDK module. The question
     * that actually settles it is where the specific {@code ResolvedModule} backing {@code
     * result}'s own module -- found by looking its own name up in its own layer's own {@code
     * Configuration}, never a global enumeration -- says it was loaded from: {@code
     * ModuleReference#location()} is {@code jrt:/<module>} for a module the image shipped, and a
     * {@code file:}/{@code jar:} URI (or anything else) for one resolved from anywhere else. This
     * check is independent of which loader actually defined the class, so a genuine image module
     * that the ordinary system/application loader defines -- {@code jdk.jdi} is such a module --
     * is still accepted.
     *
     * <p>{@code Module#getLayer()} can report {@code null} for a boot-layer class on some JDKs,
     * which is not the same as the class being in the unnamed module (that case is already
     * filtered out by {@link Result#isNamed()} before this method is ever called). When the layer
     * is {@code null}, this falls back to {@code ModuleLayer.boot()} only if {@code result}'s exact
     * module instance is a member of it (checked by identity, via {@code ModuleLayer#modules()});
     * a module built or resolved any other way is never boot's own object, so this recovery cannot
     * promote a non-boot module. If the module's reference cannot be traced this way either, the
     * method reports {@code false} rather than assuming JDK provenance: an inability to trace the
     * reference is treated as a negative, never a default "yes".
     *
     * @param result a {@link #lookupModule(Class)} outcome with {@link Result#isNamed()} true
     * @return true if the specific module {@code result} describes was resolved from the running
     * JDK's own install image
     */
    static boolean isFromSystemImage(Result result) {
        if (!result.isNamed() || !IMAGE_ORIGIN_LOOKUP_SUPPORTED) {
            return false;
        }
        try {
            Object module = result.rawModule();
            Object layer = GET_LAYER.invoke(module);
            if (layer == null) {
                Object bootLayer = LAYER_BOOT.invoke(null);
                Set<?> bootModules = (Set<?>) LAYER_MODULES.invoke(bootLayer);
                if (!bootModules.contains(module)) {
                    return false;
                }
                layer = bootLayer;
            }
            Object configuration = LAYER_CONFIGURATION.invoke(layer);
            @SuppressWarnings("unchecked")
            Optional<Object> resolvedModule =
                    (Optional<Object>) CONFIGURATION_FIND_MODULE.invoke(configuration, result.moduleName());
            if (!resolvedModule.isPresent()) {
                return false;
            }
            Object moduleReference = RESOLVED_MODULE_REFERENCE.invoke(resolvedModule.get());
            @SuppressWarnings("unchecked")
            Optional<URI> location = (Optional<URI>) MODULE_REFERENCE_LOCATION.invoke(moduleReference);
            return location.isPresent() && "jrt".equals(location.get().getScheme());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
