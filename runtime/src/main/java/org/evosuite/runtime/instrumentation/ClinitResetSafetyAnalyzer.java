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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Detects classes whose {@code <clinit>} is unsafe to replay as
 * {@code __STATIC_RESET()}, because it scans the classpath/resources to
 * build an identity-keyed or singleton registry. Replaying such a
 * {@code <clinit>} is not idempotent: a rescanned resource set can come
 * back empty, or a freshly built object can no longer match the identity
 * used to key an existing map, silently corrupting the registry for the
 * rest of the run rather than throwing (which {@link
 * org.evosuite.runtime.classhandling.ClassResetter}'s poison-on-throw path
 * already handles).
 *
 * <p>Two verified real-world instances motivate this rule:
 * <ul>
 *     <li>{@code org.firebirdsql.gds.impl.GDSFactory} (110_firebird):
 *     {@code <clinit>} calls {@code Thread.currentThread().getContextClassLoader()}
 *     directly and, through a private static helper, {@code
 *     ClassLoader.getResources("META-INF/services/...")}, to populate a
 *     {@code HashMap<GDSType, GDSFactoryPlugin>} keyed on object identity.</li>
 *     <li>{@code com.liferay.portal.kernel.util.SystemProperties} (108_liferay):
 *     {@code <clinit>} does {@code _instance = new SystemProperties()}, and the
 *     constructor it invokes reads {@code system.properties} off {@code
 *     Thread.currentThread().getContextClassLoader()} into an instance-level
 *     {@code Map}.</li>
 * </ul>
 *
 * <p>The rule is intentionally narrow so it stays rare instead of firing
 * across the whole corpus. It requires <em>both</em>:
 * <ol>
 *     <li>a call that is a strong signal of classpath/resource scanning -
 *     {@code ClassLoader.getResource(s)}, {@code
 *     ClassLoader.getSystemResource(s)}, {@code ServiceLoader.load(Installed)},
 *     or {@code Thread.getContextClassLoader()} - reachable from {@code
 *     <clinit>} by following calls the class makes to its own methods or
 *     constructor (this is what lets the Liferay case, where the scan lives
 *     in the constructor invoked by {@code <clinit>}, be detected); and</li>
 *     <li>evidence that the class is building a registry: either a static
 *     field of a known {@code Map}/{@code Collection} type, or a self-typed
 *     static singleton field (the classic {@code "private static X instance
 *     = new X()"} pattern) combined with an instance field of a known {@code
 *     Map}/{@code Collection} type on the same class.</li>
 * </ol>
 *
 * <p>{@code Class.forName} alone is deliberately <em>not</em> a trigger - it
 * is far too common in ordinary code to be a reliable signal on its own, and
 * using it here would disable reset across huge swathes of the corpus.
 *
 * <p><b>Known exception: {@code org.firebirdsql.gds.impl.GDSFactory} itself
 * is excluded</b> ({@link #NEVER_SKIP_EXACT_CLASSES}), even though it
 * matches the rule above. Verified by paired before/after EvoSuite runs on
 * {@code org.firebirdsql.management.FBNBackupManager}: permanently skipping
 * its reset does not merely fail to help, it makes every tested seed
 * collapse (branch coverage capped near the first generation), including
 * seeds where the unmodified baseline was healthy. The mechanism is
 * different from the read-once-then-frozen case this rule is designed for:
 * {@code GDSFactory.registerPlugin(GDSFactoryPlugin)} is a <em>public</em>
 * method that mutates the very registry {@code <clinit>} builds, and
 * EvoSuite's fuzzing calls it directly with generated/mocked plugins during
 * the search. A plugin's type name is attacker-controlled and can collide
 * with a well-known key (e.g. {@code "PURE_JAVA"}), silently shadowing the
 * real entry. Because the registry is never rebuilt (that is the whole
 * point of skipping the reset), that corruption is then permanent for the
 * rest of the run - so for a registry with a public, externally-reachable
 * mutator over a small/collidable key space, never-reset is strictly worse
 * than the imperfect-replay status quo, not an improvement. This is
 * distinct from {@code SystemProperties}, whose only public mutator
 * ({@code set(String, String)}) writes to an open string-keyed namespace
 * that a fuzzer colliding with a framework-critical key is astronomically
 * unlikely to hit in practice - confirmed by paired runs recovering {@code
 * RandomAccessInputStream} with no regressions across five healthy
 * liferay/weka/jgaap/vuze controls.
 *
 * <p>No general bytecode-level rule was found that reliably separates these
 * two shapes (both mutate through a preserved map/set instance via {@code
 * .put}/{@code .add} rather than by reassigning the field, so "no writes
 * outside {@code <clinit>}" does not distinguish them) without either also
 * excluding the liferay case or requiring full points-to/key-space
 * reasoning this analyzer does not attempt. Until such a rule exists, the
 * known-bad case is excluded by name rather than left to silently regress a
 * verified-healthy subject.
 *
 * <p>A static scan of every class in all 128 SF110 project jars (49,312
 * classes) found only 6 matches for the base rule, reviewed by hand for a
 * public write path into the registry (the {@code registerPlugin} shape):
 * {@code corina.Species} and {@code MetadataFactory} both have one into a
 * small, collidable key space and are excluded here for the same reason as
 * {@code GDSFactory}, without having been dynamically tested - the static
 * evidence alone was judged strong enough not to risk it. {@code
 * ServerFactory} and {@code JWBF} have no public write path at all (the
 * registry field is private and, for {@code JWBF}, only exposed as an
 * unmodifiable view), so they are structurally safer than the verified-safe
 * {@code SystemProperties} case and remain flagged. {@code LicenseUtil}'s
 * only public mutator is fed by {@code NetworkInterface} enumeration - real
 * machine data EvoSuite cannot influence - so there is no collision to
 * engineer, and it remains flagged too.
 */
public final class ClinitResetSafetyAnalyzer {

    private ClinitResetSafetyAnalyzer() {
    }

    /**
     * Exact (dotted) class names for which this analyzer must never report
     * {@code true}, regardless of what the structural rule below would say.
     * See the class javadoc for why {@code GDSFactory} is here despite
     * matching the rule, and why {@code GDSType} is included preemptively
     * (it independently matches only under the coupled-bootstrap cross-class
     * check, which is not active in the base rule, but is listed here so
     * re-enabling that check does not silently reintroduce the same
     * regression without this override).
     */
    private static final Set<String> NEVER_SKIP_EXACT_CLASSES = new HashSet<>(Arrays.asList(
            "org.firebirdsql.gds.impl.GDSFactory",
            "org.firebirdsql.gds.impl.GDSType",
            // corina.Species: registry fields "species" (Properties) and "common"
            // (List) are declared "public static final" - any caller, including a
            // generated test, can mutate them directly (e.g. Species.species.put(...))
            // with no method call to gate it at all, which is even more exposed than
            // GDSFactory.registerPlugin(). The keys are a small closed set of 4-letter
            // species codes that the class's own source embeds as a string constant
            // (DEFAULT = "ABSP,ACSP,..."), which is exactly the kind of literal
            // EvoSuite's constant-seeding tends to reuse in generated tests - the same
            // collidable-key-space shape that made GDSFactory unsafe. Not dynamically
            // tested; excluded on the strength of the static evidence alone rather
            // than risk corrupting 35_corina's other classes to find out.
            "corina.Species",
            // com.atlassw.tools.eclipse.checkstyle.config.meta.MetadataFactory: public
            // static createGenericMetadata(Module) does
            // sRuleMetadata.put(ruleMeta.getInternalName(), ruleMeta) - a
            // registerPlugin-shaped public write keyed by a caller-supplied object's
            // name, into the same registry <clinit> scans classpath XML into. Not
            // dynamically tested; excluded for the same reason as Species.
            "com.atlassw.tools.eclipse.checkstyle.config.meta.MetadataFactory"
    ));

    /**
     * Known JDK {@code Map}/{@code Collection} internal names. Deliberately
     * restricted to the standard library rather than trying to resolve
     * third-party types, to keep the check cheap (no classpath resolution)
     * and conservative (no false positives from types we can't verify).
     */
    private static final Set<String> COLLECTION_TYPES = new HashSet<>(Arrays.asList(
            "java/util/Map", "java/util/HashMap", "java/util/Hashtable", "java/util/TreeMap",
            "java/util/LinkedHashMap", "java/util/IdentityHashMap", "java/util/WeakHashMap",
            "java/util/EnumMap", "java/util/SortedMap", "java/util/NavigableMap",
            "java/util/Properties", "java/util/Dictionary",
            "java/util/concurrent/ConcurrentHashMap", "java/util/concurrent/ConcurrentMap",
            "java/util/concurrent/ConcurrentSkipListMap",
            "java/util/List", "java/util/ArrayList", "java/util/LinkedList", "java/util/Vector",
            "java/util/concurrent/CopyOnWriteArrayList",
            "java/util/Set", "java/util/HashSet", "java/util/TreeSet", "java/util/LinkedHashSet",
            "java/util/SortedSet", "java/util/NavigableSet", "java/util/EnumSet",
            "java/util/concurrent/CopyOnWriteArraySet",
            "java/util/Collection",
            "java/util/concurrent/ConcurrentLinkedQueue", "java/util/concurrent/ConcurrentLinkedDeque"
    ));

    /**
     * Parses the class from {@code reader} and decides whether its {@code
     * <clinit>} is unsafe to replay. Does not mutate or consume {@code
     * reader} in a way that prevents it from being reused by the caller for
     * the real instrumentation pass.
     *
     * @param reader a fresh {@link ClassReader} over the original,
     *               uninstrumented bytecode
     * @return true if {@code __STATIC_RESET()} should not be generated for
     * this class
     */
    public static boolean isUnsafeToReplayClinit(ClassReader reader) {
        ClassNode cn = new ClassNode();
        reader.accept(cn, ClassReader.SKIP_FRAMES);
        return isUnsafeToReplayClinit(cn);
    }

    static boolean isUnsafeToReplayClinit(ClassNode cn) {
        if (NEVER_SKIP_EXACT_CLASSES.contains(cn.name.replace('/', '.'))) {
            return false;
        }
        MethodNode clinit = findMethod(cn, "<clinit>", null);
        if (clinit == null || clinit.instructions == null) {
            return false;
        }
        if (!hasRegistryFieldSignal(cn)) {
            return false;
        }
        return reachesResourceScanCall(cn, clinit);
    }

    private static boolean hasRegistryFieldSignal(ClassNode cn) {
        String selfDesc = "L" + cn.name + ";";
        boolean hasCollectionStatic = false;
        boolean hasSelfSingletonStatic = false;
        boolean hasCollectionInstance = false;

        for (FieldNode f : cn.fields) {
            boolean isStatic = (f.access & Opcodes.ACC_STATIC) != 0;
            if (isStatic && isKnownCollectionType(f.desc)) {
                hasCollectionStatic = true;
            } else if (isStatic && selfDesc.equals(f.desc)) {
                hasSelfSingletonStatic = true;
            } else if (!isStatic && isKnownCollectionType(f.desc)) {
                hasCollectionInstance = true;
            }
        }
        return hasCollectionStatic || (hasSelfSingletonStatic && hasCollectionInstance);
    }

    private static boolean isKnownCollectionType(String desc) {
        if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L') {
            return false;
        }
        String internalName = desc.substring(1, desc.length() - 1);
        return COLLECTION_TYPES.contains(internalName);
    }

    /**
     * Breadth-first search from {@code entry}, following calls the class
     * makes to its own methods/constructors, looking for a resource-scan
     * call. Bounded by the number of methods declared in the class, since
     * we only ever follow same-class call edges.
     */
    private static boolean reachesResourceScanCall(ClassNode cn, MethodNode entry) {
        Set<String> visited = new HashSet<>();
        Deque<MethodNode> worklist = new ArrayDeque<>();
        worklist.add(entry);
        visited.add(entry.name + entry.desc);

        while (!worklist.isEmpty()) {
            MethodNode m = worklist.poll();
            if (m.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : m.instructions) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode min = (MethodInsnNode) insn;
                if (isResourceScanCall(min.owner, min.name)) {
                    return true;
                }
                if (min.owner.equals(cn.name)) {
                    String key = min.name + min.desc;
                    if (visited.add(key)) {
                        MethodNode target = findMethod(cn, min.name, min.desc);
                        if (target != null) {
                            worklist.add(target);
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean isResourceScanCall(String owner, String name) {
        if (owner.endsWith("ClassLoader")) {
            if (name.equals("getResource") || name.equals("getResources")
                    || name.equals("getSystemResource") || name.equals("getSystemResources")) {
                return true;
            }
        }
        if (owner.equals("java/util/ServiceLoader")
                && (name.equals("load") || name.equals("loadInstalled"))) {
            return true;
        }
        return owner.equals("java/lang/Thread") && name.equals("getContextClassLoader");
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) {
                return m;
            }
        }
        return null;
    }
}
