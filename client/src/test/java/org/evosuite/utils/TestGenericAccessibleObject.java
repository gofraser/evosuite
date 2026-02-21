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

package org.evosuite.utils;

import com.examples.with.different.packagename.generic.GuavaExample4;
import com.googlecode.gentyref.TypeToken;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.Inspector;
import org.evosuite.assertion.InspectorAssertion;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Gordon Fraser
 */
public class TestGenericAccessibleObject {

    @Test
    public void testGenericMethod() throws SecurityException, NoSuchMethodException,
            ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericMethod.class;
        Method targetMethod = targetClass.getMethod("coverMe",
                new Class<?>[]{Object.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);
        Assertions.assertFalse(genericMethod.getOwnerClass().hasTypeVariables());

        List<GenericClass<?>> parameters = genericMethod.getParameterClasses();
        Assertions.assertFalse(parameters.get(0).hasTypeVariables());
        Assertions.assertTrue(parameters.get(0).hasWildcardTypes());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiation();
        parameters = instantiatedMethod.getParameterClasses();
        Assertions.assertFalse(parameters.get(0).hasTypeVariables());
        Assertions.assertFalse(parameters.get(0).hasWildcardTypes());
    }

    @Test
    public void testGenericMethodWithBounds() throws SecurityException,
            NoSuchMethodException, ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericMethodWithBounds.class;
        Method targetMethod = targetClass.getMethod("is",
                new Class<?>[]{Comparable.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);
        Assertions.assertFalse(genericMethod.getOwnerClass().hasTypeVariables());

        List<GenericClass<?>> parameters = genericMethod.getParameterClasses();
        Assertions.assertFalse(parameters.get(0).hasTypeVariables());
        Assertions.assertTrue(parameters.get(0).hasWildcardTypes());
        Assertions.assertTrue(genericMethod.getGeneratedClass().hasWildcardTypes());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiation();
        parameters = instantiatedMethod.getParameterClasses();
        Assertions.assertFalse(parameters.get(0).hasTypeVariables());
        Assertions.assertFalse(parameters.get(0).hasWildcardTypes());
        Assertions.assertFalse(instantiatedMethod.getGeneratedClass().hasWildcardTypes());
    }

    @Test
    public void testGenericMethodAlternativeBounds() throws NoSuchMethodException,
            RuntimeException, ClassNotFoundException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericMethodAlternativeBounds.class;
        Method targetMethod = targetClass.getMethod("create",
                new Class<?>[]{Class.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);
        Assertions.assertFalse(genericMethod.getOwnerClass().hasTypeVariables());

        List<GenericClass<?>> parameters = genericMethod.getParameterClasses();
        Assertions.assertFalse(parameters.get(0).hasTypeVariables());
        Assertions.assertTrue(parameters.get(0).hasWildcardTypes());
        Assertions.assertTrue(genericMethod.getGeneratedClass().hasWildcardTypes());

        // Cannot instantiate because it requires inheritance tree to set up
        // TODO
        // GenericMethod instantiatedMethod = genericMethod.getGenericInstantiation();
        // parameters = instantiatedMethod.getParameterClasses();
        // Assert.assertFalse(parameters.get(0).hasTypeVariables());
        // Assert.assertFalse(parameters.get(0).hasWildcardTypes());
        // Assert.assertFalse(instantiatedMethod.getGeneratedClass().hasWildcardTypes());
    }

    @Test
    public void testGenericClassWithGenericMethodAndSubclass() throws SecurityException,
            NoSuchMethodException, ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericClassWithGenericMethodAndSubclass.class;
        Method targetMethod = targetClass.getMethod("wrap",
                new Class<?>[]{Object.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);
        Assertions.assertTrue(genericMethod.getOwnerClass().hasTypeVariables());
        System.out.println(genericMethod.toString());
        System.out.println(genericMethod.getOwnerClass().toString());
        System.out.println(genericMethod.getGeneratedClass().toString());

        List<GenericClass<?>> parameters = genericMethod.getParameterClasses();
        Assertions.assertFalse(parameters.get(0).hasTypeVariables());
        Assertions.assertTrue(parameters.get(0).hasWildcardTypes());
        Assertions.assertTrue(genericMethod.getGeneratedClass().hasWildcardTypes());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiation();
        parameters = instantiatedMethod.getParameterClasses();
        Assertions.assertFalse(parameters.get(0).hasTypeVariables());
        Assertions.assertFalse(parameters.get(0).hasWildcardTypes());
        Assertions.assertFalse(instantiatedMethod.getGeneratedClass().hasWildcardTypes());
    }

