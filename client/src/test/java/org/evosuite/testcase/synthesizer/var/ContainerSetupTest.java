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
package org.evosuite.testcase.synthesizer.var;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import com.examples.with.different.packagename.objectconstruction.tuning.MembershipGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Generated text and random-number consumption of the routines that populate containers in the
 * object-rule synthesizer: the mutator lookups, the argument factories and the insertion positions.
 */
public class ContainerSetupTest {

    private String previousPrefix;

    @BeforeEach
    public void setUp() {
        previousPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = ContainerSetupTest.class.getPackage().getName();
    }

    @AfterEach
    public void tearDown() {
        Properties.CLASS_PREFIX = previousPrefix;
        Properties.TARGET_CLASS = "";
        Properties.TARGET_METHOD = "";
        TargetContext.clearActive();
        org.evosuite.TestGenerationContext.getInstance().resetContext();
    }

    // Fixtures

    public static class Element {
    }

    public static class Maker {
        public static Maker of() {
            return new Maker();
        }

        public static Maker another() {
            return new Maker();
        }

        private Maker() {
        }
    }

    public static class Opaque {
        private Opaque() {
        }
    }

    public enum Colour {
        RED, GREEN
    }

    public static class BaseBag {
        public void addBase(String value) {
        }

        public void add(String value) {
        }
    }

    /** Declares public, package-private, inherited, static and private mutators. */
    public static class Inventory extends BaseBag {
        public boolean contains(String value) {
            return false;
        }

        public List<String> getItems() {
            return null;
        }

        @Override
        public void add(String value) {
        }

        public void putEntry(String key, int count) {
        }

        void addPackage(int value) {
        }

        public static void addStatic(String value) {
        }

        private void addHidden(String value) {
        }

        public void addAll(Collection<String> values) {
        }

        public void addColour(Colour colour) {
        }

        public void addByte(byte value) {
        }

        public void addElement(Element element) {
        }

        public void addOpaque(Opaque opaque) {
        }
    }

    public static class PackageOnlyBag {
        public boolean contains(String value) {
            return false;
        }

        void set(String value) {
        }

        void add(String value) {
        }
    }

    public static class InheritingBag extends BaseBag {
    }

    /** The erased {@code add(Object)} bridge is public, the typed method is not the bridge. */
    public static final class NameBag extends java.util.AbstractCollection<String> {
        @Override
        public boolean add(String name) {
            return true;
        }

        @Override
        public java.util.Iterator<String> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public int size() {
            return 0;
        }
    }

    public static class Subject {
        public boolean accepts(Object value) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private Map<String, String> stringMap;
    @SuppressWarnings("unused")
    private Map<String, Integer> integerMap;
    @SuppressWarnings("unused")
    private Map<String, List<Element>> listMap;
    @SuppressWarnings("unused")
    private Map<String, List<Maker>> makerListMap;
    @SuppressWarnings("unused")
    private Map<String, List<Opaque>> opaqueListMap;
    @SuppressWarnings("unused")
    private Map<String, Set<Integer>> integerSetMap;
    @SuppressWarnings("unused")
    private Map<String, Object> objectMap;
    @SuppressWarnings("unused")
    private List<String> stringList;
    @SuppressWarnings("unused")
    private List<Element> elementList;

    // Helpers

    private static GenericClass<?> typeOfField(String name) throws Exception {
        Field field = ContainerSetupTest.class.getDeclaredField(name);
        return GenericClassFactory.get(field.getGenericType());
    }

    private static VariableReference construct(TestCase test, GenericClass<?> type) throws Exception {
        Constructor<?> constructor = type.getRawClass().getConstructor();
        return test.addStatement(new ConstructorStatement(
                test, new GenericConstructor(constructor, type), Collections.emptyList()));
    }

    private static VariableReference construct(TestCase test, Class<?> type) throws Exception {
        return construct(test, GenericClassFactory.get(type));
    }

