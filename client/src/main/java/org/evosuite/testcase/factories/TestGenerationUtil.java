package org.evosuite.testcase.factories;

import java.util.Iterator;
import java.util.List;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericMethod;

/**
 * Helpers for method-level targeting (EvoObj, plan M1). Every method here is a
 * no-op unless {@code Properties.TARGET_METHOD} is set, so default whole-class
 * generation is unaffected.
 */
public class TestGenerationUtil {

    /**
     * Position of the first statement that calls {@code Properties.TARGET_METHOD}
     * within {@code [0, lastMutatableStatement]}, or -1 if none.
     */
    public static int getTargetMethodPosition(TestCase test, int lastMutatableStatement) {
        for (int position = 0; position <= lastMutatableStatement; position++) {
            Statement stat = test.getStatement(position);
            if (stat instanceof MethodStatement) {
                MethodStatement mStat = (MethodStatement) stat;
                String mSig = mStat.getMethodName() + MethodUtil.getSignature(mStat.getMethod().getMethod());
                if (mSig.equals(Properties.TARGET_METHOD)) {
                    return position;
                }
            }
        }

        return -1;
    }

    /**
     * True if the test calls {@code Properties.TARGET_METHOD} two or more times.
     */
    public static boolean checkTwiceTargetMethodInvocation(TestCase test) {
        int count = 0;
        if (!Properties.TARGET_METHOD.isEmpty()) {
            for (int i = 0; i < test.size(); i++) {
                Statement statement = test.getStatement(i);
                if (statement instanceof MethodStatement) {
                    MethodStatement mStatement = (MethodStatement) statement;
                    GenericMethod method = mStatement.getMethod();
                    String sig = method.getName() + MethodUtil.getSignature(method.getMethod());
                    if (sig.equals(Properties.TARGET_METHOD)) {
                        count++;
                        if (count >= 2) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * True if the test already contains a call to {@code Properties.TARGET_METHOD}.
     */
    public static boolean checkTargetMethodInvocation(TestCase test) {
        boolean stopInsertion = false;
        if (!Properties.TARGET_METHOD.isEmpty()) {
            for (int i = 0; i < test.size(); i++) {
                Statement statement = test.getStatement(i);
                if (statement instanceof MethodStatement) {
                    MethodStatement mStatement = (MethodStatement) statement;
                    GenericMethod method = mStatement.getMethod();
                    String sig = method.getName() + MethodUtil.getSignature(method.getMethod());
                    if (sig.equals(Properties.TARGET_METHOD)) {
                        stopInsertion = true;
                        break;
                    }
                }
            }
        }

        return stopInsertion;
    }

    /**
     * True if {@code obj} is the target method (class + descriptor match).
     */
    public static boolean isTargetMethod(GenericAccessibleObject<?> obj) {
        if (obj instanceof GenericMethod) {
            GenericMethod method = (GenericMethod) obj;
            if (!Properties.TARGET_METHOD.isEmpty()) {
                String methodString = method.getDeclaringClass().getName() + "#" + method.getNameWithDescriptor();
                String targetMethodString = Properties.TARGET_CLASS + "#" + Properties.TARGET_METHOD;
                if (methodString.equals(targetMethodString)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Remove the target method from a candidate call list (used to enforce
     * exactly one target-method call per test).
     */
    public static void filterTargetMethod(TestCase test, List<GenericAccessibleObject<?>> candidateTestMethods) {
        if (!Properties.TARGET_METHOD.isEmpty()) {
            Iterator<GenericAccessibleObject<?>> iter = candidateTestMethods.iterator();
            while (iter.hasNext()) {
                GenericAccessibleObject<?> obj = iter.next();
                if (isTargetMethod(obj)) {
                    iter.remove();
                }
            }
        }
    }
}
