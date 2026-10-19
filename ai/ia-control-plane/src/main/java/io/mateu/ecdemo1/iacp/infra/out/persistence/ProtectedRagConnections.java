package io.mateu.ecdemo1.iacp.infra.out.persistence;

import io.mateu.ecdemo1.iacp.application.out.crypto.SecretCipher;
import org.springframework.stereotype.Component;

/** New writes are encrypted; legacy URLs remain readable and are upgraded on their next save. */
@Component
public final class ProtectedRagConnections {
    private static final String PREFIX = "encrypted:v1:";
    private final SecretCipher cipher;
    public ProtectedRagConnections(SecretCipher cipher) { this.cipher = cipher; }
    public String protect(String connection) {
        if (connection == null || connection.isBlank()) return connection;
        return PREFIX + cipher.encrypt(connection);
    }
    public String reveal(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        return cipher.decrypt(stored.substring(PREFIX.length()));
    }
}
