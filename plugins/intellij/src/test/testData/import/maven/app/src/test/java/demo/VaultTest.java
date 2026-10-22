package demo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VaultTest {
    @Test
    public void negative() {
        Vault vault = new Vault();
        int result = vault.sign(-1);
        assertEquals(-1, result);
    }
}
