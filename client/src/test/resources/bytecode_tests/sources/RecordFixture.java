package bytecode_tests;

public record RecordFixture(int id, String name) {
    
    public RecordFixture {
        if (id < 0) {
            throw new IllegalArgumentException("ID cannot be negative");
        }
    }
    
    public String getDisplayName() {
        return name + " (" + id + ")";
    }
}
