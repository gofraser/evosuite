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
package org.evosuite.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Class used to access private fields/methods by reflection.
 * If the accessed fields/methods do not exist any more, than
 * the tests would gracefully stop.
 *
 * <p>Created by Andrea on 20/02/15.
 */
public class PrivateAccess {

    private static final Logger logger = LoggerFactory.getLogger(PrivateAccess.class);

    /**
     * flag to specify to throw AssumptionViolatedException when fields/methods do not
     * exist any more. this should bet set to false iff during experiments
     */
    private static boolean shouldNotFailTest = true;

    public static void setShouldNotFailTest(boolean b) {
        shouldNotFailTest = b;
    }


    /**
     * Use reflection to set the given field.
     *
     * @param klass a {@link java.lang.Class} object.
     * @param instance  null if field is static
     * @param fieldName a {@link java.lang.String} object.
     * @param value a {@link java.lang.Object} object.
     * @param <T>       the class type
     * @throws java.lang.IllegalArgumentException if klass or fieldName are null
     * @throws org.evosuite.runtime.FalsePositiveException   if the the field does not exist
     *     anymore (eg due to refactoring)
     */
    public static <T> void setVariable(Class<?> klass, T instance, String fieldName, Object value)
            throws IllegalArgumentException, FalsePositiveException {

        if (klass == null) {
            throw new IllegalArgumentException("No specified class");
        }
        if (fieldName == null) {
            throw new IllegalArgumentException("No specified field name");
        }
        if (fieldName.equals("serialVersionUID")) {
            throw new IllegalArgumentException("It is not allowed to set serialVersionUID by reflection");
        }
        // note: 'instance' can be null (ie, for static variables), and of course "value"

        Field field = null;
        try {
            field = klass.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            String message = "Field '" + fieldName + "' does not exist any more in class " + klass;

            if (shouldNotFailTest) {
                // force the throwing of a JUnit AssumptionViolatedException
                throw new FalsePositiveException(message);
                //it is equivalent to calling
                //org.junit.Assume.assumeTrue(message,false);
            } else {
                throw new IllegalArgumentException(message);
            }
        }
        assert field != null;
        field.setAccessible(true);

        try {
            Reflection.setField(field, instance, value);
        } catch (IllegalAccessException e) {
            //should never happen, due to setAccessible(true);
            throw new FalsePositiveException("Failed to set field " + fieldName + ": " + e);
        }
    }

    /**
     * Call the default constructor of the class under test.
     * This is useful to avoid low coverage due to private constructors in final
     * classes used to prevent instantiating them (eg those classes only have
     * static methods)
     *
     * @return a {@link java.lang.Object} object.
     * @throws java.lang.Throwable if any.
     */
    public static Object callDefaultConstructorOfTheClassUnderTest() throws Throwable {

        Class<?> cut = Thread.currentThread().getContextClassLoader().loadClass(RuntimeSettings.className);
        return callDefaultConstructor(cut);
    }

    /**
     * Call the default constructor of the given klass.
     * This is useful to avoid low coverage due to private constructors in final
     * classes used to prevent instantiating them (eg those classes only have
     * static methods)
     *
     * @param klass a {@link java.lang.Class} object.
     * @param <T> type of the class
     * @return a {@link java.lang.Object} object.
     * @throws java.lang.Throwable if any.
     */
    public static <T> T callDefaultConstructor(Class<T> klass) throws Throwable {

        //TODO: not only should be atMostOnce in a test, but in the whole suite/archive

        if (klass == null) {
            throw new IllegalArgumentException("No specified class");
        }

        //RuntimeSettings

        Constructor<T> constructor;
        try {
            constructor = klass.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            String message = "Default constructor does not exist anymore";
            if (shouldNotFailTest) {
                throw new FalsePositiveException(message);
            } else {
                throw new IllegalArgumentException(message);
            }
        }

        assert constructor != null;
        constructor.setAccessible(true);

        try {
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new FalsePositiveException("Failed to call the default constructor of " + klass.getName() + ": " + e);
        } catch (InvocationTargetException e) {
            //we need to propagate the real cause to the test
            throw e.getTargetException();
        }
    }

