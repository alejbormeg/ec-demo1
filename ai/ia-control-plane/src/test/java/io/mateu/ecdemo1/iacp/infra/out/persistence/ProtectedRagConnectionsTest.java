package io.mateu.ecdemo1.iacp.infra.out.persistence;

import io.mateu.ecdemo1.iacp.infra.out.crypto.AesGcmSecretCipher;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedRagConnectionsTest {
    @Test void encryptedWritesRoundTripAndLegacyRowsRemainReadable() {
        var protection = new ProtectedRagConnections(new AesGcmSecretCipher(Base64.getEncoder().encodeToString(new byte[32])));
        var url = "jdbc:postgresql://synthetic/db?user=synthetic&password=synthetic-password";
        var encrypted = protection.protect(url);
        assertNotEquals(url, encrypted); assertFalse(encrypted.contains("synthetic-password"));
        assertEquals(url, protection.reveal(encrypted)); assertEquals(url, protection.reveal(url));
        assertNotEquals(encrypted, protection.protect(url)); // Fresh GCM IV on each write.
    }
    @Test void missingCryptoKeyNeverFallsBackToPlaintextOnNewWrite() {
        var protection = new ProtectedRagConnections(new AesGcmSecretCipher(""));
        assertThrows(IllegalStateException.class, () -> protection.protect("jdbc:postgresql://synthetic/db?password=synthetic"));
    }
}
