/* dcParseArgs - Java library to simplify args[] handling
 *
 * Copyright (C) 2008 Roland Koller <roland@devcity.de>
 *
 * Licensed under the GNU Lesser General Public License, version 3.0 or later.
 * Vendored (repackaged) from SF110 project 90_dcparseargs as an EvoObj test fixture.
 */
package com.examples.with.different.packagename.objectconstruction.parseargs.arguments;

public class SwitchArgument extends KeyArgument {

	private boolean isSet;

	public SwitchArgument(int position, String key, boolean isSet) {
		super(position, key);
		this.isSet = isSet;
	}

	public boolean isSet() {
		return isSet;
	}
}
