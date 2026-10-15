package io.mateu.ecdemo1.iacp.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The row behind an {@code Llm}.
 *
 * <p>{@code credential} holds base64 of IV+ciphertext and never plaintext — see
 * {@code AesGcmSecretCipher}. It is deliberately excluded from {@code toString}: Lombok's
 * generated one is how a secret ends up in a log line nobody meant to write.
 */
@Entity
@Table(name = "llm")
@Getter
@Setter
@NoArgsConstructor
public class LlmEntity {

    @Id
    String id;
    @Column(nullable = false)
    String name;
    @Column(nullable = false)
    String provider;
    @Column(nullable = false)
    String model;
    String baseUrl;
    Double temperature;
    Integer maxTokens;
    String priceCurrency;
    Double inputPricePerMillion;
    Double outputPricePerMillion;
    @Column(length = 4096)
    String credential;
    boolean enabled;
    LocalDateTime created;

    @Override
    public String toString() {
        return "LlmEntity[" + id + " " + provider + "/" + model
                + (credential != null && !credential.isBlank() ? " credential=set" : " credential=unset") + "]";
    }
}
