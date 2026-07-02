package com.examples.with.different.packagename.objectconstruction.acctinqrq;

import java.io.Serializable;

public class DepAcctId extends DepAcctId_Type implements Serializable {

	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (obj instanceof DepAcctId) {
			DepAcctId temp = (DepAcctId) obj;
			return true;
		} else {
			return false;
		}
	}
}