    /**
     * Use reflection to call the given method.
     *
     * @param klass a {@link java.lang.Class} object.
     * @param instance   null for static methods
     * @param methodName a {@link java.lang.String} object.
     * @param inputs     arrays of inputs
     * @param types      types for the inputs
     * @param <T> type of the class
     * @return the result of calling the method
     * @throws java.lang.IllegalArgumentException if either klass or methodName are null
     * @throws org.evosuite.runtime.FalsePositiveException   if method does not exist any more
     *     (eg, refactoring)
     * @throws java.lang.Throwable                the method might throw an internal exception
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object[] inputs,
                                        Class<?>[] types)
            throws Throwable {

        if (klass == null) {
            throw new IllegalArgumentException("No specified class");
        }
        if (methodName == null) {
            throw new IllegalArgumentException("No specified method name");
        }
        // note: 'instance' can be null (ie, for static methods), and of course "inputs"

        if ((types == null && inputs != null) || (types != null && inputs == null)
                || (types != null && inputs != null && types.length != inputs.length)) {
            throw new IllegalArgumentException("Mismatch between input parameters and their type description");
        }

        Method method = null;
        try {
            method = klass.getDeclaredMethod(methodName, types);
        } catch (NoSuchMethodException e) {
            String message = "Method " + methodName + " does not exist anymore";
            if (shouldNotFailTest) {
                throw new FalsePositiveException(message);
            } else {
                throw new IllegalArgumentException(message);
            }
        }
        assert method != null;
        method.setAccessible(true);

        Object result = null;

        try {
            result = method.invoke(instance, inputs);
        } catch (IllegalAccessException e) {
            //shouldn't really happen
            throw new FalsePositiveException("Failed to call " + methodName + ": " + e);
        } catch (InvocationTargetException e) {
            //we need to propagate the real cause to the test
            throw e.getTargetException();
        }

        return result;
    }

    /*
        TODO likely need one method per number of inputs
     */

