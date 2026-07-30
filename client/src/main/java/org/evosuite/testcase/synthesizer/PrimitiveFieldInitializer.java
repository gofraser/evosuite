package org.evosuite.testcase.synthesizer;

import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.BytePrimitiveStatement;
import org.evosuite.testcase.statements.numeric.CharPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.DoublePrimitiveStatement;
import org.evosuite.testcase.statements.numeric.FloatPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.LongPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.NumericalPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.ShortPrimitiveStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericField;

public class PrimitiveFieldInitializer extends FieldInitializer{
	private final Integer integerHint;

	public PrimitiveFieldInitializer() {
		this(null);
	}

	public PrimitiveFieldInitializer(Integer integerHint) {
		this.integerHint = integerHint;
	}

	public VariableReference assignField(TestFactory testFactory, TestCase test, String fieldType, GenericField genericField,
			int insertionPosition, FieldReference fieldVar, boolean allowNullValue) throws ConstructionFailedException {
		try {
			PrimitiveStatement<?> primStatement = createNewPrimitiveStatement(test, fieldType);
			if (integerHint == null || !setIntegerHint(primStatement, fieldType, integerHint)) {
				primStatement.randomize();
			}
			VariableReference varRef = testFactory.addPrimitive(test, primStatement, insertionPosition + 1);
			return varRef;
		} catch (ConstructionFailedException e) {
			e.printStackTrace();
		}
		return null;
		
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	static boolean setIntegerHint(
			PrimitiveStatement<?> statement, String fieldType, int value) {
		if (statement == null) {
			return false;
		}
		Object converted;
		switch (fieldType) {
		case "B":
		case "byte":
			converted = (byte) value;
			break;
		case "C":
		case "char":
			converted = (char) value;
			break;
		case "S":
		case "short":
			converted = (short) value;
			break;
		case "I":
		case "int":
			converted = value;
			break;
		case "J":
		case "long":
			converted = (long) value;
			break;
		case "F":
		case "float":
			converted = (float) value;
			break;
		case "D":
		case "double":
			converted = (double) value;
			break;
		default:
			return false;
		}
		((PrimitiveStatement) statement).setValue(converted);
		return true;
	}
	
	@SuppressWarnings("rawtypes")
	private NumericalPrimitiveStatement createNewPrimitiveStatement(TestCase test, String desc) {
		switch (desc) {
		case "Z":
		case "boolean":
			return new BooleanPrimitiveStatement(test);
		case "B":
		case "byte":
			return new BytePrimitiveStatement(test);
		case "C":
		case "char":
			return new CharPrimitiveStatement(test);
		case "S":
		case "short":
			return new ShortPrimitiveStatement(test);
		case "I":
		case "int":
			return new IntPrimitiveStatement(test);
		case "J":
		case "long":
			return new LongPrimitiveStatement(test);
		case "F":
		case "float":
			return new FloatPrimitiveStatement(test);
		case "D":
		case "double":
			return new DoublePrimitiveStatement(test);
		default:
			return null;
		}
	}
}
