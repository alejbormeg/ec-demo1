package io.mateu.ecdemo1.iacp.domain.aggregates.llm;

import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.Credential;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmUsability;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.ModelName;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.Pricing;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.SamplingOptions;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Enabled;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Time;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A model this deployment may call, and the credential that pays for it.
 *
 * <p>The credential is the reason this aggregate is not a plain record. It arrives already
 * encrypted — see {@link Credential} — and it can only be <em>replaced</em>, never read back:
 * {@link #update} deliberately does not take one, so the ordinary edit path cannot blank a working
 * key by saving a form that rendered it as empty. That is not a hypothetical; it is the default
 * behaviour of every write-only field that is wired through an ordinary update.
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Llm extends AggregateRoot {

    LlmId id;
    Name name;
    LlmProvider provider;
    ModelName model;
    /** Null for a provider's own endpoint; set for OpenAI-compatible servers. */
    String baseUrl;
    SamplingOptions sampling;
    /** What a million tokens cost; see {@link Pricing}. Never null — {@link Pricing#none()}. */
    Pricing pricing;
    Credential credential;
    Enabled enabled;
    Time created;

    public static Llm of(LlmId id, Name name, LlmProvider provider, ModelName model,
                         String baseUrl, SamplingOptions sampling, Pricing pricing) {
        var llm = new Llm();
        llm.id = id;
        llm.name = name;
        llm.provider = provider;
        llm.model = model;
        llm.baseUrl = baseUrl;
        llm.sampling = sampling != null ? sampling : SamplingOptions.defaults();
        llm.pricing = pricing != null ? pricing : Pricing.none();
        // Catalogued before it is usable, on purpose: an entry can be written now and given a key
        // by someone who has one. `isUsable` is what keeps it out of an agent's config until then.
        llm.credential = Credential.none();
        llm.enabled = Enabled.yes();
        llm.created = new Time(LocalDateTime.now());
        return llm;
    }

    /** Everything about an LLM except its credential. */
    public void update(Name name, LlmProvider provider, ModelName model,
                       String baseUrl, SamplingOptions sampling, Pricing pricing, Enabled enabled) {
        this.name = name;
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl;
        this.sampling = sampling;
        this.pricing = pricing != null ? pricing : Pricing.none();
        this.enabled = enabled;
    }

    /**
     * Replaces the credential with an already-encrypted one. The only way it ever changes.
     */
    public void replaceCredential(Credential credential) {
        this.credential = credential;
    }

    /**
     * Why an agent may or may not be served this LLM.
     *
     * <p>The reason, not a boolean: the two callers that needed one used to derive it themselves
     * and both got it wrong for the same entry. See {@link LlmUsability}.
     */
    public LlmUsability usability() {
        return LlmUsability.of(enabled.value(), provider, credential.isSet());
    }

    /** Whether an agent may be served this LLM. The reason is in {@link #usability()}. */
    public boolean isUsable() {
        return usability().isUsable();
    }
}
