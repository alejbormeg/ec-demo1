package io.mateu.ecdemo1.mdm.consolidation;

import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.ecdemo1.mdm.salesforce.SalesforceClient;
import io.mateu.ecdemo1.mdm.store.ConsolidationRepository;
import io.mateu.ecdemo1.mdm.store.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inbox of «Ciclo de Limpieza» (HLA CRM-MDM): a contact carrying an MDM id left Salesforce, and
 * this finds out why. The event cannot say — before the delete, a merge's survivor is not yet on
 * the record — so the absorbed contact is read back, deleted, where {@code MasterRecordId} now
 * names the survivor. The event and the poll both land here; whichever comes second finds the work
 * done.
 *
 * <p>The event is published before the delete, so the absorbed contact may not read as deleted yet;
 * and Salesforce may not answer. Either way the event's replay id is already kept, and the poll —
 * the net — runs once a day: the merge is kept as pending, here, and tried again on a short tick, a
 * few times. Kept in memory: a restart runs the poll two minutes in, which finds whatever was pending
 * and has been deleted since.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Consolidations {

    /** How many times a merge is tried before it is left to the daily poll: the first, and the tick's. */
    static final int ATTEMPTS = 10;

    final SalesforceClient salesforce;
    final Survivorship survivorship;
    final ConsolidationRepository consolidations;
    final CustomerRepository customers;
    /** The merges heard but not applied yet, by absorbed MDM id: tried again on {@link #retryPending()}. */
    final Map<String, Pending> pending = new LinkedHashMap<>();

    /** A merge to try again: its absorbed contact, how it was heard, and how many times it was tried. */
    record Pending(String absorbedContactId, String via, int attempts) {
    }

    /**
     * A contact carrying an MDM id left Salesforce. Applied now if it can be; if not — the contact does
     * not read as deleted yet, or the work failed — kept as pending, and a failure is still thrown, so
     * whoever heard it logs it.
     */
    public synchronized void received(String absorbedMdmId, String absorbedContactId, String via) {
        attempt(absorbedMdmId, absorbedContactId, via, 0);
    }

    /**
     * The merges that could not be applied when heard: tried again, while Salesforce is available,
     * until they go or have been tried {@link #ATTEMPTS} times — then they are left to the daily poll
     * (a contact restored from the recycle bin never reads as deleted, and every try is an API call).
     */
    @Scheduled(fixedDelayString = "${mdm.refresh-tick:60s}")
    public synchronized void retryPending() {
        if (pending.isEmpty() || !salesforce.available()) {
            return;
        }
        for (var entry : List.copyOf(pending.entrySet())) {
            try {
                attempt(entry.getKey(), entry.getValue().absorbedContactId(), entry.getValue().via(), entry.getValue().attempts());
            } catch (RuntimeException e) {
                log.debug("{} not consolidated yet: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /** Applies it, or keeps it as pending with one more attempt — until {@link #ATTEMPTS}. */
    void attempt(String absorbedMdmId, String absorbedContactId, String via, int attempts) {
        boolean done;
        try {
            done = apply(absorbedMdmId, absorbedContactId, via);
        } catch (RuntimeException e) {
            later(absorbedMdmId, absorbedContactId, via, attempts + 1);
            throw e;
        }
        if (done) {
            pending.remove(absorbedMdmId);
        } else {
            later(absorbedMdmId, absorbedContactId, via, attempts + 1);
        }
    }

    void later(String absorbedMdmId, String absorbedContactId, String via, int attempts) {
        if (attempts >= ATTEMPTS) {
            pending.remove(absorbedMdmId);
            log.warn("{} ({}) not consolidated after {} attempts; left to the daily poll", absorbedMdmId, absorbedContactId, attempts);
            return;
        }
        pending.put(absorbedMdmId, new Pending(absorbedContactId, via, attempts));
    }

    /** Whether there is nothing more to do for it: applied, already applied, or not this MDM's. */
    boolean apply(String absorbedMdmId, String absorbedContactId, String via) {
        if (absorbedMdmId == null || absorbedMdmId.isBlank()) {
            return true;
        }
        if (!customers.existsById(absorbedMdmId)) {
            // Another MDM's customer: environments can share an org, and each hears every merge.
            log.debug("{} is not a customer of this MDM; ignored", absorbedMdmId);
            return true;
        }
        var known = consolidations.findById(absorbedMdmId);
        if (known.isPresent() && known.get().appliedAt != null) {
            log.debug("{} already consolidated (seen again via {})", absorbedMdmId, via);
            return true;
        }
        var absorbed = salesforce.contact(absorbedContactId).orElse(null);
        if (absorbed == null || !absorbed.path("IsDeleted").asBoolean(false)) {
            // Not deleted yet — the event goes out before the delete — or restored from the recycle bin,
            // or never gone: looked at again a few times, then nothing to do.
            log.info("{} ({}) is not deleted in Salesforce yet; looked at again later", absorbedMdmId, absorbedContactId);
            return false;
        }
        var masterId = text(absorbed, "MasterRecordId");
        if (masterId == null) {
            survivorship.removed(absorbedMdmId, absorbedContactId, via);
            return true;
        }
        var master = salesforce.contact(masterId).orElseThrow(() -> new IllegalStateException("No contact " + masterId));
        var survivorMdmId = text(master, "MDM_Id__c");
        if (survivorMdmId == null) {
            // Merged into a contact someone made in Salesforce by hand, which is nobody in the MDM yet:
            // it becomes this customer — the absorbed one lives on, as that contact.
            salesforce.assignMdmId(masterId, absorbedMdmId);
            survivorship.adopted(absorbedMdmId, absorbedContactId, masterId, master, via);
            return true;
        }
        if (!customers.existsById(survivorMdmId)) {
            // Merged into another environment's customer: this MDM cannot make it an alias of a
            // customer it does not have. Left as it is, and said.
            log.warn("{} was merged into {}, which is not a customer of this MDM; not applied", absorbedMdmId, survivorMdmId);
            return true;
        }
        survivorship.merged(survivorMdmId, absorbedMdmId, masterId, absorbedContactId, master, via);
        return true;
    }

    static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
