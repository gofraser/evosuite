package bytecode_tests;

public class CompatibilityFixture {

    public int testControlFlow(int x, int y) {
        int result = 0;
        
        // Loop and conditionals
        for (int i = 0; i < x; i++) {
            if (i % 2 == 0) {
                result += skip(i);
            } else {
                result -= y;
            }
            
            // Nested conditional
            if (result > 100) {
                break;
            }
        }
        
        // Switch block
        switch (x % 3) {
            case 0:
                result *= 2;
                break;
            case 1:
                result *= 3;
                break;
            default:
                break;
        }
        
        return result;
    }
    
    // Private method to test invocation
    private int skip(int v) {
        return v * 5;
    }
    
    // Exception handling
    public int testExceptionHandling(String s) {
        try {
            int length = s.length();
            if (length > 10) {
                throw new IllegalArgumentException("Too long");
            }
            return length;
        } catch (NullPointerException e) {
            return -1;
        } catch (IllegalArgumentException e) {
            return -2;
        } finally {
            // finally block to ensure comprehensive bytecode
            System.out.println("Finished testExceptionHandling");
        }
    }
}