    @Test
    public void testGenericRawParameter() throws SecurityException, NoSuchMethodException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericParameters8.class;
        Method targetMethod = targetClass.getMethod("testMe",
                new Class<?>[]{List.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);
        System.out.println(genericMethod.toString());
        System.out.println(genericMethod.getOwnerClass().toString());
        System.out.println(genericMethod.getGeneratedClass().toString());
        Assertions.assertFalse(genericMethod.getOwnerClass().hasTypeVariables());

        List<GenericClass<?>> parameters = genericMethod.getParameterClasses();
        Assertions.assertTrue(parameters.get(0).hasTypeVariables());
        Assertions.assertFalse(parameters.get(0).hasWildcardTypes());
        Assertions.assertFalse(genericMethod.getGeneratedClass().hasWildcardTypes());

		/*
		GenericMethod instantiatedMethod = genericMethod.getGenericInstantiation();
		parameters = instantiatedMethod.getParameterClasses();
		System.out.println(instantiatedMethod.toString());
		System.out.println(instantiatedMethod.getOwnerClass().toString());
		System.out.println(instantiatedMethod.getGeneratedClass().toString());
		System.out.println(parameters.toString());
		Assert.assertFalse(parameters.get(0).hasTypeVariables());
		Assert.assertFalse(parameters.get(0).hasWildcardTypes());
		Assert.assertFalse(instantiatedMethod.getGeneratedClass().hasWildcardTypes());
		*/
    }

    @Test
    public void testGenericArrayWithWildcardParameter() throws SecurityException, NoSuchMethodException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericArrayWithGenericType.class;
        Method targetMethod = targetClass.getMethod("testMe",
                new Class<?>[]{List[].class, List.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);
        Assertions.assertFalse(genericMethod.getOwnerClass().hasTypeVariables());

        List<GenericClass<?>> parameters = genericMethod.getParameterClasses();
        Assertions.assertTrue(parameters.get(0).isArray());
        Assertions.assertTrue(parameters.get(0).getComponentClass().hasWildcardTypes());
        Assertions.assertTrue(parameters.get(1).hasWildcardTypes());
    }

    @Test
    public void testGenericArrayWithTypeVariableParameter() throws SecurityException,
            NoSuchMethodException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericArrayWithGenericTypeVariable.class;
        Method targetMethod = targetClass.getMethod("testMe",
                new Class<?>[]{List[].class, List.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);
        Assertions.assertTrue(genericMethod.getOwnerClass().hasTypeVariables());

        List<GenericClass<?>> parameters = genericMethod.getParameterClasses();
        Assertions.assertTrue(parameters.get(0).isArray());
        Assertions.assertTrue(parameters.get(0).getComponentClass().hasTypeVariables());
        Assertions.assertTrue(parameters.get(1).hasTypeVariables());

        GenericClass<?> ownerInstantiation = GenericClassFactory.get(
                new TypeToken<com.examples.with.different.packagename.generic.GenericArrayWithGenericTypeVariable<String>>() {
                }.getType());
        GenericMethod instantiatedMethod = genericMethod.copyWithNewOwner(ownerInstantiation);
        List<GenericClass<?>> instantiatedParameters = instantiatedMethod.getParameterClasses();
        Assertions.assertTrue(instantiatedParameters.get(0).isArray());
        Assertions.assertFalse(instantiatedParameters.get(0).getComponentClass().hasTypeVariables());
        Assertions.assertEquals(String.class,
                instantiatedParameters.get(0).getComponentClass().getParameterTypes().get(0));
        Assertions.assertEquals(String.class, instantiatedParameters.get(1).getParameterTypes().get(0));
    }