    private static GenericClass<?> concrete(Class<?> raw, String field) throws Exception {
        GenericClass<?> declared = typeOfField(field);
        return GenericClassFactory.get(raw).getWithParameterTypes(declared.getParameterTypes());
    }

    private static String code(TestCase test) {
        return test.toCode().trim();
    }

    /** The first value of a fresh generator: what a routine that draws nothing leaves behind. */
    private static int firstDrawAfterSeed(long seed) {
        Randomness.setSeed(seed);
        return Randomness.nextInt();
    }

    private static String lines(String... lines) {
        return String.join("\n", lines);
    }

    private static String describe(Method method) {
        return method == null ? "null" : method.getDeclaringClass().getSimpleName() + "."
                + method.getName() + Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName).collect(Collectors.joining(",", "(", ")"));
    }

    private static List<String> describe(List<Method> methods) {
        return methods.stream().map(ContainerSetupTest::describe).collect(Collectors.toList());
    }

    private static void targetIsAccepts(TestCase test, VariableReference receiver,
            VariableReference argument) throws Exception {
        Method accepts = Subject.class.getMethod("accepts", Object.class);
        TargetContext.setActive(new TargetContext(Subject.class.getName(),
                accepts.getName() + MethodUtil.getSignature(accepts), null));
        test.addStatement(new MethodStatement(test, new GenericMethod(accepts, Subject.class),
                receiver, Collections.singletonList(argument)));
    }

    private static VariableReference constructSubject(TestCase test) throws Exception {
        return construct(test, Subject.class);
    }

    private static OtherVariableWrapper observation(String name) {
        BytecodeInstruction instruction = mock(BytecodeInstruction.class);
        when(instruction.getASMNode()).thenReturn(
                new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", name, "()I", true));
        DepVariable variable = mock(DepVariable.class);
        when(variable.getInstruction()).thenReturn(instruction);
        return new OtherVariableWrapper(variable);
    }

    private static ParameterVariableWrapper parameterWith(String... queries) {
        ParameterVariableWrapper wrapper = new ParameterVariableWrapper(null);
        for (String query : queries) {
            wrapper.children.add(observation(query));
        }
        return wrapper;
    }

    /** A wrapper without bytecode evidence, or with a fixed cast type for the observed map value. */
    private static OtherVariableWrapper otherWrapper(Class<?> inferredValueType) {
        return new OtherVariableWrapper(null) {
            @Override
            Class<?> inferMapValueCast(String key) {
                return inferredValueType;
            }
        };
    }

    // Mutator lookup

    @Test
    public void collectionMutationCandidatesAreTheCallableAddAndPutMethods() {
        assertEquals(Arrays.asList("Inventory.add(String)", "Inventory.addAll(Collection)", "BaseBag.addBase(String)", "Inventory.addByte(byte)", "Inventory.addColour(Colour)", "Inventory.addElement(Element)", "Inventory.addOpaque(Opaque)", "Inventory.addPackage(int)", "Inventory.putEntry(String,int)"), describe(
                OtherVariableWrapper.collectionMutationCandidates(Inventory.class)));
        assertEquals(Arrays.asList("BaseBag.add(String)", "BaseBag.addBase(String)"), describe(
                OtherVariableWrapper.collectionMutationCandidates(InheritingBag.class)));
    }

    @Test
    public void membershipMutationLookup() throws Exception {
        Method contains = Inventory.class.getMethod("contains", String.class);
        assertEquals(Arrays.asList("Inventory.add(String)", "PackageOnlyBag.add(String)", "BaseBag.add(String)"), Arrays.asList(
                describe(OtherVariableWrapper.findMembershipMutation(
                        contains, Inventory.class, String.class)),
                describe(OtherVariableWrapper.findMembershipMutation(
                        contains, PackageOnlyBag.class, String.class)),
                describe(OtherVariableWrapper.findMembershipMutation(
                        contains, InheritingBag.class, String.class))));
    }

    @Test
    public void listInsertionLookup() {
        assertEquals(Arrays.asList("Inventory.add(String)", "PackageOnlyBag.add(String)", "BaseBag.add(String)"), Arrays.asList(
                describe(OtherVariableWrapper.findCompatibleListInsertionMethod(
                        Inventory.class, String.class)),
                describe(OtherVariableWrapper.findCompatibleListInsertionMethod(
                        PackageOnlyBag.class, String.class)),
                describe(OtherVariableWrapper.findCompatibleListInsertionMethod(
                        InheritingBag.class, String.class))));
    }

    @Test
    public void elementAddLookup() {
        assertEquals(Arrays.asList("Inventory.add(String)", "PackageOnlyBag.add(String)", "BaseBag.add(String)"), Arrays.asList(
                describe(VariableCodeGenerationUtil.findElementAddMethod(Inventory.class)),
                describe(VariableCodeGenerationUtil.findElementAddMethod(PackageOnlyBag.class)),
                describe(VariableCodeGenerationUtil.findElementAddMethod(InheritingBag.class))));
    }

    // Deterministic values

    @Test
    public void deterministicElementPrefersConstructorThenFactory() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference string = ContainerSetup.element(
                test, GenericClassFactory.get(String.class), 0);
        VariableReference object = ContainerSetup.element(
                test, GenericClassFactory.get(Object.class), 1);
        VariableReference element = ContainerSetup.element(
                test, GenericClassFactory.get(Element.class), 2);
        VariableReference maker = ContainerSetup.element(
                test, GenericClassFactory.get(Maker.class), 3);
        VariableReference opaque = ContainerSetup.element(
                test, GenericClassFactory.get(Opaque.class), 4);
        VariableReference integer = ContainerSetup.element(
                test, GenericClassFactory.get(Integer.class), 5);
        assertNotNull(string);
        assertNotNull(object);
        assertNotNull(element);
        assertNotNull(maker);
        assertNull(opaque);
        assertNull(integer);
        assertEquals(lines(
                "String string0 = \"value\";",
                "String string1 = \"value\";",
                "ContainerSetupTest.Element containerSetupTest_Element0 = new ContainerSetupTest.Element();",
                "ContainerSetupTest.Maker.another();"),
                code(test));
    }

