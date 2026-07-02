package com.examples.with.different.packagename.objectconstruction.ocgexample.arrayelementaccess;

public class Parent {
	private Child child = new Child();
	
	public Child getChild() {
		return child;
	}
	
	public void method() {
		if (this.child.grandchild.integerArray[3] != 0) {
			System.currentTimeMillis();
		}
	}
}
