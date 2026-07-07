/* dcParseArgs - Java library to simplify args[] handling
 *
 * Copyright (C) 2008 Roland Koller <roland@devcity.de>
 *
 * Licensed under the GNU Lesser General Public License, version 3.0 or later.
 * Vendored (repackaged) from SF110 project 90_dcparseargs as an EvoObj test fixture.
 */
package com.examples.with.different.packagename.objectconstruction.parseargs.arguments;

public class ParameterArgument extends KeyArgument {

	String value;

	public ParameterArgument(int position, String key, String value) {
		super(position, key);
		if (value == null) throw new NullPointerException();
		if (value.length() == 0) throw new IllegalArgumentException("Value cannot be an empty string.");
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
