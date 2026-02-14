package org.evosuite.assertion;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for ChainedInspector.
 */
public class ChainedInspectorTest {

    // A sample class with a method returning a complex object
    public static class Container {
        private final List<String> items = new ArrayList<>();

        public Container() {
            items.add("a");
            items.add("b");
            items.add("c");
        }

        public List<String> getItems() {
            return items;
        }

        public String getName() {
            return "test";
        }

        public int getCount() {
            return items.size();
        }
    }

    @Test
    public void testGetValue_chainsOuterAndInner() throws Exception {
        Method outerMethod = Container.class.getMethod("getItems");
        Method innerMethod = List.class.getMethod("size");

        Inspector innerInspector = new Inspector(List.class, innerMethod);
        ChainedInspector chained = new ChainedInspector(Container.class, outerMethod, innerInspector);

        Container container = new Container();
        Object result = chained.getValue(container);

        assertEquals(3, result);
    }

    @Test
    public void testGetValue_nullIntermediateReturnsNull() throws Exception {
        // A class that returns null from the outer method
        Method outerMethod = NullContainer.class.getMethod("getItems");
        Method innerMethod = List.class.getMethod("size");

        Inspector innerInspector = new Inspector(List.class, innerMethod);
        ChainedInspector chained = new ChainedInspector(NullContainer.class, outerMethod, innerInspector);

        NullContainer container = new NullContainer();
        Object result = chained.getValue(container);

        assertNull(result);
    }

    public static class NullContainer {
        public List<String> getItems() {
            return null;
        }
    }

    @Test
    public void testGetMethodCall_producesChainedName() throws Exception {
        Method outerMethod = Container.class.getMethod("getItems");
        Method innerMethod = List.class.getMethod("size");

        Inspector innerInspector = new Inspector(List.class, innerMethod);
        ChainedInspector chained = new ChainedInspector(Container.class, outerMethod, innerInspector);

        // Should produce "getItems().size" so code gen makes "obj.getItems().size()"
        assertEquals("getItems().size", chained.getMethodCall());
    }

    @Test
    public void testGetReturnType_returnsInnerReturnType() throws Exception {
        Method outerMethod = Container.class.getMethod("getItems");
        Method innerMethod = List.class.getMethod("size");

        Inspector innerInspector = new Inspector(List.class, innerMethod);
        ChainedInspector chained = new ChainedInspector(Container.class, outerMethod, innerInspector);

        assertEquals(int.class, chained.getReturnType());
    }

    @Test
    public void testEquals_sameChainIsEqual() throws Exception {
        Method outerMethod = Container.class.getMethod("getItems");
        Method innerMethod = List.class.getMethod("size");

        Inspector inner1 = new Inspector(List.class, innerMethod);
        Inspector inner2 = new Inspector(List.class, innerMethod);

        ChainedInspector chained1 = new ChainedInspector(Container.class, outerMethod, inner1);
        ChainedInspector chained2 = new ChainedInspector(Container.class, outerMethod, inner2);

        assertEquals(chained1, chained2);
        assertEquals(chained1.hashCode(), chained2.hashCode());
    }

    @Test
    public void testEquals_differentInnerIsNotEqual() throws Exception {
        Method outerMethod = Container.class.getMethod("getItems");
        Method sizeMethod = List.class.getMethod("size");
        Method isEmptyMethod = List.class.getMethod("isEmpty");

        Inspector innerSize = new Inspector(List.class, sizeMethod);
        Inspector innerIsEmpty = new Inspector(List.class, isEmptyMethod);

        ChainedInspector chained1 = new ChainedInspector(Container.class, outerMethod, innerSize);
        ChainedInspector chained2 = new ChainedInspector(Container.class, outerMethod, innerIsEmpty);

        assertNotEquals(chained1, chained2);
    }

    @Test
    public void testEquals_notEqualToPlainInspector() throws Exception {
        Method outerMethod = Container.class.getMethod("getItems");
        Method innerMethod = List.class.getMethod("size");

        Inspector innerInspector = new Inspector(List.class, innerMethod);
        ChainedInspector chained = new ChainedInspector(Container.class, outerMethod, innerInspector);
        Inspector plain = new Inspector(Container.class, outerMethod);

        assertNotEquals(chained, plain);
    }

    @Test
    public void testCodeGeneration_producesCorrectAssertion() throws Exception {
        Method outerMethod = Container.class.getMethod("getItems");
        Method innerMethod = List.class.getMethod("size");

        Inspector innerInspector = new Inspector(List.class, innerMethod);
        ChainedInspector chained = new ChainedInspector(Container.class, outerMethod, innerInspector);

        // Simulate what InspectorAssertion.getCode does
        String sourceVarName = "container0";
        String code = "assertEquals(" + 3 + ", " + sourceVarName + "."
                + chained.getMethodCall() + "());";

        assertEquals("assertEquals(3, container0.getItems().size());", code);
    }

    @Test
    public void testGetOuterMethod() throws Exception {
        Method outerMethod = Container.class.getMethod("getItems");
        Method innerMethod = List.class.getMethod("size");

        Inspector innerInspector = new Inspector(List.class, innerMethod);
        ChainedInspector chained = new ChainedInspector(Container.class, outerMethod, innerInspector);

        assertEquals(outerMethod, chained.getOuterMethod());
    }

    @Test
    public void testGetInnerInspector() throws Exception {
        Method outerMethod = Container.class.getMethod("getItems");
        Method innerMethod = List.class.getMethod("size");

        Inspector innerInspector = new Inspector(List.class, innerMethod);
        ChainedInspector chained = new ChainedInspector(Container.class, outerMethod, innerInspector);

        assertEquals(innerInspector, chained.getInnerInspector());
    }
}