    @Test
    public void testGenericParameterWithGenericBoundInstantiation() throws SecurityException,
            NoSuchMethodException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericParameterWithGenericBound.class;
        Method targetMethod = targetClass.getMethod("testMe",
                new Class<?>[]{List.class, Number.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);
        Assertions.assertTrue(genericMethod.getOwnerClass().hasTypeVariables());

        List<GenericClass<?>> parameters = genericMethod.getParameterClasses();
        Assertions.assertTrue(parameters.get(0).isTypeVariable());
        Assertions.assertTrue(parameters.get(1).isTypeVariable());

        GenericClass<?> ownerInstantiation = GenericClassFactory.get(
                new TypeToken<com.examples.with.different.packagename.generic.GenericParameterWithGenericBound<List<String>, Integer>>() {
                }.getType());
        GenericMethod instantiatedMethod = genericMethod.copyWithNewOwner(ownerInstantiation);
        List<GenericClass<?>> instantiatedParameters = instantiatedMethod.getParameterClasses();
        Assertions.assertEquals(List.class, instantiatedParameters.get(0).getRawClass());
        Assertions.assertEquals(String.class, instantiatedParameters.get(0).getParameterTypes().get(0));
        Assertions.assertEquals(Integer.class, instantiatedParameters.get(1).getRawClass());
    }

    @Test
    public void testNestedGenericReturnInstantiationFromStaticMethod()
            throws SecurityException, NoSuchMethodException, ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericCollectionUtil.class;
        Method targetMethod = targetClass.getMethod("intersection",
                new Class<?>[]{List.class, List.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);

        GenericClass<?> generatedType = GenericClassFactory.get(
                new TypeToken<List<Map<String, Integer>>>() {
                }.getType());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiationFromReturnValue(generatedType);
        Assertions.assertEquals(generatedType, instantiatedMethod.getGeneratedClass());
        List<GenericClass<?>> parameters = instantiatedMethod.getParameterClasses();
        Assertions.assertEquals(List.class, parameters.get(0).getRawClass());
        Assertions.assertTrue(parameters.get(0).hasWildcardTypes());
        Assertions.assertEquals(List.class, parameters.get(1).getRawClass());
        Assertions.assertTrue(parameters.get(1).hasWildcardTypes());
    }

    @Test
    public void testNestedGenericOwnerInstantiation() throws SecurityException,
            NoSuchMethodException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericTripleParameter.class;
        Method targetMethod = targetClass.getMethod("foo",
                new Class<?>[]{Object.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);

        GenericClass<?> ownerInstantiation = GenericClassFactory.get(
                new TypeToken<com.examples.with.different.packagename.generic.GenericTripleParameter<List<String>, Map<Integer, String>, Set<Long>>>() {
                }.getType());
        GenericMethod instantiatedMethod = genericMethod.copyWithNewOwner(ownerInstantiation);

        GenericClass<?> generatedClass = instantiatedMethod.getGeneratedClass();
        Assertions.assertEquals(Map.class, generatedClass.getRawClass());
        List<Type> returnArgs = generatedClass.getParameterTypes();
        assertParameterizedType(returnArgs.get(0), List.class, String.class);
        assertParameterizedType(returnArgs.get(1), Map.class, Integer.class, String.class);

        List<GenericClass<?>> parameters = instantiatedMethod.getParameterClasses();
        Assertions.assertEquals(Set.class, parameters.get(0).getRawClass());
        Assertions.assertEquals(Long.class, parameters.get(0).getParameterTypes().get(0));
    }

