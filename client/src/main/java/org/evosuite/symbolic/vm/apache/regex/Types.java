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
package org.evosuite.symbolic.vm.apache.regex;

import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.Perl5Matcher;
import org.objectweb.asm.Type;

/**
 * Constants for Types related to Apache ORO Regex.
 *
 * @author galeotti
 */
public interface Types {

    /**
     * String type.
     */
    Type STR_TYPE = Type.getType(String.class);

    /**
     * Pattern type.
     */
    Type PATTERN_TYPE = Type.getType(Pattern.class);

    /**
     * Descriptor for a method taking two strings and returning a boolean.
     */
    String STR_STR_TO_BOOLEAN = Type.getMethodDescriptor(
            Type.BOOLEAN_TYPE, STR_TYPE, PATTERN_TYPE);

    /**
     * Name of the Perl5Matcher class.
     */
    String ORG_APACHE_ORO_TEXT_REGEX_PERL5MATCHER = Perl5Matcher.class
            .getName().replace('.', '/');

}
