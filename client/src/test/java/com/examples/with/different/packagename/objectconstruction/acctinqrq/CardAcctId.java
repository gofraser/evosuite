package com.examples.with.different.packagename.objectconstruction.acctinqrq;

import java.io.Serializable;

public class CardAcctId extends CardAcctId_Type implements Serializable {

	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (obj instanceof CardAcctId) {
			CardAcctId temp = (CardAcctId) obj;
			return true;
		} else {
			return false;
		}
	}
}