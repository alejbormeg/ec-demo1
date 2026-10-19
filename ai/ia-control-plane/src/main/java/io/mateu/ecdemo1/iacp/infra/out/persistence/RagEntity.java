package io.mateu.ecdemo1.iacp.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * {@code connectionUrl} may carry a password in its userinfo, which is why it is not in
 * {@code toString} — the same reason as the LLM credential, one step less obvious.
 */
@Entity
@Table(name = "rag")
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "connectionUrl")
public class RagEntity {

    @Id
    String id;
    @Column(nullable = false)
    String name;
    @Column(nullable = false)
    String kind;
    @Column(columnDefinition = "text")
    String connectionUrl;
    String collectionName;
    String embeddingLlmId;
    // Named explicitly: the default strategy turns `topK` into `topk`, not `top_k`, because the
    // K has no lowercase letter after it to break on. Harmless until someone writes the obvious
    // column name in a query and gets "column top_k does not exist".
    @Column(name = "top_k")
    int topK;
    @Column(length = 2048)
    String description;
    boolean enabled;
    LocalDateTime created;
}
