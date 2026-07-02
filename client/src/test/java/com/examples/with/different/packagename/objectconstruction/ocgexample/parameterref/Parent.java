package com.examples.with.different.packagename.objectconstruction.ocgexample.parameterref;

public class Parent {
	private Child child = new Child();
	
	public Grandchild getGrandchild() {
		return child.grandchild;
	}
	
	public void method(int value) {
		if ((this.child.grandchild.integer != value) && (this.child.grandchild.greatGrandchild.integer != value)) {
			System.currentTimeMillis();
		}
	}
}
