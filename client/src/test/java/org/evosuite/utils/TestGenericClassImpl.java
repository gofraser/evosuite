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

import com.examples.with.different.packagename.generic.AbstractGuavaExample;
import com.examples.with.different.packagename.generic.GuavaExample5;
import com.googlecode.gentyref.GenericTypeReflector;
import com.googlecode.gentyref.TypeToken;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.WildcardTypeImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestGenericClassImpl {

    @Test
    public void testWildcardClassloader() {
        GenericClass<?> clazz = GenericClassFactory.get(Class.class).getWithWildcardTypes();
        assertEquals("java.lang.Class<?>", clazz.getTypeName());
        clazz.changeClassLoader(TestGenericClassImpl.class.getClassLoader());
        assertEquals("java.lang.Class<?>", clazz.getTypeName());
    }

    @Test
    public void testAssignablePrimitives() {
        GenericClass<?> clazz1 = GenericClassFactory.get(int.class);
        GenericClass<?> clazz2 = GenericClassFactory.get(int.class);
        Assertions.assertTrue(clazz1.isAssignableTo(clazz2));
        Assertions.assertTrue(clazz1.isAssignableFrom(clazz2));
    }

    @Test
    public void testAssignableObject() {
        GenericClass<?> clazz1 = GenericClassFactory.get(Object.class);
        GenericClass<?> clazz2 = GenericClassFactory.get(Object.class);
        Assertions.assertTrue(clazz1.isAssignableTo(clazz2));
    }

    @Test
    public void testAssignableIntegerObject() {
        GenericClass<?> clazz1 = GenericClassFactory.get(Integer.class);
        GenericClass<?> clazz2 = GenericClassFactory.get(Object.class);
        Assertions.assertTrue(clazz1.isAssignableTo(clazz2));
        Assertions.assertFalse(clazz1.isAssignableFrom(clazz2));
    }

    @Test
    public void testAssignableIntegerNumber() {
        GenericClass<?> clazz1 = GenericClassFactory.get(Integer.class);
        GenericClass<?> clazz2 = GenericClassFactory.get(Number.class);
        Assertions.assertTrue(clazz1.isAssignableTo(clazz2));
        Assertions.assertFalse(clazz1.isAssignableFrom(clazz2));
    }

    @Test
    public void testAssignableIntInteger() {
        GenericClass<?> clazz1 = GenericClassFactory.get(Integer.class);
        GenericClass<?> clazz2 = GenericClassFactory.get(int.class);
        Assertions.assertTrue(clazz1.isAssignableTo(clazz2));
        Assertions.assertTrue(clazz1.isAssignableFrom(clazz2));
    }

    @Test
    public void testAssignableClass() {
        GenericClass<?> clazzTypeVar = GenericClassFactory.get(Class.class);
        GenericClass<?> clazzWildcard = clazzTypeVar.getWithWildcardTypes();

        ParameterizedType type = new ParameterizedTypeImpl(Class.class,
                new Type[]{Integer.class}, null);
        GenericClass<?> clazzConcrete = GenericClassFactory.get(type);

        Assertions.assertFalse(clazzWildcard.isAssignableTo(clazzConcrete));
        Assertions.assertFalse(clazzWildcard.isAssignableTo(clazzTypeVar));
        Assertions.assertTrue(clazzWildcard.isAssignableTo(clazzWildcard));

        Assertions.assertFalse(clazzTypeVar.isAssignableTo(clazzConcrete));
        Assertions.assertTrue(clazzTypeVar.isAssignableTo(clazzTypeVar));
        Assertions.assertTrue(clazzTypeVar.isAssignableTo(clazzWildcard));

        Assertions.assertTrue(clazzConcrete.isAssignableTo(clazzConcrete));
        Assertions.assertFalse(clazzConcrete.isAssignableTo(clazzTypeVar));
        Assertions.assertTrue(clazzConcrete.isAssignableTo(clazzWildcard));
    }

    @Test
    public void testAssignableClassLiteralToParameterizedClassType() {
        Type linkedListOfString = new ParameterizedTypeImpl(java.util.LinkedList.class,
                new Type[]{String.class}, null);
        Type classOfLinkedListOfString = new ParameterizedTypeImpl(Class.class,
                new Type[]{linkedListOfString}, null);
        Type classOfLinkedList = new ParameterizedTypeImpl(Class.class,
                new Type[]{java.util.LinkedList.class}, null);

        GenericClass<?> expected = GenericClassFactory.get(classOfLinkedListOfString);
        GenericClass<?> actualClassLiteralType = GenericClassFactory.get(classOfLinkedList);

        Assertions.assertTrue(actualClassLiteralType.isAssignableTo(expected));
    }

    public static class ClassLiteralHolder {
        public Class<LinkedList<String>> value;
    }

    @Test
    public void testAssignableClassLiteralToReflectedParameterizedClassType() throws Exception {
        Type reflectedFieldType = ClassLiteralHolder.class.getField("value").getGenericType();
        Type classOfLinkedList = new ParameterizedTypeImpl(Class.class,
                new Type[]{java.util.LinkedList.class}, null);

        GenericClass<?> expected = GenericClassFactory.get(reflectedFieldType);
        GenericClass<?> actualClassLiteralType = GenericClassFactory.get(classOfLinkedList);

        Assertions.assertTrue(actualClassLiteralType.isAssignableTo(expected));
    }

    @Test
    public void testConstrainedGenericInstantiationForClassTypeArgument() throws Exception {
        WildcardType requiredBounds = new WildcardTypeImpl(
                new Type[]{new ParameterizedTypeImpl(Class.class, new Type[]{java.util.Date.class}, null)},
                new Type[]{});
        Type classOfSqlDate = new ParameterizedTypeImpl(Class.class,
                new Type[]{java.sql.Date.class}, null);

        GenericClass<?> concrete = GenericClassFactory.get(classOfSqlDate);
        GenericClass<?> instantiated = concrete.getGenericInstantiation(new HashMap<>(), 0, requiredBounds);

        Assertions.assertTrue(instantiated.satisfiesBoundaries(requiredBounds));
    }

    @Test
    public void testConstrainedGenericInstantiationWithNullBounds() throws Exception {
        Type classOfSqlDate = new ParameterizedTypeImpl(Class.class, new Type[]{java.sql.Date.class}, null);
        GenericClass<?> concrete = GenericClassFactory.get(classOfSqlDate);

        GenericClass<?> instantiated = concrete.getGenericInstantiation(new HashMap<>(), 0, null);
        Assertions.assertTrue(instantiated.isAssignableTo(concrete));
    }

    private static class A {
    }

    @SuppressWarnings({"unused"})
    @Test
    public void test01() throws Throwable {

        /*
         * This test case come from compilation issue found during SBST'13 competition:
         *
         * String string0 = vM0.run(vM0.stack);
         *
         * SUT at:  http://www.massapi.com/source/jabref-2.6/src/java/net/sf/jabref/bst/VM.java.html
         *
         * Snippet of interest:
         *
         * 1) Stack<Object> stack = new Stack<Object>();
         * 2)  public String run(Collection<BibtexEntry> bibtex) {
         */

        Collection<?> col0 = new Stack<>();
        Collection<A> col1 = new Stack();
        Collection col2 = new Stack();
        Collection col3 = new Stack<>();

        /*
         *  following does not compile
         *
         *  Collection<A> col = new Stack<Object>();
         *
         *  but it can be generated by EvoSuite
         */

        GenericClass<?> stack = GenericClassFactory.get(Stack.class).getWithWildcardTypes();
        GenericClass<?> collection = GenericClassFactory.get(Collection.class).getWithWildcardTypes();
        Assertions.assertTrue(stack.isAssignableTo(collection));

        GenericClass<?> objectStack = GenericClassFactory.get(col0.getClass());
        Assertions.assertTrue(objectStack.isAssignableTo(collection));

        Type typeColA = new TypeToken<Collection<A>>() {
        }.getType();
        Type typeStack = new TypeToken<Stack>() {
        }.getType();
        Type typeObjectStack = new TypeToken<Stack<Object>>() {
        }.getType();

        GenericClass<?> classColA = GenericClassFactory.get(typeColA);
        GenericClass<?> classStack = GenericClassFactory.get(typeStack).getWithWildcardTypes();
        GenericClass<?> classObjectStack = GenericClassFactory.get(typeObjectStack);

        Assertions.assertFalse(classStack.isAssignableTo(classColA));
        Assertions.assertFalse(classObjectStack.isAssignableTo(classColA));
        Assertions.assertFalse(classColA.isAssignableFrom(classObjectStack));
    }

    @Test
    public void test1() {
        Type listOfString = new TypeToken<List<String>>() {
        }.getType();
        Type listOfInteger = new TypeToken<List<Integer>>() {
        }.getType();

        GenericClass<?> listOfStringClass = GenericClassFactory.get(listOfString);
        GenericClass<?> listOfIntegerClass = GenericClassFactory.get(listOfInteger);

        Assertions.assertFalse(listOfStringClass.isAssignableFrom(listOfIntegerClass));
        Assertions.assertFalse(listOfStringClass.isAssignableTo(listOfIntegerClass));
    }


    @Test
    public void test2() {
        Type listOfString = new TypeToken<List<String>>() {
        }.getType();
        Type plainList = new TypeToken<List>() {
        }.getType();
        Type objectList = new TypeToken<List<Object>>() {
        }.getType();

        GenericClass<?> listOfStringClass = GenericClassFactory.get(listOfString);
        GenericClass<?> plainListClass = GenericClassFactory.get(plainList).getWithWildcardTypes();
        GenericClass<?> objectListClass = GenericClassFactory.get(objectList);

        /*
         * Note:
         *
         * 		List<String> l = new LinkedList<Object>();
         *
         *  does not compile
         */

        Assertions.assertFalse(listOfStringClass.isAssignableTo(objectListClass));

        Assertions.assertFalse(listOfStringClass.isAssignableFrom(plainListClass));
        Assertions.assertTrue(listOfStringClass.isAssignableTo(plainListClass));
    }

    @Test
    public void test3() {
        Type listOfInteger = new TypeToken<List<Integer>>() {
        }.getType();
        Type listOfSerializable = new TypeToken<List<Serializable>>() {
        }.getType();

        GenericClass<?> listOfIntegerClass = GenericClassFactory.get(listOfInteger);
        GenericClass<?> listOfSerializableClass = GenericClassFactory.get(listOfSerializable);

        Assertions.assertFalse(listOfIntegerClass.isAssignableFrom(listOfSerializableClass));
        Assertions.assertFalse(listOfSerializableClass.isAssignableFrom(listOfIntegerClass));

        Assertions.assertTrue(listOfIntegerClass.isAssignableFrom(listOfIntegerClass));
        Assertions.assertTrue(listOfSerializableClass.isAssignableFrom(listOfSerializableClass));
    }

    private class NumberBoundary<T extends Number> {
    }

    private class ComparableBoundary<T extends Comparable<T>> {
    }

    private class RefinedComparableBoundary<T extends java.util.Date> extends
            ComparableBoundary<java.util.Date> {
    }

    @Test
    public void testTypeVariableBoundariesNumber() {
        TypeVariable<?> numberTypeVariable = NumberBoundary.class.getTypeParameters()[0];

        GenericClass<?> listOfIntegerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> listOfSerializableClass = GenericClassFactory.get(Serializable.class);

        Assertions.assertTrue(listOfIntegerClass.satisfiesBoundaries(numberTypeVariable));
        Assertions.assertFalse(listOfSerializableClass.satisfiesBoundaries(numberTypeVariable));
    }

    @Test
    public void testTypeVariableBoundariesComparable() {
        TypeVariable<?> comparableTypeVariable = ComparableBoundary.class.getTypeParameters()[0];

        GenericClass<?> listOfIntegerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> listOfSerializableClass = GenericClassFactory.get(Serializable.class);

        Assertions.assertTrue(listOfIntegerClass.satisfiesBoundaries(comparableTypeVariable));
        Assertions.assertFalse(listOfSerializableClass.satisfiesBoundaries(comparableTypeVariable));
    }

    @Test
    public void testGuavaExample() {
        Type abstractGuavaExampleString = new TypeToken<AbstractGuavaExample<String>>() {
        }.getType();
        Type guavaExample5 = new TypeToken<GuavaExample5<String>>() {
        }.getType();

        GenericClass<?> abstractClass = GenericClassFactory.get(abstractGuavaExampleString);
        GenericClass<?> concreteClass = GenericClassFactory.get(guavaExample5);

        Assertions.assertTrue(TypeUtils.isAssignable(concreteClass.getType(), abstractClass.getType()));
        Assertions.assertTrue(abstractClass.isAssignableFrom(concreteClass), "Cannot assign " + concreteClass + " to " + abstractClass);
        Assertions.assertTrue(concreteClass.isAssignableTo(abstractClass));
    }

    @Test
    public void testTypeVariableBoundariesRefined() {
        TypeVariable<?> dateTypeVariable = RefinedComparableBoundary.class.getTypeParameters()[0];
        TypeVariable<?> comparableTypeVariable = ComparableBoundary.class.getTypeParameters()[0];

        GenericClass<?> listOfIntegerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> listOfComparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> listOfDateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> listOfSqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertFalse(listOfIntegerClass.satisfiesBoundaries(dateTypeVariable));
        Assertions.assertFalse(listOfComparableClass.satisfiesBoundaries(dateTypeVariable));
        Assertions.assertTrue(listOfDateClass.satisfiesBoundaries(dateTypeVariable));
        Assertions.assertTrue(listOfSqlDateClass.satisfiesBoundaries(dateTypeVariable));

        Assertions.assertTrue(listOfIntegerClass.satisfiesBoundaries(comparableTypeVariable));
        //		Assert.assertTrue(listOfComparableClass.satisfiesBoundaries(comparableTypeVariable));
        Assertions.assertTrue(listOfDateClass.satisfiesBoundaries(comparableTypeVariable));
        // Assert.assertTrue(listOfSqlDateClass.satisfiesBoundaries(comparableTypeVariable));
    }

    @Test
    public void testWildcardObjectBoundaries() {

        WildcardType objectType = new WildcardTypeImpl(new Type[]{Object.class},
                new Type[]{});

        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> comparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> dateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> sqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertTrue(integerClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(comparableClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(dateClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(sqlDateClass.satisfiesBoundaries(objectType));
    }

    @Test
    public void testWildcardNumberBoundaries() {

        WildcardType objectType = new WildcardTypeImpl(new Type[]{Number.class},
                new Type[]{});

        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> comparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> dateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> sqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertTrue(integerClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(comparableClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(dateClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(sqlDateClass.satisfiesBoundaries(objectType));
    }

    @Test
    public void testWildcardIntegerBoundaries() {

        WildcardType objectType = new WildcardTypeImpl(new Type[]{Integer.class},
                new Type[]{});

        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> comparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> dateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> sqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertTrue(integerClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(comparableClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(dateClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(sqlDateClass.satisfiesBoundaries(objectType));
    }

    @Test
    public void testWildcardComparableBoundaries() {

        WildcardType objectType = new WildcardTypeImpl(new Type[]{Comparable.class},
                new Type[]{});

        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> comparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> dateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> sqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertTrue(integerClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(comparableClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(dateClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(sqlDateClass.satisfiesBoundaries(objectType));
    }

    @Test
    public void testWildcardDateBoundaries() {

        WildcardType objectType = new WildcardTypeImpl(
                new Type[]{java.util.Date.class}, new Type[]{});

        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> comparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> dateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> sqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertFalse(integerClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(comparableClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(dateClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(sqlDateClass.satisfiesBoundaries(objectType));
    }

    @Test
    public void testWildcardSqlDateBoundaries() {

        WildcardType objectType = new WildcardTypeImpl(
                new Type[]{java.sql.Date.class}, new Type[]{});

        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> comparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> dateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> sqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertFalse(integerClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(comparableClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(dateClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(sqlDateClass.satisfiesBoundaries(objectType));
    }

    @Test
    public void testWildcardDateSuperBoundaries() {

        WildcardType objectType = new WildcardTypeImpl(new Type[]{Object.class},
                new Type[]{java.util.Date.class});

        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> comparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> dateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> sqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertFalse(integerClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(comparableClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(dateClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(sqlDateClass.satisfiesBoundaries(objectType));
    }

    @Test
    public void testWildcardDateBothBoundaries() {

        WildcardType objectType = new WildcardTypeImpl(
                new Type[]{java.util.Date.class}, new Type[]{java.util.Date.class});

        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> comparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> dateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> sqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertFalse(integerClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(comparableClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(dateClass.satisfiesBoundaries(objectType));
        // Does not satisfy lower bound, so needs to be false
        Assertions.assertFalse(sqlDateClass.satisfiesBoundaries(objectType));
    }

    @Test
    public void testWildcardDateBothBoundaries2() {

        WildcardType objectType = new WildcardTypeImpl(new Type[]{Comparable.class},
                new Type[]{java.util.Date.class});

        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> comparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> dateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> sqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertFalse(integerClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(comparableClass.satisfiesBoundaries(objectType));
        Assertions.assertTrue(dateClass.satisfiesBoundaries(objectType));
        // Does not satisfy lower boundary
        Assertions.assertFalse(sqlDateClass.satisfiesBoundaries(objectType));
    }

    @Test
    public void testWildcardParameterizedUpperBoundRejectsWildcardArgumentCandidate() {
        Type linkedListOfString = new ParameterizedTypeImpl(LinkedList.class, new Type[]{String.class}, null);
        WildcardType wildcard = new WildcardTypeImpl(new Type[]{linkedListOfString}, new Type[]{});
        Type linkedListOfWildcard = new ParameterizedTypeImpl(LinkedList.class,
                new Type[]{new WildcardTypeImpl(new Type[]{Object.class}, new Type[]{})}, null);

        GenericClass<?> candidate = GenericClassFactory.get(linkedListOfWildcard);
        Assertions.assertFalse(candidate.satisfiesBoundaries(wildcard));
    }

    @Test
    public void testWildcardInvalidBoundaries() {

        WildcardType objectType = new WildcardTypeImpl(new Type[]{Number.class},
                new Type[]{java.util.Date.class});

        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> comparableClass = GenericClassFactory.get(Comparable.class);
        GenericClass<?> dateClass = GenericClassFactory.get(java.util.Date.class);
        GenericClass<?> sqlDateClass = GenericClassFactory.get(java.sql.Date.class);

        Assertions.assertFalse(integerClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(comparableClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(dateClass.satisfiesBoundaries(objectType));
        Assertions.assertFalse(sqlDateClass.satisfiesBoundaries(objectType));
    }

    @Test
    public void testGenericSuperclassWildcards() {
        GenericClass<?> listOfInteger = GenericClassFactory.get(new TypeToken<List<Integer>>() {
        }.getType());
        GenericClass<?> listOfWildcard = GenericClassFactory.get(new TypeToken<List<?>>() {
        }.getType());

        Assertions.assertTrue(listOfWildcard.isGenericSuperTypeOf(listOfInteger));
        Assertions.assertFalse(listOfInteger.isGenericSuperTypeOf(listOfWildcard));
        Assertions.assertTrue(listOfInteger.hasGenericSuperType(listOfWildcard));
        Assertions.assertFalse(listOfWildcard.hasGenericSuperType(listOfInteger));

        GenericClass<?> mapOfInteger = GenericClassFactory.get(
                new TypeToken<Map<Integer, String>>() {
                }.getType());
        GenericClass<?> mapOfWildcard = GenericClassFactory.get(new TypeToken<Map<?, ?>>() {
        }.getType());
        Assertions.assertTrue(mapOfWildcard.isGenericSuperTypeOf(mapOfInteger));
        Assertions.assertFalse(mapOfInteger.isGenericSuperTypeOf(mapOfWildcard));
        Assertions.assertTrue(mapOfInteger.hasGenericSuperType(mapOfWildcard));
        Assertions.assertFalse(mapOfWildcard.hasGenericSuperType(mapOfInteger));
    }

    @Test
    public void testGenericSuperclassConcreteList() {
        GenericClass<?> listOfInteger = GenericClassFactory.get(new TypeToken<List<Integer>>() {
        }.getType());
        GenericClass<?> linkedlistOfInteger = GenericClassFactory.get(
                new TypeToken<LinkedList<Integer>>() {
                }.getType());

        Assertions.assertTrue(linkedlistOfInteger.canBeInstantiatedTo(listOfInteger));
        Assertions.assertFalse(listOfInteger.canBeInstantiatedTo(linkedlistOfInteger));
    }

    @Test
    public void testGenericSuperclassToWildcardList() {
        GenericClass<?> listOfWildcard = GenericClassFactory.get(new TypeToken<List<Integer>>() {
        }.getType()).getWithWildcardTypes();
        GenericClass<?> linkedlistOfInteger = GenericClassFactory.get(
                new TypeToken<LinkedList<Integer>>() {
                }.getType());

        Assertions.assertTrue(linkedlistOfInteger.canBeInstantiatedTo(listOfWildcard));
        Assertions.assertFalse(listOfWildcard.canBeInstantiatedTo(linkedlistOfInteger));
    }

    @Test
    public void testGenericSuperclassFromWildcardList() {
        GenericClass<?> listOfInteger = GenericClassFactory.get(new TypeToken<List<Integer>>() {
        }.getType());
        GenericClass<?> linkedlistOfWildcard = GenericClassFactory.get(
                new TypeToken<LinkedList<Integer>>() {
                }.getType()).getWithWildcardTypes();

        Assertions.assertTrue(linkedlistOfWildcard.canBeInstantiatedTo(listOfInteger));
        Assertions.assertFalse(listOfInteger.canBeInstantiatedTo(linkedlistOfWildcard));
    }


    @Test
    public void testGenericSuperclassToTypeVariableList() {
        GenericClass<?> listOfTypeVariable = GenericClassFactory.get(new TypeToken<List>() {
        }.getType());
        GenericClass<?> linkedlistOfInteger = GenericClassFactory.get(
                new TypeToken<LinkedList<Integer>>() {
                }.getType());

        Assertions.assertTrue(linkedlistOfInteger.canBeInstantiatedTo(listOfTypeVariable));
        Assertions.assertFalse(listOfTypeVariable.canBeInstantiatedTo(linkedlistOfInteger));
    }


    @Test
    public void testGenericSuperclassFromTypeVariableList() {
        GenericClass<?> listOfInteger = GenericClassFactory.get(new TypeToken<List<Integer>>() {
        }.getType());
        GenericClass<?> linkedlistOfTypeVariable = GenericClassFactory.get(
                new TypeToken<LinkedList>() {
                }.getType());

        Assertions.assertTrue(linkedlistOfTypeVariable.canBeInstantiatedTo(listOfInteger));
        Assertions.assertFalse(listOfInteger.canBeInstantiatedTo(linkedlistOfTypeVariable));
    }

    @Test
    public void testPrimitiveWrapper() {
        GenericClass<?> integerClass = GenericClassFactory.get(Integer.class);
        GenericClass<?> intClass = GenericClassFactory.get(int.class);

        Assertions.assertTrue(integerClass.canBeInstantiatedTo(intClass));
        Assertions.assertFalse(intClass.canBeInstantiatedTo(integerClass));
    }


    @Test
    public void testGenericInstantiationIntegerList() throws ConstructionFailedException {
        GenericClass<?> listOfInteger = GenericClassFactory.get(new TypeToken<List<Integer>>() {
        }.getType());
        GenericClass<?> linkedlistOfTypeVariable = GenericClassFactory.get(
                new TypeToken<LinkedList>() {
                }.getType());

        GenericClass<?> instantiatedClass = linkedlistOfTypeVariable.getWithParametersFromSuperclass(listOfInteger);
        //GenericClass instantiatedClass = linkedlistOfTypeVariable.getGenericInstantiation(listOfInteger.getTypeVariableMap());
        Assertions.assertEquals(Integer.class, instantiatedClass.getParameterTypes().get(0));
    }


    @Test
    public void testGenericInstantiationMapSubclass() throws ConstructionFailedException {
        GenericClass<?> mapOfStringAndWildcard = GenericClassFactory.get(
                new TypeToken<Map<String, ?>>() {
                }.getType());
        GenericClass<?> hashMapClass = GenericClassFactory.get(new TypeToken<HashMap>() {
        }.getType());

        GenericClass<?> instantiatedClass = hashMapClass.getWithParametersFromSuperclass(mapOfStringAndWildcard);
        //GenericClass instantiatedClass = linkedlistOfTypeVariable.getGenericInstantiation(listOfInteger.getTypeVariableMap());
        System.out.println(instantiatedClass.toString());
        Assertions.assertEquals(String.class, instantiatedClass.getParameterTypes().get(0));
    }

    @Disabled
    @Test
    public void testGenericInstantiationMapType() throws ConstructionFailedException {
        GenericClass<?> genericClass = GenericClassFactory.get(
                com.examples.with.different.packagename.generic.GenericParameterExtendingGenericBounds.class);
        GenericClass<?> instantiatedClass = genericClass.getGenericInstantiation();
        //GenericClass instantiatedClass = linkedlistOfTypeVariable.getGenericInstantiation(listOfInteger.getTypeVariableMap());
        System.out.println(instantiatedClass.toString());
        Type parameterType = instantiatedClass.getParameterTypes().get(0);
        Assertions.assertTrue(TypeUtils.isAssignable(parameterType, Map.class));
        Assertions.assertTrue(parameterType instanceof ParameterizedType);

        ParameterizedType parameterizedType = (ParameterizedType) parameterType;
        Assertions.assertEquals(String.class, parameterizedType.getActualTypeArguments()[0]);
    }

    @Test
    public void testIterableAndList() throws ConstructionFailedException {
        GenericClass<?> iterableIntegerClass = GenericClassFactory.get(
                new TypeToken<java.lang.Iterable<Integer>>() {
                }.getType());
        GenericClass<?> arrayListClass = GenericClassFactory.get(java.util.ArrayList.class);

        Assertions.assertTrue(arrayListClass.canBeInstantiatedTo(iterableIntegerClass));
        Assertions.assertFalse(iterableIntegerClass.canBeInstantiatedTo(arrayListClass));

        GenericClass<?> instantiatedList = arrayListClass.getWithParametersFromSuperclass(iterableIntegerClass);

        Type parameterType = instantiatedList.getParameterTypes().get(0);
        Assertions.assertEquals(Integer.class, GenericTypeReflector.erase(parameterType));
    }


    @Test
    public void testIterableAndListBoundaries() {
        Map<TypeVariable<?>, Type> typeMap = new HashMap<>();
        final GenericClass<?> iterableIntegerClass = GenericClassFactory.get(
                new TypeToken<java.lang.Iterable<Integer>>() {
                }.getType());

        TypeVariable<?> var = new TypeVariable() {

            public AnnotatedType[] getAnnotatedBounds() {
                return null;
            }

            @Override
            public Type[] getBounds() {
                return new Type[]{iterableIntegerClass.getType()};
            }

            @Override
            public GenericDeclaration getGenericDeclaration() {
                // TODO Auto-generated method stub
                return null;
            }

            @Override
            public String getName() {
                return "Test";
            }

            /* (non-Javadoc)
             * @see java.lang.Object#toString()
             */
            @Override
            public String toString() {
                return "Dummy Variable";
            }

            public <T extends Annotation> T getAnnotation(
                    Class<T> annotationClass) {
                // TODO Auto-generated method stub
                return null;
            }

            public Annotation[] getAnnotations() {
                // TODO Auto-generated method stub
                return null;
            }

            public Annotation[] getDeclaredAnnotations() {
                // TODO Auto-generated method stub
                return null;
            }

            //public void getAnnotatedBounds() {
            // TODO Auto-generated method stub
            //}

        };

        typeMap.put(var, iterableIntegerClass.getType());

        GenericClass<?> arrayListClass = GenericClassFactory.get(
                new TypeToken<java.util.ArrayList<String>>() {
                }.getType());

        Assertions.assertFalse(arrayListClass.satisfiesBoundaries(var, typeMap));

        arrayListClass = GenericClassFactory.get(
                new TypeToken<java.util.ArrayList<Integer>>() {
                }.getType());

        Assertions.assertTrue(arrayListClass.satisfiesBoundaries(var, typeMap));

    }

    @Test
    public void testSatisfiesTypeVariableInSubtype() {
        GenericClass<?> iterableIntegerClass = GenericClassFactory.get(
                new TypeToken<com.examples.with.different.packagename.generic.GuavaExample4<java.lang.Iterable<Integer>>>() {
                }.getType());
        ParameterizedType iterableInteger = (ParameterizedType) iterableIntegerClass.getParameterTypes().get(0);
        TypeVariable<?> typeVariable = ((Class<?>) iterableInteger.getRawType()).getTypeParameters()[0];

        TypeVariable<?> iterableTypeVariable = iterableIntegerClass.getTypeVariables().get(0);

        GenericClass<?> listOfIntegerClass = GenericClassFactory.get(
                com.examples.with.different.packagename.generic.GuavaExample4.class);

        // Object bound
        Assertions.assertTrue(iterableIntegerClass.satisfiesBoundaries(typeVariable));
        Assertions.assertTrue(listOfIntegerClass.satisfiesBoundaries(typeVariable));

        // Iterable bound
        Assertions.assertTrue(iterableIntegerClass.satisfiesBoundaries(iterableTypeVariable));
        Assertions.assertTrue(listOfIntegerClass.satisfiesBoundaries(iterableTypeVariable));

    }

    @Test
    public void reloadArrayClass() {
        GenericClass<?> arrayClass = GenericClassFactory.get(Object[].class);
        ClassLoader loader = new InstrumentingClassLoader();
        arrayClass.changeClassLoader(loader);
        Class<?> rawClass = arrayClass.getRawClass();
        Assertions.assertTrue(rawClass.isArray());

    }

    @Test
    public void reloadNonArrayClass() {
        GenericClass<?> arrayClass = GenericClassFactory.get(Integer.class);
        ClassLoader loader = new InstrumentingClassLoader();
        arrayClass.changeClassLoader(loader);
        Class<?> rawClass = arrayClass.getRawClass();
        Assertions.assertFalse(rawClass.isArray());
    }

    @Test
    public void testWildcardInstantiation() throws ConstructionFailedException {

        GenericClass<?> integerWildcardListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<? extends Integer>>() {
                }.getType());

        GenericClass<?> integerListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<Integer>>() {
                }.getType());
        GenericClass<?> objectListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<Object>>() {
                }.getType());

        Assertions.assertTrue(integerWildcardListClass.isAssignableFrom(integerListClass));
        Assertions.assertFalse(integerWildcardListClass.isAssignableFrom(objectListClass));

        GenericClass<?> integerWildcardListInstantiation = integerWildcardListClass.getGenericInstantiation();
        Assertions.assertTrue(integerWildcardListClass.isAssignableFrom(integerWildcardListInstantiation));
    }

    @Test
    public void testWildcardWithSuperIntegerBoundaryInstantiation() throws ConstructionFailedException {

        GenericClass<?> integerWildcardListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<? super Integer>>() {
                }.getType());

        GenericClass<?> integerListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<Integer>>() {
                }.getType());
        GenericClass<?> numberListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<Number>>() {
                }.getType());
        GenericClass<?> objectListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<Object>>() {
                }.getType());

        Assertions.assertTrue(integerWildcardListClass.isAssignableFrom(integerListClass));
        Assertions.assertTrue(integerWildcardListClass.isAssignableFrom(numberListClass));
        Assertions.assertTrue(integerWildcardListClass.isAssignableFrom(objectListClass));

        GenericClass<?> integerWildcardListInstantiation = integerWildcardListClass.getGenericInstantiation();
        Assertions.assertTrue(integerWildcardListClass.isAssignableFrom(integerWildcardListInstantiation));
    }

    @Test
    public void testWildcardWithSuperNumberBoundaryInstantiation() throws ConstructionFailedException {

        GenericClass<?> numberWildcardListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<? super Number>>() {
                }.getType());

        GenericClass<?> integerListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<Integer>>() {
                }.getType());
        GenericClass<?> numberListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<Number>>() {
                }.getType());
        GenericClass<?> objectListClass = GenericClassFactory.get(
                new TypeToken<java.util.List<Object>>() {
                }.getType());

        Assertions.assertFalse(numberWildcardListClass.isAssignableFrom(integerListClass));
        Assertions.assertTrue(numberWildcardListClass.isAssignableFrom(numberListClass));
        Assertions.assertTrue(numberWildcardListClass.isAssignableFrom(objectListClass));

        GenericClass<?> integerWildcardListInstantiation = numberWildcardListClass.getGenericInstantiation();
        System.out.println(integerWildcardListInstantiation.toString());
        Assertions.assertTrue(numberWildcardListClass.isAssignableFrom(integerWildcardListInstantiation));
    }

    @Test
    public void testWildcardWithConcreteParameterizedBoundAtRecursionLevelInstantiatesConcreteArgument()
            throws ConstructionFailedException {
        Type linkedListOfString = new ParameterizedTypeImpl(LinkedList.class, new Type[]{String.class}, null);
        Type wildcard = new WildcardTypeImpl(new Type[]{linkedListOfString}, new Type[]{});
        Type listOfWildcardBound = new ParameterizedTypeImpl(LinkedList.class, new Type[]{wildcard}, null);

        GenericClass<?> genericClass = GenericClassFactory.get(listOfWildcardBound);
        GenericClass<?> instantiated = genericClass.getGenericInstantiation(new HashMap<>(), 1);

        Assertions.assertTrue(instantiated.getType() instanceof ParameterizedType);
        Type actualArgument = ((ParameterizedType) instantiated.getType()).getActualTypeArguments()[0];
        Assertions.assertFalse(actualArgument instanceof WildcardType);
    }

    @Test
    public void testCollectionWildcardBoundDoesNotInstantiateNestedWildcardAtRecursionLevel()
            throws ConstructionFailedException {
        Type linkedListOfString = new ParameterizedTypeImpl(LinkedList.class, new Type[]{String.class}, null);
        Type wildcardElement = new WildcardTypeImpl(new Type[]{linkedListOfString}, new Type[]{});
        Type expectedCollection = new ParameterizedTypeImpl(Collection.class, new Type[]{wildcardElement}, null);

        GenericClass<?> genericClass = GenericClassFactory.get(expectedCollection);
        GenericClass<?> instantiated = genericClass.getGenericInstantiation(new HashMap<>(), 1);

        Assertions.assertTrue(instantiated.isAssignableTo(GenericClassFactory.get(expectedCollection)));
        Assertions.assertTrue(instantiated.getType() instanceof ParameterizedType);
        Type collectionArg = ((ParameterizedType) instantiated.getType()).getActualTypeArguments()[0];
        Assertions.assertFalse(collectionArg instanceof WildcardType);
        Assertions.assertTrue(collectionArg instanceof ParameterizedType);
        Type nestedArg = ((ParameterizedType) collectionArg).getActualTypeArguments()[0];
        Assertions.assertFalse(nestedArg instanceof WildcardType);
    }

    @Test
    public void testMapClassWildcardBoundDoesNotInstantiateClassQuestionMarkAtRecursionLevel()
            throws ConstructionFailedException {
        Type classOfString = new ParameterizedTypeImpl(Class.class, new Type[]{String.class}, null);
        Type classOfInteger = new ParameterizedTypeImpl(Class.class, new Type[]{Integer.class}, null);
        Type wildcardKey = new WildcardTypeImpl(new Type[]{classOfString}, new Type[]{});
        Type wildcardValue = new WildcardTypeImpl(new Type[]{classOfInteger}, new Type[]{});
        Type expectedMap = new ParameterizedTypeImpl(Map.class, new Type[]{wildcardKey, wildcardValue}, null);

        GenericClass<?> genericClass = GenericClassFactory.get(expectedMap);
        GenericClass<?> instantiated = genericClass.getGenericInstantiation(new HashMap<>(), 1);

        Assertions.assertTrue(instantiated.isAssignableTo(GenericClassFactory.get(expectedMap)),
                "instantiated=" + instantiated.getType());
        Assertions.assertTrue(instantiated.getType() instanceof ParameterizedType);
        Type[] args = ((ParameterizedType) instantiated.getType()).getActualTypeArguments();
        Assertions.assertFalse(args[0] instanceof WildcardType);
        Assertions.assertFalse(args[1] instanceof WildcardType);
        Assertions.assertTrue(args[0] instanceof ParameterizedType);
        Assertions.assertTrue(args[1] instanceof ParameterizedType);
        Type keyClassArg = ((ParameterizedType) args[0]).getActualTypeArguments()[0];
        Type valueClassArg = ((ParameterizedType) args[1]).getActualTypeArguments()[0];
        Assertions.assertFalse(keyClassArg instanceof WildcardType);
        Assertions.assertFalse(valueClassArg instanceof WildcardType);
    }

    @Test
    public void testClassStringDoesNotSatisfyWildcardUpperBoundClassInteger() {
        Type classOfString = new ParameterizedTypeImpl(Class.class, new Type[]{String.class}, null);
        Type classOfInteger = new ParameterizedTypeImpl(Class.class, new Type[]{Integer.class}, null);
        WildcardType wildcard = new WildcardTypeImpl(new Type[]{classOfInteger}, new Type[]{});

        GenericClass<?> candidate = GenericClassFactory.get(classOfString);
        Assertions.assertFalse(candidate.satisfiesBoundaries(wildcard));
    }
}
