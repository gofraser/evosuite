/* dcParseArgs - Java library to simplify args[] handling
 *
 * Copyright (C) 2008 Roland Koller <roland@devcity.de>
 *
 * Licensed under the GNU Lesser General Public License, version 3.0 or later.
 * Vendored (repackaged) from SF110 project 90_dcparseargs as an EvoObj test fixture.
 */
package com.examples.with.different.packagename.objectconstruction.parseargs.arguments;

public abstract class KeyArgument extends Argument {

	String key;

	public KeyArgument(int position, String key) {
		super(position);
		if (key == null) throw new NullPointerException();
		if (key.length() == 0) throw new IllegalArgumentException("Key cannot be an empty string.");
		this.key = key;
	}

	public boolean isLongKey() {
		return (key.length() > 1);
	}

	public String getKey() {
		return key;
	}

}
