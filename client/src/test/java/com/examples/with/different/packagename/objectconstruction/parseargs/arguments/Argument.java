/* dcParseArgs - Java library to simplify args[] handling
 *
 * Copyright (C) 2008 Roland Koller <roland@devcity.de>
 *
 * Licensed under the GNU Lesser General Public License, version 3.0 or later.
 * Vendored (repackaged) from SF110 project 90_dcparseargs as an EvoObj test
 * fixture: ArgsParser reads two String[] fields on its branches, which drives
 * the object-construction array-element synthesis path.
 */
package com.examples.with.different.packagename.objectconstruction.parseargs.arguments;

public class Argument implements Comparable<Argument> {

	int position;

	public Argument(int position) {
		this.position = position;
	}

	public int getPosition() {
		return position;
	}

	public int compareTo(Argument arg0) {
		return position - arg0.getPosition();
	}

}
