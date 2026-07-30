package org.evosuite.testcase.factories;

import com.examples.with.different.packagename.objectconstruction.wholeclass.FactoryOnlyWholeClassExample;
import com.examples.with.different.packagename.objectconstruction.wholeclass.ConditionalFactoryWholeClassExample;
import com.examples.with.different.packagename.objectconstruction.wholeclass.FailingReceiver;
import com.examples.with.different.packagename.objectconstruction.wholeclass.ReceiverFallbackBase;
import com.examples.with.different.packagename.objectconstruction.wholeclass.WorkingReceiver;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomLengthTestFactoryEntryTest {

    public interface RequiredCollaborator {
        boolean ready();
    }

    public static class CollaboratorReceiver {
        private final RequiredCollaborator collaborator;

        public CollaboratorReceiver() {
            this(new RequiredCollaborator() {
                @Override
                public boolean ready() {
                    return false;
                }
            });
        }

        public CollaboratorReceiver(RequiredCollaborator collaborator) {
            this.collaborator = collaborator;
        }

        public boolean target() {
            return collaborator.ready();
        }
    }

    public static class ArrayBackedReceiver {
        private final String[] values;
        private final RequiredCollaborator irrelevant;

        public ArrayBackedReceiver(String[] values, RequiredCollaborator irrelevant) {
            this.values = values;
            this.irrelevant = irrelevant;
        }

        public boolean target() {
            return values[0] != null;
        }
    }

    static class GenericFactoryBase {
    }

    static final class GenericFactorySubtype extends GenericFactoryBase {
    }

    static class UnboundedReceiver<T> {
    }

    static class MixedReceiver<T, U extends Number> {
    }

    public static class GenericEntryReceiver<T> {
        public GenericEntryReceiver() {
        }

        public void entry(T value) {
        }
    }

    static <T extends GenericFactoryBase> T conditionalFactory(Iterable<GenericFactoryBase> values) {
        return null;
    }

    public static boolean scalarEntry(String text, int number) {
        return text.length() == number;
    }

    @AfterEach
    void tearDown() {
        Properties.TARGET_CLASS = "";
        Properties.TARGET_METHOD = "";
        TestGenerationContext.getInstance().resetContext();
    }

    @Test
    void attachesEntryToReceiverAlreadyProducedByOrdinaryGeneration() throws Exception {
        Properties.TARGET_CLASS = FactoryOnlyWholeClassExample.class.getName();
        Properties.TARGET_METHOD = "";
        String classPath = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(classPath);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, Collections.singletonList(classPath));
        Class<?> receiverClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
                .loadClass(Properties.TARGET_CLASS);

        TestCase test = new DefaultTestCase();
        Method factoryMethod = receiverClass.getDeclaredMethod("create", int.class);
        TestFactory.getInstance().addMethod(test,
                new GenericMethod(factoryMethod, receiverClass), test.size(), 0);

        Method entryMethod = receiverClass.getDeclaredMethod("entry", int.class);
        String entrySignature = entryMethod.getName() + MethodUtil.getSignature(entryMethod);
        TargetContext context = new TargetContext(
                receiverClass.getName(), entrySignature, null);

        assertTrue(new ObjectRuleEntryBuilder().insertEntryMethodCallOnExistingReceiver(test, context));
        assertNotNull(test.findMethodCallStatement(entrySignature));
    }

    @Test
    void rejectsConditionalGenericFactoryAsDirectSubtypeConstructor() throws Exception {
        Method factory = RandomLengthTestFactoryEntryTest.class.getDeclaredMethod(
                "conditionalFactory", Iterable.class);
        GenericMethod genericFactory = new GenericMethod(factory, RandomLengthTestFactoryEntryTest.class);

        assertTrue(ObjectRuleEntryBuilder.isUnsafeConditionalGenericFactory(
                genericFactory, GenericFactorySubtype.class));
        assertFalse(ObjectRuleEntryBuilder.isUnsafeConditionalGenericFactory(
                genericFactory, GenericFactoryBase.class));
    }

    @Test
    void constructsAndValidatesConditionalFactoryReceiverFromConcreteElements() throws Exception {
        Properties.TARGET_CLASS = ConditionalFactoryWholeClassExample.class.getName();
        Properties.TARGET_METHOD = "";
        String classPath = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(classPath);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, Collections.singletonList(classPath));
        Class<?> receiverClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
                .loadClass(Properties.TARGET_CLASS);

        TestCase test = new DefaultTestCase();
        VariableReference receiver =
                new ObjectRuleEntryBuilder().constructEntryReceiver(test, receiverClass);

        assertNotNull(receiver);
        assertTrue(ObjectRuleEntryBuilder.producesRuntimeReceiver(
                test, receiver, receiverClass));
        ExecutionResult result = TestCaseExecutor.getInstance().execute(test);
        assertTrue(result.noThrownExceptions());
    }

    @Test
    void rejectsExistingFactoryReceiverWhoseRuntimeValueIsNull() throws Exception {
        Properties.TARGET_CLASS = FactoryOnlyWholeClassExample.class.getName();
        Properties.TARGET_METHOD = "";
        String classPath = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(classPath);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, Collections.singletonList(classPath));
        Class<?> receiverClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
                .loadClass(Properties.TARGET_CLASS);

        TestCase test = new DefaultTestCase();
        Method nullFactory = receiverClass.getDeclaredMethod("createNull");
        TestFactory.getInstance().addMethod(test,
                new GenericMethod(nullFactory, receiverClass), test.size(), 0);

        Method entryMethod = receiverClass.getDeclaredMethod("entry", int.class);
        TargetContext context = new TargetContext(receiverClass.getName(),
                entryMethod.getName() + MethodUtil.getSignature(entryMethod), null);

        assertFalse(new ObjectRuleEntryBuilder()
                .insertEntryMethodCallOnExistingReceiver(test, context));
    }

    @Test
    void rejectsAndCachesExistingReceiverWhoseConstructorFailsAtRuntime() throws Exception {
        Properties.TARGET_CLASS = ReceiverFallbackBase.class.getName();
        Properties.TARGET_METHOD = "";
        String classPath = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(classPath);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS,
                Collections.singletonList(classPath));
        ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        Class<?> receiverClass = loader.loadClass(ReceiverFallbackBase.class.getName());
        Class<?> failingClass = loader.loadClass(FailingReceiver.class.getName());

        TestCase test = new DefaultTestCase();
        VariableReference dependency = test.addStatement(
                new ArrayStatement(test, String[].class, 1));
        java.lang.reflect.Constructor<?> constructor =
                failingClass.getConstructor(String[].class);
        test.addStatement(new ConstructorStatement(test,
                new GenericConstructor(constructor, failingClass),
                Collections.singletonList(dependency)));

        Method entry = receiverClass.getMethod("entry");
        TargetContext context = new TargetContext(receiverClass.getName(),
                entry.getName() + MethodUtil.getSignature(entry), null);
        ObjectRuleEntryBuilder builder = new ObjectRuleEntryBuilder();

        assertFalse(builder.insertEntryMethodCallOnExistingReceiver(test, context));
        assertTrue(builder.isConcreteReceiverTypeNegativelyCached(failingClass));
        assertEquals(2, test.size(), "validation must not append the entry call");
    }

    @Test
    void skipsNullNormalFactoryWhenConstructingEntryReceiver() throws Exception {
        Properties.TARGET_CLASS = FactoryOnlyWholeClassExample.class.getName();
        Properties.TARGET_METHOD = "";
        String classPath = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(classPath);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, Collections.singletonList(classPath));
        Class<?> receiverClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
                .loadClass(Properties.TARGET_CLASS);

        TestCase test = new DefaultTestCase();
        VariableReference receiver =
                new ObjectRuleEntryBuilder().constructEntryReceiver(test, receiverClass);

        assertNotNull(receiver);
        assertTrue(ObjectRuleEntryBuilder.producesRuntimeReceiver(
                test, receiver, receiverClass));
    }

    @Test
    void skipsAndCachesConcreteReceiverWhoseConstructorFailsAtRuntime() throws Exception {
        Properties.TARGET_CLASS = ReceiverFallbackBase.class.getName();
        Properties.TARGET_METHOD = "";
        String classPath = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(classPath);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS,
                Collections.singletonList(classPath));
        ClassLoader sutLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        Class<?> receiverClass = sutLoader.loadClass(ReceiverFallbackBase.class.getName());
        Class<?> failingClass = sutLoader.loadClass(FailingReceiver.class.getName());

        ObjectRuleEntryBuilder builder = new ObjectRuleEntryBuilder();
        TestCase test = new DefaultTestCase();
        VariableReference receiver = builder.constructEntryReceiver(
                test, receiverClass, Collections.singleton(
                        org.objectweb.asm.Type.getDescriptor(String[].class)));

        assertNotNull(receiver);
        assertEquals(WorkingReceiver.class.getName(),
                ((ConstructorStatement) test.getStatement(receiver.getStPosition()))
                        .getConstructor().getDeclaringClass().getName());
        assertTrue(builder.isConcreteReceiverTypeNegativelyCached(failingClass),
                "the failing subtype should not be retried later in the run");
        assertTrue(ObjectRuleEntryBuilder.producesRuntimeReceiver(
                test, receiver, receiverClass));
    }

    @Test
    void requiredFieldTypeSelectsControllableConstructor() throws Exception {
        boolean oldMockSetup = Properties.OBJECT_RULE_MOCK_SETUP;
        try {
            Properties.OBJECT_RULE_MOCK_SETUP = true;
            Properties.TARGET_CLASS = CollaboratorReceiver.class.getName();
            String classPath = System.getProperty("user.dir") + "/target/test-classes";
            ClassPathHandler.getInstance().addElementToTargetProjectClassPath(classPath);
            DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS,
                    Collections.singletonList(classPath));
            Class<?> receiverClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
                    .loadClass(Properties.TARGET_CLASS);

            Set<String> required = new LinkedHashSet<>();
            required.add(org.objectweb.asm.Type.getDescriptor(
                    TestGenerationContext.getInstance().getClassLoaderForSUT()
                            .loadClass(RequiredCollaborator.class.getName())));
            TestCase test = new DefaultTestCase();
            Class<?> collaboratorClass = TestGenerationContext.getInstance()
                    .getClassLoaderForSUT().loadClass(RequiredCollaborator.class.getName());
            java.lang.reflect.Constructor<?> reflected =
                    receiverClass.getConstructor(collaboratorClass);
            VariableReference receiver = new ObjectRuleEntryBuilder()
                    .constructReceiverWithRequiredCollaborators(test,
                            new GenericConstructor(reflected, receiverClass), required);

            assertNotNull(receiver);
            assertTrue(test.getStatement(receiver.getStPosition()) instanceof ConstructorStatement);
            ConstructorStatement constructor =
                    (ConstructorStatement) test.getStatement(receiver.getStPosition());
            assertTrue(constructor.getParameterReferences().size() == 1);
            VariableReference collaborator = constructor.getParameterReferences().get(0);
            assertTrue(test.getStatement(collaborator.getStPosition())
                    instanceof FunctionalMockStatement);
        } finally {
            Properties.OBJECT_RULE_MOCK_SETUP = oldMockSetup;
        }
    }

    @Test
    void requiredArrayUsesDirectStorageAndOmitsIrrelevantCollaborator() throws Exception {
        TestCase test = new DefaultTestCase();
        java.lang.reflect.Constructor<?> reflected = ArrayBackedReceiver.class.getConstructor(
                String[].class, RequiredCollaborator.class);
        Set<String> required = Collections.singleton(
                org.objectweb.asm.Type.getDescriptor(String[].class));

        VariableReference receiver = new ObjectRuleEntryBuilder()
                .constructReceiverWithRequiredCollaborators(test,
                        new GenericConstructor(reflected, ArrayBackedReceiver.class), required);

        ConstructorStatement constructor =
                (ConstructorStatement) test.getStatement(receiver.getStPosition());
        VariableReference array = constructor.getParameterReferences().get(0);
        VariableReference collaborator = constructor.getParameterReferences().get(1);
        assertTrue(test.getStatement(array.getStPosition()) instanceof ArrayStatement);
        assertEquals(1, ((ArrayStatement) test.getStatement(array.getStPosition())).size());
        assertTrue(test.getStatement(collaborator.getStPosition()) instanceof NullStatement);
        assertTrue(ObjectRuleEntryBuilder.producesRuntimeReceiver(
                test, receiver, ArrayBackedReceiver.class));
    }

    @Test
    void stableEntryUsesDirectPrimitiveArguments() throws Exception {
        TestCase test = new DefaultTestCase();
        Method reflected = RandomLengthTestFactoryEntryTest.class.getMethod(
                "scalarEntry", String.class, int.class);
        GenericMethod method = new GenericMethod(
                reflected, RandomLengthTestFactoryEntryTest.class);

        new ObjectRuleEntryBuilder().insertStableEntryInvocation(
                test, method, null, reflected.getGenericParameterTypes());

        MethodStatement call = (MethodStatement) test.getStatement(test.size() - 1);
        VariableReference text = call.getParameterReferences().get(0);
        VariableReference number = call.getParameterReferences().get(1);
        assertTrue(test.getStatement(text.getStPosition())
                instanceof StringPrimitiveStatement);
        assertTrue(test.getStatement(number.getStPosition())
                instanceof PrimitiveStatement);
        assertEquals(1, ((PrimitiveStatement<?>) test.getStatement(
                number.getStPosition())).getValue());
        for (int i = 0; i < call.getPosition(); i++) {
            assertFalse(test.getStatement(i) instanceof MethodStatement,
                    "scalar construction must not recursively call a generator method");
        }
    }

    @Test
    void synthesisReceiverUsesStringOnlyForUnconstrainedTypeVariables() {
        Type unbounded = ObjectRuleEntryBuilder.stableSynthesisReceiverType(
                UnboundedReceiver.class);
        assertTrue(unbounded instanceof ParameterizedType);
        assertEquals(String.class,
                ((ParameterizedType) unbounded).getActualTypeArguments()[0]);

        Type mixed = ObjectRuleEntryBuilder.stableSynthesisReceiverType(MixedReceiver.class);
        assertTrue(mixed instanceof ParameterizedType);
        Type[] arguments = ((ParameterizedType) mixed).getActualTypeArguments();
        assertEquals(String.class, arguments[0]);
        assertTrue(arguments[1] instanceof TypeVariable,
                "bounded variables must remain available to CastClassManager");
    }

    @Test
    void comparisonHintSelectsRequestedSideWithoutExtremeIntegers() {
        assertEquals(257, ObjectRuleTemplateSynthesizer.integerValueForComparison(
                Opcodes.IF_ICMPLE, 256, false));
        assertEquals(256, ObjectRuleTemplateSynthesizer.integerValueForComparison(
                Opcodes.IF_ICMPLE, 256, true));
        assertEquals(-1, ObjectRuleTemplateSynthesizer.integerValueForComparison(
                Opcodes.IFLT, 0, true));
        assertEquals(0, ObjectRuleTemplateSynthesizer.integerValueForComparison(
                Opcodes.IFLT, 0, false));
    }

    @Test
    void stableEntryUsesReceiverTypeBindingForGenericParameters() throws Exception {
        Type exactType = new ParameterizedTypeImpl(
                GenericEntryReceiver.class, new Type[]{String.class},
                RandomLengthTestFactoryEntryTest.class);
        TestCase test = new DefaultTestCase();
        VariableReference receiver = TestFactory.getInstance().addConstructor(
                test,
                new GenericConstructor(GenericEntryReceiver.class.getConstructor(), exactType),
                exactType, test.size(), org.evosuite.testcase.GenerationContext.fromDepth(0));
        Method reflected = GenericEntryReceiver.class.getMethod("entry", Object.class);
        GenericMethod boundMethod = new GenericMethod(
                reflected, GenericEntryReceiver.class)
                .copyWithNewOwner(receiver.getGenericClass());

        new ObjectRuleEntryBuilder().insertStableEntryInvocation(
                test, boundMethod, receiver, boundMethod.getParameterTypes());

        MethodStatement call = (MethodStatement) test.getStatement(test.size() - 1);
        VariableReference argument = call.getParameterReferences().get(0);
        assertTrue(test.getStatement(argument.getStPosition()) instanceof StringPrimitiveStatement);
    }
}