    /**
     * Use reflection to call a method with no parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[0], new Class<?>[0]);
    }

    /**
     * Use reflection to call a method with one parameter.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param input      the input parameter
     * @param type       the type of the input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object input, Class<?> type)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{input}, new Class<?>[]{type});
    }

    /**
     * Use reflection to call a method with two parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1}, new Class<?>[]{t0, t1});
    }

    /**
     * Use reflection to call a method with three parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2}, new Class<?>[]{t0, t1, t2});
    }

    /**
     * Use reflection to call a method with four parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2, i3},
                new Class<?>[]{t0, t1, t2, t3});
    }

    /**
     * Use reflection to call a method with five parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2, i3, i4},
                new Class<?>[]{t0, t1, t2, t3, t4});
    }

    /**
     * Use reflection to call a method with six parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2, i3, i4, i5},
                new Class<?>[]{t0, t1, t2, t3, t4, t5});
    }

    /**
     * Use reflection to call a method with seven parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2, i3, i4, i5, i6},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6});
    }

    /**
     * Use reflection to call a method with eight parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2, i3, i4, i5, i6, i7},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7});
    }

    /**
     * Use reflection to call a method with nine parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8});
    }

    /**
     * Use reflection to call a method with ten parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9});
    }

    /**
     * Use reflection to call a method with eleven parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param i10        the eleventh input parameter
     * @param t10        the type of the eleventh input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9,
                                        Object i10, Class<?> t10)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10});
    }

    /**
     * Use reflection to call a method with twelve parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param i10        the eleventh input parameter
     * @param t10        the type of the eleventh input parameter
     * @param i11        the twelfth input parameter
     * @param t11        the type of the twelfth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9,
                                        Object i10, Class<?> t10, Object i11, Class<?> t11)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10, i11},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11});
    }

    /**
     * Use reflection to call a method with thirteen parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param i10        the eleventh input parameter
     * @param t10        the type of the eleventh input parameter
     * @param i11        the twelfth input parameter
     * @param t11        the type of the twelfth input parameter
     * @param i12        the thirteenth input parameter
     * @param t12        the type of the thirteenth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9,
                                        Object i10, Class<?> t10, Object i11, Class<?> t11, Object i12, Class<?> t12)
            throws Throwable {
        return callMethod(klass, instance, methodName,
                new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10, i11, i12},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12});
    }

    /**
     * Use reflection to call a method with fourteen parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param i10        the eleventh input parameter
     * @param t10        the type of the eleventh input parameter
     * @param i11        the twelfth input parameter
     * @param t11        the type of the twelfth input parameter
     * @param i12        the thirteenth input parameter
     * @param t12        the type of the thirteenth input parameter
     * @param i13        the fourteenth input parameter
     * @param t13        the type of the fourteenth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9,
                                        Object i10, Class<?> t10, Object i11, Class<?> t11, Object i12, Class<?> t12,
                                        Object i13, Class<?> t13)
            throws Throwable {
        return callMethod(klass, instance, methodName,
                new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10, i11, i12, i13},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13});
    }

    /**
     * Use reflection to call a method with fifteen parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param i10        the eleventh input parameter
     * @param t10        the type of the eleventh input parameter
     * @param i11        the twelfth input parameter
     * @param t11        the type of the twelfth input parameter
     * @param i12        the thirteenth input parameter
     * @param t12        the type of the thirteenth input parameter
     * @param i13        the fourteenth input parameter
     * @param t13        the type of the fourteenth input parameter
     * @param i14        the fifteenth input parameter
     * @param t14        the type of the fifteenth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9,
                                        Object i10, Class<?> t10, Object i11, Class<?> t11, Object i12, Class<?> t12,
                                        Object i13, Class<?> t13, Object i14, Class<?> t14)
            throws Throwable {
        return callMethod(klass, instance, methodName,
                new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10, i11, i12, i13, i14},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14});
    }

    /**
     * Use reflection to call a method with sixteen parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param i10        the eleventh input parameter
     * @param t10        the type of the eleventh input parameter
     * @param i11        the twelfth input parameter
     * @param t11        the type of the twelfth input parameter
     * @param i12        the thirteenth input parameter
     * @param t12        the type of the thirteenth input parameter
     * @param i13        the fourteenth input parameter
     * @param t13        the type of the fourteenth input parameter
     * @param i14        the fifteenth input parameter
     * @param t14        the type of the fifteenth input parameter
     * @param i15        the sixteenth input parameter
     * @param t15        the type of the sixteenth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9,
                                        Object i10, Class<?> t10, Object i11, Class<?> t11, Object i12, Class<?> t12,
                                        Object i13, Class<?> t13, Object i14, Class<?> t14, Object i15, Class<?> t15)
            throws Throwable {
        return callMethod(klass, instance, methodName,
                new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10, i11, i12, i13, i14, i15},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15});
    }

    /**
     * Use reflection to call a method with seventeen parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param i10        the eleventh input parameter
     * @param t10        the type of the eleventh input parameter
     * @param i11        the twelfth input parameter
     * @param t11        the type of the twelfth input parameter
     * @param i12        the thirteenth input parameter
     * @param t12        the type of the thirteenth input parameter
     * @param i13        the fourteenth input parameter
     * @param t13        the type of the fourteenth input parameter
     * @param i14        the fifteenth input parameter
     * @param t14        the type of the fifteenth input parameter
     * @param i15        the sixteenth input parameter
     * @param t15        the type of the sixteenth input parameter
     * @param i16        the seventeenth input parameter
     * @param t16        the type of the seventeenth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9,
                                        Object i10, Class<?> t10, Object i11, Class<?> t11, Object i12, Class<?> t12,
                                        Object i13, Class<?> t13, Object i14, Class<?> t14, Object i15, Class<?> t15,
                                        Object i16, Class<?> t16)
            throws Throwable {
        return callMethod(klass, instance, methodName,
                new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10, i11, i12, i13, i14, i15, i16},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16});
    }

    /**
     * Use reflection to call a method with eighteen parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param i10        the eleventh input parameter
     * @param t10        the type of the eleventh input parameter
     * @param i11        the twelfth input parameter
     * @param t11        the type of the twelfth input parameter
     * @param i12        the thirteenth input parameter
     * @param t12        the type of the thirteenth input parameter
     * @param i13        the fourteenth input parameter
     * @param t13        the type of the fourteenth input parameter
     * @param i14        the fifteenth input parameter
     * @param t14        the type of the fifteenth input parameter
     * @param i15        the sixteenth input parameter
     * @param t15        the type of the sixteenth input parameter
     * @param i16        the seventeenth input parameter
     * @param t16        the type of the seventeenth input parameter
     * @param i17        the eighteenth input parameter
     * @param t17        the type of the eighteenth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9,
                                        Object i10, Class<?> t10, Object i11, Class<?> t11, Object i12, Class<?> t12,
                                        Object i13, Class<?> t13, Object i14, Class<?> t14, Object i15, Class<?> t15,
                                        Object i16, Class<?> t16, Object i17, Class<?> t17)
            throws Throwable {
        return callMethod(klass, instance, methodName,
                new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10, i11, i12, i13, i14, i15, i16, i17},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17});
    }

    /**
     * Use reflection to call a method with nineteen parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param i10        the eleventh input parameter
     * @param t10        the type of the eleventh input parameter
     * @param i11        the twelfth input parameter
     * @param t11        the type of the twelfth input parameter
     * @param i12        the thirteenth input parameter
     * @param t12        the type of the thirteenth input parameter
     * @param i13        the fourteenth input parameter
     * @param t13        the type of the fourteenth input parameter
     * @param i14        the fifteenth input parameter
     * @param t14        the type of the fifteenth input parameter
     * @param i15        the sixteenth input parameter
     * @param t15        the type of the sixteenth input parameter
     * @param i16        the seventeenth input parameter
     * @param t16        the type of the seventeenth input parameter
     * @param i17        the eighteenth input parameter
     * @param t17        the type of the eighteenth input parameter
     * @param i18        the nineteenth input parameter
     * @param t18        the type of the nineteenth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9,
                                        Object i10, Class<?> t10, Object i11, Class<?> t11, Object i12, Class<?> t12,
                                        Object i13, Class<?> t13, Object i14, Class<?> t14, Object i15, Class<?> t15,
                                        Object i16, Class<?> t16, Object i17, Class<?> t17, Object i18, Class<?> t18)
            throws Throwable {
        return callMethod(klass, instance, methodName,
                new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10, i11, i12, i13, i14, i15, i16, i17, i18},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18});
    }

    /**
     * Use reflection to call a method with twenty parameters.
     *
     * @param klass      the class containing the method
     * @param instance   the instance to call the method on (null for static methods)
     * @param methodName the name of the method
     * @param i0         the first input parameter
     * @param t0         the type of the first input parameter
     * @param i1         the second input parameter
     * @param t1         the type of the second input parameter
     * @param i2         the third input parameter
     * @param t2         the type of the third input parameter
     * @param i3         the fourth input parameter
     * @param t3         the type of the fourth input parameter
     * @param i4         the fifth input parameter
     * @param t4         the type of the fifth input parameter
     * @param i5         the sixth input parameter
     * @param t5         the type of the sixth input parameter
     * @param i6         the seventh input parameter
     * @param t6         the type of the seventh input parameter
     * @param i7         the eighth input parameter
     * @param t7         the type of the eighth input parameter
     * @param i8         the ninth input parameter
     * @param t8         the type of the ninth input parameter
     * @param i9         the tenth input parameter
     * @param t9         the type of the tenth input parameter
     * @param i10        the eleventh input parameter
     * @param t10        the type of the eleventh input parameter
     * @param i11        the twelfth input parameter
     * @param t11        the type of the twelfth input parameter
     * @param i12        the thirteenth input parameter
     * @param t12        the type of the thirteenth input parameter
     * @param i13        the fourteenth input parameter
     * @param t13        the type of the fourteenth input parameter
     * @param i14        the fifteenth input parameter
     * @param t14        the type of the fifteenth input parameter
     * @param i15        the sixteenth input parameter
     * @param t15        the type of the sixteenth input parameter
     * @param i16        the seventeenth input parameter
     * @param t16        the type of the seventeenth input parameter
     * @param i17        the eighteenth input parameter
     * @param t17        the type of the eighteenth input parameter
     * @param i18        the nineteenth input parameter
     * @param t18        the type of the nineteenth input parameter
     * @param i19        the twentieth input parameter
     * @param t19        the type of the twentieth input parameter
     * @param <T>        the class type
     * @return the result of the method call
     * @throws Throwable if the method call fails
     */
    public static <T> Object callMethod(Class<T> klass, T instance, String methodName, Object i0, Class<?> t0,
                                        Object i1, Class<?> t1, Object i2, Class<?> t2, Object i3, Class<?> t3,
                                        Object i4, Class<?> t4, Object i5, Class<?> t5, Object i6, Class<?> t6,
                                        Object i7, Class<?> t7, Object i8, Class<?> t8, Object i9, Class<?> t9,
                                        Object i10, Class<?> t10, Object i11, Class<?> t11, Object i12, Class<?> t12,
                                        Object i13, Class<?> t13, Object i14, Class<?> t14, Object i15, Class<?> t15,
                                        Object i16, Class<?> t16, Object i17, Class<?> t17, Object i18, Class<?> t18,
                                        Object i19, Class<?> t19)
            throws Throwable {
        return callMethod(klass, instance, methodName, new Object[]{i0, i1, i2, i3, i4, i5, i6, i7, i8, i9, i10, i11,
                i12, i13, i14, i15, i16, i17, i18, i19},
                new Class<?>[]{t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17,
                        t18, t19});
    }


    /**
     * Returns the reflection Method object for the callMethod with the given number of parameters.
     *
     * @param nparameters the number of parameters the target method has
     * @return the reflection Method object, or null if it cannot be found or the number of parameters is unsupported
     */
    public static Method getCallMethod(int nparameters) {
        int max = 20; //TODO might consider have more
        if (nparameters < 0 || nparameters > max) {
            logger.error("Cannot handle reflection on methods with more than {} parameters: asked for {}",
                    max, nparameters);
            return null;
        }

        List<Class<?>> types = new ArrayList<>();
        types.add(Class.class); //klass
        types.add(Object.class); //T
        types.add(String.class); //methodName

        for (int i = 0; i < nparameters; i++) {
            types.add(Object.class);
            types.add(Class.class);
        }

        try {
            return PrivateAccess.class.getDeclaredMethod("callMethod",
                    types.toArray(new Class[0]));
        } catch (NoSuchMethodException e) {
            logger.error("" + e.getMessage());
            return null;
        }
    }
}