    @Test
    public void testNestedWildcardBoundsInReturnInstantiation()
            throws SecurityException, NoSuchMethodException, ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericCollectionUtil.class;
        Method targetMethod = targetClass.getMethod("intersection",
                new Class<?>[]{List.class, List.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);

        GenericClass<?> generatedType = GenericClassFactory.get(
                new TypeToken<List<? extends Map<String, Integer>>>() {
                }.getType());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiationFromReturnValue(generatedType);
        GenericClass<?> generatedClass = instantiatedMethod.getGeneratedClass();
        Assertions.assertEquals(List.class, generatedClass.getRawClass());
        Type innerType = generatedClass.getParameterTypes().get(0);
        GenericClass<?> innerClass = GenericClassFactory.get(innerType);
        Assertions.assertFalse(innerClass.hasWildcardOrTypeVariables());
    }

    @Test
    public void testNestedArraysInReturnInstantiation()
            throws SecurityException, NoSuchMethodException, ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericCollectionUtil.class;
        Method targetMethod = targetClass.getMethod("intersection",
                new Class<?>[]{List.class, List.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);

        GenericClass<?> generatedType = GenericClassFactory.get(
                new TypeToken<List<Map<String, Integer>>[]>() {
                }.getType());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiationFromReturnValue(generatedType);
        GenericClass<?> generatedClass = instantiatedMethod.getGeneratedClass();
        if (generatedClass.isArray()) {
            GenericClass<?> componentClass = generatedClass.getComponentClass();
            Assertions.assertEquals(List.class, componentClass.getRawClass());
            GenericClass<?> componentParam = GenericClassFactory.get(componentClass.getParameterTypes().get(0));
            Assertions.assertFalse(componentParam.hasWildcardOrTypeVariables());
        } else {
            Assertions.assertEquals(List.class, generatedClass.getRawClass());
            GenericClass<?> paramClass = GenericClassFactory.get(generatedClass.getParameterTypes().get(0));
            Assertions.assertFalse(paramClass.hasWildcardOrTypeVariables());
        }
    }

    @Test
    public void testLinkedList() throws SecurityException, NoSuchMethodException,
            ConstructionFailedException {
        Class<?> targetClass = java.util.LinkedList.class;
        Method targetMethod = targetClass.getMethod("get", new Class<?>[]{int.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);
        System.out.println(genericMethod.getGeneratedClass().toString());
        Assertions.assertTrue(genericMethod.getGeneratedClass().hasWildcardOrTypeVariables());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiation();
        System.out.println(instantiatedMethod.getGeneratedClass().toString());
        Assertions.assertFalse(instantiatedMethod.getGeneratedClass().hasWildcardOrTypeVariables());
    }

    @Test
    public void testGuavaExample3() throws SecurityException, NoSuchMethodException,
            ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GuavaExample3.class;

        GenericClass<?> genericInstantiation = GenericClassFactory.get(
                new TypeToken<com.examples.with.different.packagename.generic.GuavaExample3<String, String, Object>>() {
                }.getType());

        Method targetMethod = targetClass.getMethod("create",
                new Class<?>[]{com.examples.with.different.packagename.generic.GuavaExample3.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);
        System.out.println(genericMethod.getGeneratedClass().toString());
        Assertions.assertTrue(genericMethod.getGeneratedClass().hasWildcardOrTypeVariables());

        System.out.println("------------------");
        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiationFromReturnValue(genericInstantiation);
        System.out.println(instantiatedMethod.getGeneratedClass().toString());
        Assertions.assertFalse(instantiatedMethod.getGeneratedClass().hasWildcardOrTypeVariables());
        Assertions.assertEquals(genericInstantiation, instantiatedMethod.getGeneratedClass());
    }

    @Test
    public void testGenericMethodFromReturnValue() throws SecurityException,
            NoSuchMethodException, ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericMethodWithBounds.class;
        Method targetMethod = targetClass.getMethod("is",
                new Class<?>[]{Comparable.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);

        GenericClass<?> generatedType = GenericClassFactory.get(
                new TypeToken<java.util.List<Integer>>() {
                }.getType());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiationFromReturnValue(generatedType);
        Assertions.assertEquals(instantiatedMethod.getGeneratedClass(), generatedType);
    }

    @Test
    public void testGenericMethodFromReturnValueWithSubclass() throws SecurityException,
            NoSuchMethodException, ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericClassWithGenericMethodAndSubclass.class;
        Method targetMethod = targetClass.getMethod("wrap",
                new Class<?>[]{Object.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);

        GenericClass<?> generatedType = GenericClassFactory.get(
                new TypeToken<com.examples.with.different.packagename.generic.GenericClassWithGenericMethodAndSubclass.Foo<String>>() {
                }.getType());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiationFromReturnValue(generatedType);
        Assertions.assertEquals(instantiatedMethod.getGeneratedClass().getParameterTypes().get(0),
                String.class);
    }

    @Test
    public void testGenericMethodFromReturnValueTypeVariable() throws SecurityException,
            NoSuchMethodException, ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericMethodReturningTypeVariable.class;
        Method targetMethod = targetClass.getMethod("get",
                new Class<?>[]{Object.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);

        GenericClass<?> generatedType1 = GenericClassFactory.get(Integer.class);
        GenericClass<?> generatedType2 = GenericClassFactory.get(String.class);

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiationFromReturnValue(generatedType2);
        Assertions.assertEquals(instantiatedMethod.getGeneratedClass().getRawClass(),
                String.class);

        instantiatedMethod = genericMethod.getGenericInstantiationFromReturnValue(generatedType1);
        Assertions.assertEquals(instantiatedMethod.getGeneratedClass().getRawClass(),
                Integer.class);
    }

    @Test
    public void testGenericMethodFromReturnValueTypeVariable2() throws SecurityException,
            NoSuchMethodException, ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GuavaExample4.class;
        Method targetMethod = targetClass.getMethod("create", new Class<?>[]{});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);