    @Test
    public void deterministicContainerValueByRequestedType() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference map = construct(test, concrete(LinkedHashMap.class, "listMap"));
        ContainerSetup.containerValue(test, map, true, test.size());
        VariableReference integerMap = construct(test, concrete(LinkedHashMap.class, "integerMap"));
        ContainerSetup.containerValue(test, integerMap, true, test.size());
        VariableReference objectMap = construct(test, concrete(LinkedHashMap.class, "objectMap"));
        ContainerSetup.containerValue(test, objectMap, true, test.size());
        VariableReference setMap = construct(test, concrete(LinkedHashMap.class, "integerSetMap"));
        ContainerSetup.containerValue(test, setMap, true, test.size());
        VariableReference makerMap = construct(test, concrete(LinkedHashMap.class, "makerListMap"));
        ContainerSetup.containerValue(test, makerMap, true, test.size());
        VariableReference opaqueMap = construct(test, concrete(LinkedHashMap.class, "opaqueListMap"));
        ContainerSetup.containerValue(test, opaqueMap, true, test.size());
        assertEquals(lines(
                "LinkedHashMap<String, List<ContainerSetupTest.Element>> linkedHashMap0 = new LinkedHashMap<String, List<ContainerSetupTest.Element>>();",
                "ArrayList arrayList0 = new ArrayList<>();",
                "ContainerSetupTest.Element containerSetupTest_Element0 = new ContainerSetupTest.Element();",
                "((Collection)arrayList0).add((Object) containerSetupTest_Element0);",
                "LinkedHashMap<String, Integer> linkedHashMap1 = new LinkedHashMap<String, Integer>();",
                "Integer nullRef0 = null;",
                "LinkedHashMap<String, Object> linkedHashMap2 = new LinkedHashMap<String, Object>();",
                "String string0 = \"value\";",
                "LinkedHashMap<String, Set<Integer>> linkedHashMap3 = new LinkedHashMap<String, Set<Integer>>();",
                "LinkedHashSet linkedHashSet0 = new LinkedHashSet<>();",
                "LinkedHashMap<String, List<ContainerSetupTest.Maker>> linkedHashMap4 = new LinkedHashMap<String, List<ContainerSetupTest.Maker>>();",
                "ArrayList arrayList1 = new ArrayList<>();",
                "ContainerSetupTest.Maker containerSetupTest_Maker0 = ContainerSetupTest.Maker.another();",
                "((Collection)arrayList1).add((Object) containerSetupTest_Maker0);",
                "LinkedHashMap<String, List<ContainerSetupTest.Opaque>> linkedHashMap5 = new LinkedHashMap<String, List<ContainerSetupTest.Opaque>>();",
                "ArrayList arrayList2 = new ArrayList<>();"),
                code(test));
    }

    @Test
    public void deterministicContainerMutationForMapAndList() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference map = construct(test, concrete(LinkedHashMap.class, "stringMap"));
        ContainerSetup.addEntry(test, map,
                new GenericMethod(LinkedHashMap.class.getMethod("put", Object.class, Object.class),
                        LinkedHashMap.class), test.size());
        VariableReference list = construct(test, concrete(ArrayList.class, "elementList"));
        ContainerSetup.addEntry(test, list,
                new GenericMethod(ArrayList.class.getMethod("add", Object.class), ArrayList.class),
                test.size());
        assertEquals(lines(
                "LinkedHashMap<String, String> linkedHashMap0 = new LinkedHashMap<String, String>();",
                "String string0 = \"key\";",
                "String string1 = \"value\";",
                "linkedHashMap0.put(string0, string1);",
                "ArrayList<ContainerSetupTest.Element> arrayList0 = new ArrayList<ContainerSetupTest.Element>();",
                "ContainerSetupTest.Element nullRef0 = null;",
                "arrayList0.add((ContainerSetupTest.Element) null);"),
                code(test));
    }

    @Test
    public void mutationArgumentsAndTheirRandomness() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference receiver = construct(test, Inventory.class);
        Randomness.setSeed(7L);
        for (Class<?> parameter : new Class<?>[] {byte.class, int.class, boolean.class,
                String.class, Colour.class, Element.class, Inventory.class, Object.class}) {
            assertNotNull(ContainerSetup.mutationArgument(
                    test, receiver, parameter, test.size()));
        }
        for (Class<?> parameter : new Class<?>[] {Opaque.class, Collection.class, List.class}) {
            assertNull(ContainerSetup.mutationArgument(
                    test, receiver, parameter, test.size()));
        }
        assertEquals(lines(
                "ContainerSetupTest.Inventory containerSetupTest_Inventory0 = new ContainerSetupTest.Inventory();",
                "byte byte0 = (byte)1;",
                "int int0 = 0;",
                "boolean boolean0 = false;",
                "String string0 = \"\";",
                "ContainerSetupTest.Colour containerSetupTest_Colour0 = ContainerSetupTest.Colour.RED;",
                "ContainerSetupTest.Element containerSetupTest_Element0 = new ContainerSetupTest.Element();",
                "ContainerSetupTest.Inventory containerSetupTest_Inventory1 = new ContainerSetupTest.Inventory();",
                "ContainerSetupTest.Inventory containerSetupTest_Inventory2 = new ContainerSetupTest.Inventory();"),
                code(test));
        int next = Randomness.nextInt();
        assertEquals(firstDrawAfterSeed(7L), next);
    }

    @Test
    public void mapValueForObservedKeyByInferredType() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference receiver = construct(test, LinkedHashMap.class);
        VariableReference key = test.addStatement(new StringPrimitiveStatement(test, "symbols"));
        for (Class<?> inferred : new Class<?>[] {null, Object.class, String.class, ArrayList.class,
                List.class, Integer.class, Element.class, Opaque.class}) {
            assertNotNull(ContainerSetup.observedMapValue(test, inferred, test.size()));
        }
        assertEquals(lines(
                "LinkedHashMap linkedHashMap0 = new LinkedHashMap<>();",
                "String string0 = \"symbols\";",
                "String string1 = \"value\";",
                "Object object0 = new Object();",
                "String string2 = new String();",
                "ArrayList arrayList0 = new ArrayList<>();",
                "List nullRef0 = null;",
                "Integer nullRef1 = null;",
                "ContainerSetupTest.Element containerSetupTest_Element0 = new ContainerSetupTest.Element();",
                "ContainerSetupTest.Opaque nullRef2 = null;"),
                code(test));
    }

    // Observation materialisation

    @Test
    public void membershipObservationOnMapAndList() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference map = construct(test, LinkedHashMap.class);
        VariableReference list = construct(test, ArrayList.class);
        VariableReference key = test.addStatement(new StringPrimitiveStatement(test, "k"));
        otherWrapper(null).materializeMembershipObservation(test,
                Map.class.getMethod("containsKey", Object.class), map, observed(key));
        otherWrapper(null).materializeMembershipObservation(test,
                List.class.getMethod("contains", Object.class), list, observed(key));
        assertEquals(lines(
                "LinkedHashMap linkedHashMap0 = new LinkedHashMap<>();",
                "ArrayList arrayList0 = new ArrayList<>();",
                "String string0 = \"k\";",
                "String string1 = \"value\";",
                "((HashMap)linkedHashMap0).put((Object) string0, (Object) string1);",
                "((ArrayList)arrayList0).add((Object) string0);"),
                code(test));
    }

    @Test
    public void membershipObservationIsInsertedBeforeTheTarget() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference map = construct(test, LinkedHashMap.class);
        VariableReference key = test.addStatement(new StringPrimitiveStatement(test, "k"));
        VariableReference subject = constructSubject(test);
        targetIsAccepts(test, subject, key);
        test.addStatement(new StringPrimitiveStatement(test, "after"));
        otherWrapper(null).materializeMembershipObservation(test,
                Map.class.getMethod("containsKey", Object.class), map, observed(key));
        assertEquals(lines(
                "LinkedHashMap linkedHashMap0 = new LinkedHashMap<>();",
                "String string0 = \"k\";",
                "ContainerSetupTest.Subject containerSetupTest_Subject0 = new ContainerSetupTest.Subject();",
                "String string1 = \"value\";",
                "((HashMap)linkedHashMap0).put((Object) string0, (Object) string1);",
                "containerSetupTest_Subject0.accepts(string0);",
                "String string2 = \"after\";"),
                code(test));
    }

    @Test
    public void collectionQueryObservationPopulatesTheReceiver() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference receiver = construct(test, Inventory.class);
        VariableReference subject = constructSubject(test);
        targetIsAccepts(test, subject, receiver);
        Randomness.setSeed(11L);
        VariableReference observed = otherWrapper(null).materializeCollectionQueryObservation(
                test, Inventory.class.getMethod("getItems"), receiver);
        assertNotNull(observed);
        assertEquals(lines(
                "ContainerSetupTest.Inventory containerSetupTest_Inventory0 = new ContainerSetupTest.Inventory();",
                "ContainerSetupTest.Subject containerSetupTest_Subject0 = new ContainerSetupTest.Subject();",
                "String string0 = \"\";",
                "containerSetupTest_Inventory0.add(string0);",
                "containerSetupTest_Inventory0.getItems();",
                "containerSetupTest_Subject0.accepts(containerSetupTest_Inventory0);"),
                code(test));
        int next = Randomness.nextInt();
        assertEquals(firstDrawAfterSeed(11L), next);
    }

    @Test
    public void collectionQueryObservationWithoutTarget() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference receiver = construct(test, Inventory.class);
        Randomness.setSeed(11L);
        otherWrapper(null).materializeCollectionQueryObservation(
                test, Inventory.class.getMethod("getItems"), receiver);
        assertEquals(lines(
                "ContainerSetupTest.Inventory containerSetupTest_Inventory0 = new ContainerSetupTest.Inventory();",
                "String string0 = \"\";",
                "containerSetupTest_Inventory0.add(string0);",
                "containerSetupTest_Inventory0.getItems();"),
                code(test));
    }

    private static Map<Integer, VariableReference> observed(VariableReference key) {
        Map<Integer, VariableReference> references = new HashMap<>();
        references.put(1, key);
        return references;
    }

    // Parameter containers

    @Test
    public void parameterMapReceivesAnEntryBeforeTheTarget() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference map = construct(test, concrete(LinkedHashMap.class, "stringMap"));
        VariableReference subject = constructSubject(test);
        targetIsAccepts(test, subject, map);
        parameterWith("entrySet").materializeContainer(test, map, false);
        assertEquals(lines(
                "LinkedHashMap<String, String> linkedHashMap0 = new LinkedHashMap<String, String>();",
                "ContainerSetupTest.Subject containerSetupTest_Subject0 = new ContainerSetupTest.Subject();",
                "String string0 = \"key\";",
                "String string1 = \"value\";",
                "linkedHashMap0.put(string0, string1);",
                "containerSetupTest_Subject0.accepts(linkedHashMap0);"),
                code(test));
    }

    @Test
    public void parameterMapCardinalityAndExistingEntry() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference map = construct(test, concrete(LinkedHashMap.class, "stringMap"));
        parameterWith("size").materializeContainer(test, map, false);
        parameterWith("size").materializeContainer(test, map, false);
        assertEquals(lines(
                "LinkedHashMap<String, String> linkedHashMap0 = new LinkedHashMap<String, String>();",
                "String string0 = \"key\";",
                "String string1 = \"value\";",
                "linkedHashMap0.put(string0, string1);"),
                code(test));
    }

    @Test
    public void parameterCollectionsReceiveAnElement() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference list = construct(test, concrete(ArrayList.class, "stringList"));
        VariableReference elements = construct(test, concrete(ArrayList.class, "elementList"));
        VariableReference names = construct(test, NameBag.class);
        VariableReference set = construct(test, java.util.HashSet.class);
        parameterWith("iterator").materializeContainer(test, list, false);
        parameterWith("iterator").materializeContainer(test, elements, false);
        parameterWith("next").materializeContainer(test, names, false);
        parameterWith("hasNext").materializeContainer(test, set, false);
        assertEquals(lines(
                "ArrayList<String> arrayList0 = new ArrayList<String>();",
                "ArrayList<ContainerSetupTest.Element> arrayList1 = new ArrayList<ContainerSetupTest.Element>();",
                "ContainerSetupTest.NameBag containerSetupTest_NameBag0 = new ContainerSetupTest.NameBag();",
                "HashSet hashSet0 = new HashSet<>();",
                "String string0 = \"value\";",
                "arrayList0.add(string0);",
                "ContainerSetupTest.Element nullRef0 = null;",
                "arrayList1.add((ContainerSetupTest.Element) null);",
                "String string1 = \"value\";",
                "((AbstractCollection) containerSetupTest_NameBag0).add((Object) string1);",
                "String string2 = \"value\";",
                "((HashSet)hashSet0).add((Object) string2);"),
                code(test));
    }

    @Test
    public void parameterContainerWithoutEvidenceIsUntouched() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference list = construct(test, ArrayList.class);
        VariableReference map = construct(test, LinkedHashMap.class);
        parameterWith().materializeContainer(test, list, false);
        parameterWith("size").materializeContainer(test, list, false);
        parameterWith().materializeContainer(test, map, false);
        assertEquals(2, test.size());
    }

    // Generated elements and membership setters

    @Test
    public void generatedElementsConsumeTheRecordedRandomness() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference receiver = construct(test, Inventory.class);
        Randomness.setSeed(5L);
        VariableCodeGenerationUtil.generateElements(Inventory.class, test, receiver);
        assertEquals(lines(
                "ContainerSetupTest.Inventory containerSetupTest_Inventory0 = new ContainerSetupTest.Inventory();",
                "String string0 = \"e`7/JK^)<Du4t@)s\";",
                "containerSetupTest_Inventory0.add(string0);",
                "String string1 = \"(dDRS_mx/hc&g8Z\";",
                "containerSetupTest_Inventory0.add(string1);"),
                code(test));
        assertEquals(1222716560, Randomness.nextInt());
    }

    @Test
    public void generatedElementsThroughAnInheritedAdd() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference receiver = construct(test, InheritingBag.class);
        Randomness.setSeed(5L);
        VariableCodeGenerationUtil.generateElements(InheritingBag.class, test, receiver);
        assertEquals(lines(
                "ContainerSetupTest.InheritingBag containerSetupTest_InheritingBag0 = new ContainerSetupTest.InheritingBag();",
                "String string0 = \"e`7/JK^)<Du4t@)s\";",
                "containerSetupTest_InheritingBag0.add(string0);",
                "String string1 = \"(dDRS_mx/hc&g8Z\";",
                "containerSetupTest_InheritingBag0.add(string1);"),
                code(test));
        assertEquals(1222716560, Randomness.nextInt());
    }

    @Test
    public void membershipSetterPassesTheTargetArgument() throws Exception {
        Method accepts = MembershipGroup.class.getMethod("accepts", MembershipGroup.Member.class);
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[] {Criterion.BRANCH};
        Properties.APPLY_OBJECT_RULE = true;
        Properties.TARGET_CLASS = MembershipGroup.class.getName();
        Properties.TARGET_METHOD = accepts.getName() + MethodUtil.getSignature(accepts);
        String classPath = System.getProperty("user.dir") + "/target/test-classes";
        org.evosuite.classpath.ClassPathHandler.getInstance()
                .addElementToTargetProjectClassPath(classPath);
        DependencyAnalysis.analyzeClass(MembershipGroup.class.getName(),
                Collections.singletonList(classPath));
        LoopCounter.getInstance().setActive(false);
        TargetContext.setActive(new TargetContext(
                MembershipGroup.class.getName(), Properties.TARGET_METHOD, null));

        TestCase test = new DefaultTestCase();
        VariableReference group = construct(test, MembershipGroup.class);
        VariableReference member = construct(test, MembershipGroup.Member.class);
        test.addStatement(new StringPrimitiveStatement(test, "between"));
        test.addStatement(new MethodStatement(test, new GenericMethod(accepts, MembershipGroup.class),
                group, Collections.singletonList(member)));
        Field members = MembershipGroup.class.getDeclaredField("members");
        Method add = MembershipGroup.class.getMethod("add", MembershipGroup.Member.class);

        assertNotNull(VariableCodeGenerationUtil.generateMembershipSetterWithTargetArgument(
                test, group, members, add));
        assertEquals(lines(
                "MembershipGroup membershipGroup0 = new MembershipGroup();",
                "MembershipGroup.Member membershipGroup_Member0 = new MembershipGroup.Member();",
                "membershipGroup0.add(membershipGroup_Member0);",
                "String string0 = \"between\";",
                "membershipGroup0.accepts(membershipGroup_Member0);"),
                code(test));
    }
}
