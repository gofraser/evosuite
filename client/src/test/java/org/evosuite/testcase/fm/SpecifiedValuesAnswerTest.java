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
package org.evosuite.testcase.fm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by Andrea Arcuri on 27/07/15.
 */
public class SpecifiedValuesAnswerTest {

    public interface BaseString {
        String getString();
    }

    private static boolean checkString_3different(BaseString s) {
        if (s.getString().equals("foo") && s.getString().equals("bar") && s.getString().equals("42")) {
            return true;
        } else {
            return false;
        }
    }

    private static boolean checkString_allSame(BaseString s) {
        if (s.getString().equals("foo") && s.getString().equals("foo") && s.getString().equals("foo")) {
            return true;
        } else {
            return false;
        }
    }

    @Test
    public void testBasicWithString_3different() {

        BaseString s = mock(BaseString.class);
        when(s.getString()).thenAnswer(new SpecifiedValuesAnswer<>("foo"));
        boolean res = checkString_3different(s);
        //should fail
        Assertions.assertFalse(res);

        when(s.getString()).thenAnswer(new SpecifiedValuesAnswer<>("foo", "bar", "42"));
        res = checkString_3different(s);
        Assertions.assertTrue(res);
    }


    @Test
    public void testBasicWithString_allSame() {

        BaseString s = mock(BaseString.class);
        when(s.getString()).thenAnswer(new SpecifiedValuesAnswer<>("bar"));
        boolean res = checkString_allSame(s);
        //should fail
        Assertions.assertFalse(res);

        when(s.getString()).thenAnswer(new SpecifiedValuesAnswer<>("foo")); //1 "foo" should be enough
        res = checkString_allSame(s);
        Assertions.assertTrue(res);
    }


    public interface BaseInteger {
        Integer getInteger();
    }

    private static boolean checkInteger(BaseInteger i) {
        Integer v = i.getInteger();
        return v == 42;
    }

    @Test
    public void testBasicInteger() {

        BaseInteger i = mock(BaseInteger.class);
        when(i.getInteger()).thenAnswer(new SpecifiedValuesAnswer<>(7));
        boolean res = checkInteger(i);
        Assertions.assertFalse(res);

        when(i.getInteger()).thenAnswer(new SpecifiedValuesAnswer<Integer>()); //note: here it is important to specify <Integer>
        res = checkInteger(i);
        Assertions.assertFalse(res); //still should fail, as default is 0

        when(i.getInteger()).thenAnswer(new SpecifiedValuesAnswer<>(42));
        res = checkInteger(i);
        Assertions.assertTrue(res);
    }

    public interface BaseInt {
        int getInt();
    }

    private static boolean checkInt(BaseInt i) {
        int v = i.getInt();
        return v == 42;
    }

    @Test
    public void testBasicInt() {

        BaseInt i = mock(BaseInt.class);
        when(i.getInt()).thenAnswer(new SpecifiedValuesAnswer<>(7));
        boolean res = checkInt(i);
        Assertions.assertFalse(res);

        when(i.getInt()).thenAnswer(new SpecifiedValuesAnswer<Integer>()); //note: here it is important to specify <Integer>
        res = checkInt(i);
        Assertions.assertFalse(res); //still should fail, as default is 0

        when(i.getInt()).thenAnswer(new SpecifiedValuesAnswer<>(42));
        res = checkInt(i);
        Assertions.assertTrue(res);
    }
}