        GenericClass<?> iterableIntegerClass = GenericClassFactory.get(
                new TypeToken<com.examples.with.different.packagename.generic.GuavaExample4<java.lang.Iterable<Integer>>>() {
                }.getType());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiationFromReturnValue(iterableIntegerClass);
        System.out.println(instantiatedMethod.getGeneratedClass().toString());
        Assertions.assertEquals(instantiatedMethod.getGeneratedClass().getRawClass(),
                GuavaExample4.class);
    }

    @Test
    public void testGenericMethodAbstractType() throws SecurityException,
            NoSuchMethodException, ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.ConcreteGenericClass.class;
        Method targetMethod = targetClass.getMethod("create",
                new Class<?>[]{int.class});
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass);

        Assertions.assertEquals(genericMethod.getGeneratedClass().getRawClass(),
                com.examples.with.different.packagename.generic.ConcreteGenericClass.class);

        GenericClass<?> iterableIntegerClass = GenericClassFactory.get(
                new TypeToken<com.examples.with.different.packagename.generic.AbstractGenericClass<java.lang.Integer>>() {
                }.getType());

        GenericMethod instantiatedMethod = genericMethod.getGenericInstantiationFromReturnValue(iterableIntegerClass);
        System.out.println(instantiatedMethod.getGeneratedClass().toString());
        Assertions.assertEquals(instantiatedMethod.getGeneratedClass().getRawClass(),
                com.examples.with.different.packagename.generic.ConcreteGenericClass.class);

        instantiatedMethod = genericMethod.copyWithOwnerFromReturnType(iterableIntegerClass);
        System.out.println(instantiatedMethod.getGeneratedClass().toString());
        Assertions.assertEquals(instantiatedMethod.getGeneratedClass().getRawClass(),
                com.examples.with.different.packagename.generic.ConcreteGenericClass.class);

        instantiatedMethod = genericMethod.getGenericInstantiation(iterableIntegerClass);
        System.out.println(instantiatedMethod.getGeneratedClass().toString());
        Assertions.assertEquals(instantiatedMethod.getGeneratedClass().getRawClass(),
                com.examples.with.different.packagename.generic.ConcreteGenericClass.class);

        instantiatedMethod = genericMethod.copyWithNewOwner(iterableIntegerClass);
        System.out.println(instantiatedMethod.getGeneratedClass().toString());
        Assertions.assertEquals(instantiatedMethod.getGeneratedClass().getRawClass(),
                com.examples.with.different.packagename.generic.ConcreteGenericClass.class);

    }

    @Test
    public void testClassLoaderChange() throws NoSuchMethodException, SecurityException,
            ConstructionFailedException {
        Class<?> targetClass = com.examples.with.different.packagename.generic.GenericClassTwoParameters.class;
        Method creatorMethod = targetClass.getMethod("create", new Class<?>[]{});
        Method targetMethod = targetClass.getMethod("get",
                new Class<?>[]{Object.class});
        Method inspectorMethod = targetClass.getMethod("testMe", new Class<?>[]{});
        Constructor<?> intConst = Integer.class.getConstructor(new Class<?>[]{int.class});

        GenericClass<?> listOfInteger = GenericClassFactory.get(
                new TypeToken<com.examples.with.different.packagename.generic.GenericClassTwoParameters<Integer, Integer>>() {
                }.getType());

        GenericMethod genericCreatorMethod = new GenericMethod(creatorMethod, targetClass).getGenericInstantiationFromReturnValue(listOfInteger);
        System.out.println(genericCreatorMethod.getGeneratedClass().toString());
        GenericMethod genericMethod = new GenericMethod(targetMethod, targetClass).copyWithNewOwner(genericCreatorMethod.getGeneratedClass());
        System.out.println(genericMethod.getGeneratedClass().toString());

        DefaultTestCase test = new DefaultTestCase();
        MethodStatement ms1 = new MethodStatement(test, genericCreatorMethod,
                null, new ArrayList<>());
        test.addStatement(ms1);

        IntPrimitiveStatement ps1 = (IntPrimitiveStatement) PrimitiveStatement.getPrimitiveStatement(test,
                int.class);
        test.addStatement(ps1);

        GenericConstructor intConstructor = new GenericConstructor(intConst,
                Integer.class);
        List<VariableReference> constParam = new ArrayList<>();
        constParam.add(ps1.getReturnValue());
        ConstructorStatement cs1 = new ConstructorStatement(test, intConstructor,
                constParam);
        //test.addStatement(cs1);

        List<VariableReference> callParam = new ArrayList<>();
        callParam.add(ps1.getReturnValue());

        MethodStatement ms2 = new MethodStatement(test, genericMethod,
                ms1.getReturnValue(), callParam);
        test.addStatement(ms2);

        Inspector inspector = new Inspector(targetClass, inspectorMethod);
        Assertion assertion = new InspectorAssertion(inspector, ms2,
                ms1.getReturnValue(), 0);
        ms2.addAssertion(assertion);

        String code = test.toCode();

        ClassLoader loader = new InstrumentingClassLoader();
        Properties.TARGET_CLASS = targetClass.getCanonicalName();
        Properties.CRITERION = new Criterion[1];
        Properties.CRITERION[0] = Criterion.MUTATION;

        DefaultTestCase testCopy = test.clone();
        testCopy.changeClassLoader(loader);
        String code2 = testCopy.toCode();

        Assertions.assertEquals(code, code2);
        Assertions.assertEquals(code, test.toCode());

        testCopy.removeAssertion(assertion);
        Assertions.assertEquals(code, test.toCode());

        //test.removeAssertion(assertion);
        test.removeAssertions();
        System.out.println(test.toCode());
    }

    private static void assertParameterizedType(Type type, Class<?> rawType, Class<?>... arguments) {
        Assertions.assertTrue(type instanceof ParameterizedType);
        ParameterizedType parameterizedType = (ParameterizedType) type;
        Assertions.assertEquals(rawType, parameterizedType.getRawType());
        Type[] actualArguments = parameterizedType.getActualTypeArguments();
        Assertions.assertEquals(arguments.length, actualArguments.length);
        for (int i = 0; i < arguments.length; i++) {
            Assertions.assertEquals(arguments[i], actualArguments[i]);
        }
    }

}
