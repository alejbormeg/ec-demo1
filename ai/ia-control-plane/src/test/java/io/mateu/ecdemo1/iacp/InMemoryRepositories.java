package io.mateu.ecdemo1.iacp;

import io.mateu.ecdemo1.iacp.application.out.crypto.SecretCipher;
import io.mateu.ecdemo1.iacp.application.out.repository.AgentRepository;
import io.mateu.ecdemo1.iacp.application.out.repository.LlmRepository;
import io.mateu.ecdemo1.iacp.application.out.repository.McpRepository;
import io.mateu.ecdemo1.iacp.application.out.repository.RagRepository;
import io.mateu.ecdemo1.iacp.application.out.repository.Repository;
import io.mateu.ecdemo1.iacp.application.out.repository.RouteRepository;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.Agent;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.vo.AgentId;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.Llm;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.Credential;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.ModelName;
import io.mateu.ecdemo1.iacp.domain.aggregates.mcp.Mcp;
import io.mateu.ecdemo1.iacp.domain.aggregates.mcp.vo.McpId;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.Rag;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagId;
import io.mateu.ecdemo1.iacp.domain.aggregates.route.Route;
import io.mateu.ecdemo1.iacp.domain.aggregates.route.vo.RouteId;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** The catalogues' repositories as maps, for tests that exercise the use cases without a database. */
public final class InMemoryRepositories {

    private InMemoryRepositories() {}

    public static class InMemory<T, ID> implements Repository<T, ID> {
        final Map<ID, T> rows = new LinkedHashMap<>();
        final Function<T, ID> idOf;

        InMemory(Function<T, ID> idOf) {
            this.idOf = idOf;
        }

        @Override public T save(T aggregate) { rows.put(idOf.apply(aggregate), aggregate); return aggregate; }
        @Override public Optional<T> findById(ID id) { return Optional.ofNullable(rows.get(id)); }
        @Override public List<T> findAll() { return new ArrayList<>(rows.values()); }
        @Override public void deleteAllById(List<ID> ids) { ids.forEach(rows::remove); }
        @Override public boolean existsById(ID id) { return rows.containsKey(id); }
    }

    public static class Agents extends InMemory<Agent, AgentId> implements AgentRepository {
        public Agents() { super(Agent::getId); }
    }

    public static class Llms extends InMemory<Llm, LlmId> implements LlmRepository {
        public Llms() { super(Llm::getId); }
    }

    public static class Mcps extends InMemory<Mcp, McpId> implements McpRepository {
        public Mcps() { super(Mcp::getId); }
    }

    public static class Rags extends InMemory<Rag, RagId> implements RagRepository {
        public Rags() { super(Rag::getId); }
    }

    public static class Routes extends InMemory<Route, RouteId> implements RouteRepository {
        public Routes() { super(Route::getId); }

        @Override
        public List<Route> findEnabledOrderedByPriority() {
            return rows.values().stream().filter(Route::isUsable)
                    .sorted(Comparator.comparingInt(Route::getPriority)).toList();
        }
    }

    /** "Encrypts" by prefixing; enough to tell a decrypted key from a stored one. */
    public static final SecretCipher CIPHER = new SecretCipher() {
        @Override public String encrypt(String plainText) { return "enc:" + plainText; }
        @Override public String decrypt(String cipherText) {
            return cipherText == null ? null : cipherText.replaceFirst("^enc:", "");
        }
    };

    /** An Anthropic LLM with a key: usable. */
    public static Llm usableLlm(String id) {
        var llm = Llm.of(new LlmId(id), new Name(id), LlmProvider.ANTHROPIC, new ModelName("claude"), null, null, null);
        llm.replaceCredential(new Credential("enc:key"));
        return llm;
    }
}
