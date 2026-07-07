/* dcParseArgs - Java library to simplify args[] handling
 *
 * Copyright (C) 2008 Roland Koller <roland@devcity.de>
 *
 * Licensed under the GNU Lesser General Public License, version 3.0 or later.
 * Vendored (repackaged) from SF110 project 90_dcparseargs as an EvoObj test
 * fixture. Its branches read elements of two same-typed String[] fields
 * (args, innerArgs), which is what drives EvoObj's array-element object-
 * construction synthesis and reproduced the invalid-template defect.
 */
package com.examples.with.different.packagename.objectconstruction.parseargs;

import java.util.ArrayList;
import java.util.List;

import com.examples.with.different.packagename.objectconstruction.parseargs.arguments.ParameterArgument;
import com.examples.with.different.packagename.objectconstruction.parseargs.arguments.StringArgument;
import com.examples.with.different.packagename.objectconstruction.parseargs.arguments.SwitchArgument;

public class ArgsParser {

	public static final String SHORT_ARGUMENT_INDICATOR = "-";

	public static final String LONG_ARGUMENT_INDICATOR = "--";

	private String[] args; /* A copy of the original args array */

	private String[] innerArgs; /* A working copy where every already parsed part is deleted or overwritten with null */

	public ArgsParser() {
		super();
	}

	public ArgsParser(String[] args) {
		super();
		this.setArgs(args);
	}

	public int getArgsCount() {
		return args.length;
	}

	public String[] getArgs() {
		return args;
	}

	public void setArgs(String[] args) {
		if (args == null)
			throw new NullPointerException("args[] cannot be null.");

		this.args = new String[args.length];
		this.innerArgs = new String[args.length];

		for (int i = 0; i < args.length; ++i) {
			this.args[i] = args[i];
			this.innerArgs[i] = args[i];
		}
	}

	public int getArgsLeftCount() {
		int result = 0;
		for (int i = 0; i < innerArgs.length; ++i)
			if (innerArgs[i] != null)
				result++;
		return result;
	}

	public SwitchArgument parseSwitchArgument(String key) {
		boolean isLongKey = (key.length() > 1);

		if (isLongKey) {
			String searchFor = LONG_ARGUMENT_INDICATOR + key;
			for (int i = 0; i < args.length; ++i) {
				if (innerArgs[i] != null) {
					if (innerArgs[i].equals(searchFor)) {
						innerArgs[i] = null;
						return new SwitchArgument(i, key, true);
					}
				}
			}
		} else {
			int index;
			for (int i = 0; i < args.length; ++i) {
				if (innerArgs[i] != null) {
					if (innerArgs[i].length() > 1 && innerArgs[i].startsWith(SHORT_ARGUMENT_INDICATOR)
							&& (!innerArgs[i].startsWith(LONG_ARGUMENT_INDICATOR))) {
						index = innerArgs[i].lastIndexOf(key);
						if (index > 0) {
							String before = innerArgs[i].substring(0, index);
							String after = innerArgs[i].substring(index + 1, innerArgs[i].length());
							innerArgs[i] = before + after;
							if (innerArgs[i].replace(SHORT_ARGUMENT_INDICATOR, " ").trim().length() == 0)
								innerArgs[i] = null;
							return new SwitchArgument(i, key, true);
						}
					}
				}
			}
		}
		return new SwitchArgument(-1, key, false);
	}

	public ParameterArgument parseParameterArgument(String key) {
		boolean isLongKey = (key.length() > 1);

		if (isLongKey) {
			String searchFor = LONG_ARGUMENT_INDICATOR + key;
			for (int i = 0; i < innerArgs.length; ++i) {
				if (innerArgs[i] != null) {
					if (innerArgs[i].equals(searchFor)) {
						if ((innerArgs.length > (i + 1)) && (innerArgs[i] != null) && (innerArgs[i + 1] != null)) {
							String value = innerArgs[i + 1];
							innerArgs[i] = null;
							innerArgs[i + 1] = null;
							return new ParameterArgument(i, key, value);
						} else {
							return null;
						}
					}
				}
			}
		} else {

			int index;
			for (int i = 0; i < args.length; ++i) {
				if (innerArgs[i] != null) {
					if (innerArgs[i].length() > 1 && (innerArgs[i].startsWith(SHORT_ARGUMENT_INDICATOR))
							&& (!innerArgs[i].startsWith(LONG_ARGUMENT_INDICATOR))) {
						index = innerArgs[i].indexOf(key);
						if (index > 0) {
							if ((index + 1) == innerArgs[i].length()) {
								if ((innerArgs.length > (i + 1)) && (innerArgs[i] != null) && (innerArgs[i + 1]) != null) {
									String before = innerArgs[i].substring(0, index);
									String after = innerArgs[i].substring(index + 1, innerArgs[i].length());
									innerArgs[i] = before + after;
									if (innerArgs[i].replace(SHORT_ARGUMENT_INDICATOR, " ").trim().length() == 0)
										innerArgs[i] = null;
									String value = innerArgs[i + 1];
									innerArgs[i + 1] = null;
									return new ParameterArgument(i, key, value);
								} else {
									return null;
								}
							} else {
								String before = innerArgs[i].substring(0, index);
								String value = innerArgs[i].substring(index + 1, innerArgs[i].length());
								innerArgs[i] = before;
								if (innerArgs[i].replace(SHORT_ARGUMENT_INDICATOR, " ").trim().length() == 0)
									innerArgs[i] = null;
								return new ParameterArgument(i, key, value);
							}
						}
					}
				}
			}

		}

		/* nothing found... */
		return null;
	}

	public List<StringArgument> parseStringArgument() {
		List<StringArgument> result = new ArrayList<StringArgument>();
		for (int i = 0; i < args.length; ++i) {
			if (innerArgs[i] != null) {
				result.add(new StringArgument(i, innerArgs[i]));
				innerArgs[i] = null;
			}
		}
		return result;
	}

}
