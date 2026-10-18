package io.mateu.ecdemo1.mdm.consolidation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.mateu.ecdemo1.mdm.salesforce.SalesforceClient;
import io.mateu.ecdemo1.mdm.store.ConsolidationRepository;
import io.mateu.ecdemo1.mdm.store.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A merge heard from Salesforce that cannot be applied at once — the absorbed contact does not read as
 * deleted yet, or the work fails — is not left to the daily poll: it is kept as pending and tried
 * again on the short tick, a bounded number of times.
 */
class ConsolidationsTest {

    static final String ABSORBED = "C-ABSORBED";
    static final String CONTACT = "003000000000001AAA";
    static final String MASTER = "003000000000002AAA";

    final SalesforceClient salesforce = mock(SalesforceClient.class);
    final Survivorship survivorship = mock(Survivorship.class);
    final ConsolidationRepository consolidationRepository = mock(ConsolidationRepository.class);
    final CustomerRepository customers = mock(CustomerRepository.class);
    final Consolidations consolidations = new Consolidations(salesforce, survivorship, consolidationRepository, customers);

    @BeforeEach
    void setUp() {
        when(salesforce.available()).thenReturn(true);
        when(customers.existsById(anyString())).thenReturn(true);
        when(consolidationRepository.findById(anyString())).thenReturn(Optional.empty());
        when(salesforce.contact(MASTER)).thenReturn(Optional.of(contact(false, null, "C-SURVIVOR")));
    }

    static JsonNode contact(boolean deleted, String masterRecordId, String mdmId) {
        var node = JsonNodeFactory.instance.objectNode();
        node.put("IsDeleted", deleted);
        if (masterRecordId != null) {
            node.put("MasterRecordId", masterRecordId);
        }
        if (mdmId != null) {
            node.put("MDM_Id__c", mdmId);
        }
        return node;
    }

    @Test
    void aContactNotDeletedYetIsAppliedOnTheNextTick() {
        when(salesforce.contact(CONTACT)).thenReturn(Optional.of(contact(false, null, ABSORBED)),
                Optional.of(contact(true, MASTER, ABSORBED)));

        consolidations.received(ABSORBED, CONTACT, "EVENT");
        verify(survivorship, never()).merged(any(), any(), any(), any(), any(), any());
        assertThat(consolidations.pending).containsKey(ABSORBED);

        consolidations.retryPending();

        verify(survivorship).merged("C-SURVIVOR", ABSORBED, MASTER, CONTACT, contact(false, null, "C-SURVIVOR"), "EVENT");
        assertThat(consolidations.pending).isEmpty();
    }

    @Test
    void aFailureIsThrown_andTriedAgainOnTheNextTick() {
        when(salesforce.contact(CONTACT)).thenReturn(Optional.of(contact(true, MASTER, ABSORBED)));
        doThrow(new IllegalStateException("database down")).doNothing()
                .when(survivorship).merged(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> consolidations.received(ABSORBED, CONTACT, "EVENT")).hasMessage("database down");
        assertThat(consolidations.pending).containsKey(ABSORBED);

        consolidations.retryPending();

        verify(survivorship, times(2)).merged(any(), any(), any(), any(), any(), any());
        assertThat(consolidations.pending).isEmpty();
    }

    @Test
    void nothingIsTriedWhileSalesforceIsUnavailable() {
        when(salesforce.contact(CONTACT)).thenReturn(Optional.of(contact(false, null, ABSORBED)));
        consolidations.received(ABSORBED, CONTACT, "EVENT");
        when(salesforce.available()).thenReturn(false);

        consolidations.retryPending();

        verify(salesforce, times(1)).contact(CONTACT);
        assertThat(consolidations.pending).containsKey(ABSORBED);
    }

    @Test
    void aContactThatNeverReadsAsDeletedIsLeftToThePollAfterTheLastAttempt() {
        when(salesforce.contact(CONTACT)).thenReturn(Optional.of(contact(false, null, ABSORBED)));

        consolidations.received(ABSORBED, CONTACT, "EVENT");
        for (int i = 0; i < Consolidations.ATTEMPTS + 5; i++) {
            consolidations.retryPending();
        }

        verify(salesforce, times(Consolidations.ATTEMPTS)).contact(CONTACT);
        assertThat(consolidations.pending).isEmpty();
    }
}
