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
package org.evosuite;

import org.evosuite.instrumentation.RegexDistance;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestRegexInstantiation {

    @Test
    public void testFailingSeedingRegex() {
        String FAILING_PATTERN = "^[_A-Za-z0-9-]+(\\.[_A-Za-z0-9-]+)*@[A-Za-z0-9]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";
        String matchingInstance = RegexDistance.getRegexInstance(FAILING_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(FAILING_PATTERN);
        assertTrue(matchingInstance.matches(FAILING_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(FAILING_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, FAILING_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, FAILING_PATTERN) > 0);
    }


    @Test
    public void testEmailRegex() {
        String EMAIL_PATTERN =
                "^[_A-Za-z0-9-\\+]+(\\.[_A-Za-z0-9-]+)*@"
                        + "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";

        String matchingInstance = RegexDistance.getRegexInstance(EMAIL_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(EMAIL_PATTERN);
        assertTrue(matchingInstance.matches(EMAIL_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(EMAIL_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, EMAIL_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, EMAIL_PATTERN) > 0);

    }

    @Test
    public void testUsernameRegex() {
        String USERNAME_PATTERN = "^[a-z0-9_-]{3,15}$";

        String matchingInstance = RegexDistance.getRegexInstance(USERNAME_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(USERNAME_PATTERN);
        assertTrue(matchingInstance.matches(USERNAME_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(USERNAME_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, USERNAME_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, USERNAME_PATTERN) > 0);

    }

    @Disabled
    @Test
    // dk.brics.automaton runs out of memory on this one, need to investigate why
    public void testPasswordRegex() {
        String PASSWORD_PATTERN =
                "((?=.*\\d)(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%]).{6,20})";

        String matchingInstance = RegexDistance.getRegexInstance(PASSWORD_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(PASSWORD_PATTERN);
        assertTrue(matchingInstance.matches(PASSWORD_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(PASSWORD_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, PASSWORD_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, PASSWORD_PATTERN) > 0);

    }

    @Test
    public void testHexRegex() {
        String HEX_PATTERN = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$";

        String matchingInstance = RegexDistance.getRegexInstance(HEX_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(HEX_PATTERN);
        assertTrue(matchingInstance.matches(HEX_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(HEX_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, HEX_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, HEX_PATTERN) > 0);

    }

    @Test
    public void testImageRegex() {
        // TODO: If the whole thing is in parenthesis, then $ will not be removed
        String IMAGE_PATTERN =
                "[^\\s]+(\\.(?i)(jpg|png|gif|bmp))$";

        String matchingInstance = RegexDistance.getRegexInstance(IMAGE_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(IMAGE_PATTERN);
        assertTrue(matchingInstance.matches(IMAGE_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(IMAGE_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, IMAGE_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, IMAGE_PATTERN) > 0);

    }

    @Test
    public void testIPRegex() {
        String IPADDRESS_PATTERN =
                "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";

        String matchingInstance = RegexDistance.getRegexInstance(IPADDRESS_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(IPADDRESS_PATTERN);
        assertTrue(matchingInstance.matches(IPADDRESS_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(IPADDRESS_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, IPADDRESS_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, IPADDRESS_PATTERN) > 0);

    }

    @Test
    public void testTime12Regex() {
        String TIME12HOURS_PATTERN =
                "(1[012]|[1-9]):[0-5][0-9](\\s)?(?i)(am|pm)";

        String matchingInstance = RegexDistance.getRegexInstance(TIME12HOURS_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(TIME12HOURS_PATTERN);
        assertTrue(matchingInstance.matches(TIME12HOURS_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(TIME12HOURS_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, TIME12HOURS_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, TIME12HOURS_PATTERN) > 0);

    }

    @Test
    public void testTime24Regex() {
        String TIME24HOURS_PATTERN =
                "([01]?[0-9]|2[0-3]):[0-5][0-9]";

        String matchingInstance = RegexDistance.getRegexInstance(TIME24HOURS_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(TIME24HOURS_PATTERN);
        assertTrue(matchingInstance.matches(TIME24HOURS_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(TIME24HOURS_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, TIME24HOURS_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, TIME24HOURS_PATTERN) > 0);

    }

    @Test
    public void testDateRegex() {
        String DATE_PATTERN =
                "(0?[1-9]|[12][0-9]|3[01])/(0?[1-9]|1[012])/((19|20)\\d\\d)";

        String matchingInstance = RegexDistance.getRegexInstance(DATE_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(DATE_PATTERN);
        assertTrue(matchingInstance.matches(DATE_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(DATE_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, DATE_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, DATE_PATTERN) > 0);

    }

    @Test
    public void testHTMLRegex() {
        String HTML_TAG_PATTERN = "<(\"[^\"]*\"|'[^']*'|[^'\">])*>";

        String matchingInstance = RegexDistance.getRegexInstance(HTML_TAG_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(HTML_TAG_PATTERN);
        assertTrue(matchingInstance.matches(HTML_TAG_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(HTML_TAG_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, HTML_TAG_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, HTML_TAG_PATTERN) > 0);

    }

    @Test
    public void testHTMLARegex() {
        String HTML_A_TAG_PATTERN = "(?i)<a([^>]+)>(.+?)</a>";

        String matchingInstance = RegexDistance.getRegexInstance(HTML_A_TAG_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(HTML_A_TAG_PATTERN);
        assertTrue(matchingInstance.matches(HTML_A_TAG_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(HTML_A_TAG_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, HTML_A_TAG_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, HTML_A_TAG_PATTERN) > 0);

    }

    @Disabled
    @Test
    // I don't know what's happening in here
    public void testHTMLHrefRegex() {
        String HTML_A_HREF_TAG_PATTERN =
                "\\s*(?i)href\\s*=\\s*\".*\"";

        String matchingInstance = RegexDistance.getRegexInstance(HTML_A_HREF_TAG_PATTERN);
        String nonMatchingInstance = RegexDistance.getNonMatchingRegexInstance(HTML_A_HREF_TAG_PATTERN);
        assertTrue(matchingInstance.matches(HTML_A_HREF_TAG_PATTERN), "String does not match regex: " + matchingInstance);
        assertFalse(nonMatchingInstance.matches(HTML_A_HREF_TAG_PATTERN), "String matches regex but shouldn't: " + nonMatchingInstance);
        assertEquals(0, RegexDistance.getDistance(matchingInstance, HTML_A_HREF_TAG_PATTERN));
        assertTrue(RegexDistance.getDistance(nonMatchingInstance, HTML_A_HREF_TAG_PATTERN) > 0);

    }

}
