package io.mateu.ecdemo1.iacp.infra.out.persistence;

import io.mateu.ecdemo1.iacp.application.out.repository.RagRepository;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.Rag;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagId;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagKind;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Enabled;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Time;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RagDBRepository implements RagRepository {

    final RagEntityRepository entities;
    final ProtectedRagConnections connections;

    @Override
    public Rag save(Rag rag) {
        var e = entities.findById(rag.getId().value()).orElseGet(RagEntity::new);
        e.setId(rag.getId().value());
        e.setName(rag.getName().value());
        e.setKind(rag.getKind().name());
        e.setConnectionUrl(connections.protect(rag.getConnectionUrl()));
        e.setCollectionName(rag.getCollection());
        e.setEmbeddingLlmId(rag.getEmbeddingLlmId().value());
        e.setTopK(rag.getTopK());
        e.setDescription(rag.getDescription());
        e.setEnabled(rag.getEnabled().value());
        e.setCreated(rag.getCreated().value());
        entities.save(e);
        return rag;
    }

    @Override
    public Optional<Rag> findById(RagId id) {
        return entities.findById(id.value()).map(this::toDomain);
    }

    @Override
    public List<Rag> findAll() {
        return entities.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public void deleteAllById(List<RagId> ids) {
        entities.deleteAllById(ids.stream().map(RagId::value).toList());
    }

    @Override
    public boolean existsById(RagId id) {
        return entities.existsById(id.value());
    }

    Rag toDomain(RagEntity e) {
        return new Rag(
                new RagId(e.getId()),
                new Name(e.getName()),
                RagKind.valueOf(e.getKind()),
                connections.reveal(e.getConnectionUrl()),
                e.getCollectionName(),
                new LlmId(e.getEmbeddingLlmId()),
                e.getTopK(),
                e.getDescription(),
                new Enabled(e.isEnabled()),
                new Time(e.getCreated()));
    }
}
