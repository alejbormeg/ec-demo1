package io.mateu.ecdemo1.iacp.infra.out.workflow;

import io.mateu.ecdemo1.iacp.application.out.crypto.SecretCipher;
import io.mateu.ecdemo1.iacp.application.out.workflow.WorkflowCatalogueStore;
import io.mateu.ecdemo1.iacp.application.out.usage.UsageEvent;
import io.mateu.ecdemo1.iacp.application.out.usage.UsageLog;
import io.mateu.ecdemo1.iacp.infra.out.persistence.JsonDocuments;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Repository
@Transactional
public class JpaWorkflowCatalogueStore implements WorkflowCatalogueStore {
    private final EntityManager em;
    private final SecretCipher cipher;
    private final UsageLog usage;
    public JpaWorkflowCatalogueStore(EntityManager em, SecretCipher cipher, UsageLog usage) {
        this.em = em; this.cipher = cipher; this.usage = usage;
    }
    @Override public Optional<Publication> publication() { return read("publication", Publication.class); }
    @Override public void publish(Publication value) {
        em.createNativeQuery("insert into workflow_agent_document(id,ciphertext) values (:id,:value) "
                + "on conflict (id) do update set ciphertext = excluded.ciphertext")
                .setParameter("id", "publication").setParameter("value", cipher.encrypt(JsonDocuments.write(value)))
                .executeUpdate();
    }
    @Override public Optional<Snapshot> find(String invocationId) { return read(key(invocationId), Snapshot.class); }
    @Override public Snapshot freeze(Snapshot value) {
        em.createNativeQuery("insert into workflow_agent_document(id,ciphertext) values (:id,:value) "
                + "on conflict (id) do nothing")
                .setParameter("id", key(value.snapshotId()))
                .setParameter("value", cipher.encrypt(JsonDocuments.write(value))).executeUpdate();
        return find(value.snapshotId()).orElseThrow();
    }
    private String key(String invocationId) { return "snapshot:" + digest(invocationId); }
    private <T> Optional<T> read(String key, Class<T> type) {
        var values = em.createNativeQuery("select ciphertext from workflow_agent_document where id = :id", String.class)
                .setParameter("id", key).getResultList();
        if (values.isEmpty()) return Optional.empty();
        return Optional.of(JsonDocuments.read(cipher.decrypt((String) values.getFirst()), type));
    }
    @Override public String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JsonDocuments.write(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Override public boolean recordUsage(String eventId, String eventDigest, String agentId,
            String llmId, String model, int inputTokens, int outputTokens, String actor, String invocationId) {
        int inserted = em.createNativeQuery("insert into workflow_agent_usage_receipt(id,digest) values (:id,:digest) "
                + "on conflict (id) do nothing").setParameter("id", eventId)
                .setParameter("digest", eventDigest).executeUpdate();
        if (inserted == 0) {
            var stored = (String) em.createNativeQuery("select digest from workflow_agent_usage_receipt where id = :id", String.class)
                    .setParameter("id", eventId).getSingleResult();
            if (!stored.equals(eventDigest)) throw new IllegalArgumentException("Usage round already recorded with different values");
            return false;
        }
        usage.append(new UsageEvent(agentId, llmId, model, inputTokens, outputTokens,
                inputTokens + outputTokens, actor, actor, List.of("workflow-agent-executor"), null, invocationId));
        return true;
    }
}
