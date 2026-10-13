package io.mateu.ecdemo1.pmsintegration.worker;

import io.mateu.ecdemo1.integration.model.partner.Partner;
import io.mateu.ecdemo1.integration.model.partner.PartnerType;
import io.mateu.ecdemo1.pmsintegration.clients.IntegrationClients;
import io.mateu.ecdemo1.pmsintegration.config.OhipProperties;
import io.mateu.ecdemo1.pmsintegration.frontoffice.StayProjection;
import io.mateu.ecdemo1.pmsintegration.ohip.PmsTransientException;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.api.Cancellations;
import io.mateu.workflow.worker.api.TaskDispatcher;
import io.mateu.workflow.worker.api.TaskRegistry;
import io.mateu.workflow.worker.api.TaskTracing;
import io.mateu.workflow.worker.api.TaskReplySink;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The PMS adapter's steps, run through the engine's worker runtime as the contracts they serve: the
 * variables they read, the ones they answer with, the reason a failure gives — and the retry watch
 * told of each.
 */
class PmsTasksTest {

    final IntegrationClients integration = mock(IntegrationClients.class);
    final StayProjection stays = mock(StayProjection.class);
    final RetryWatch watch = mock(RetryWatch.class);
    final io.mateu.ecdemo1.pmsintegration.ohip.OperaProfiles profiles = mock(io.mateu.ecdemo1.pmsintegration.ohip.OperaProfiles.class);
    final io.mateu.ecdemo1.pmsintegration.connections.Connections connections = mock(io.mateu.ecdemo1.pmsintegration.connections.Connections.class);
    final TaskHandlers handlers = new TaskHandlers(integration, profiles, null, null, connections, null,
            new OhipProperties("ECDEMO1", "UDFN01", "CASH", Duration.ofSeconds(2), "", false, false, null, null), stays, null);
    final PmsTasks tasks = new PmsTasks();
    final RecordingSink sink = new RecordingSink();
    final TaskDispatcher dispatcher = new TaskDispatcher(new TaskRegistry(List.of(
            tasks.ensureGuestProfileTask(handlers, watch), tasks.upsertReservationTask(handlers, watch),
            tasks.cancelReservationTask(handlers, watch), tasks.ensurePartnerProfileTask(handlers, watch),
            tasks.projectStayTask(handlers, watch))),
            sink, Cancellations.NONE, false, TaskTracing.NOOP);

    static class RecordingSink implements TaskReplySink {
        final List<String> replies = new ArrayList<>();

        @Override
        public void running(TaskExecutionRequested task) {
        }

        @Override
        public void completed(TaskExecutionRequested task, List<Variable> variables) {
            replies.add("COMPLETED " + variables.stream().map(v -> v.name() + "=" + v.value()).toList());
        }

        @Override
        public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) {
            replies.add("ERROR " + reason);
        }
    }

    static TaskExecutionRequested task(String definition, String step, String taskId, Variable... variables) {
        return new TaskExecutionRequested("TE-1", "PROC-1", definition, step, taskId, List.of(variables));
    }

    static Partner partner(String pmsProfileId) {
        return new Partner("NORDTRAVEL", PartnerType.values()[0], "Nord Travel", null, null, null, null, null, true, 4,
                pmsProfileId, pmsProfileId == null ? null : "Agent");
    }

    void operaIsAt(String hotel) {
        when(connections.integrations()).thenReturn(List.of(new io.mateu.ecdemo1.integration.model.integration.IntegrationView(
                "I-1", hotel, hotel, io.mateu.ecdemo1.integration.model.integration.IntegrationStatus.ACTIVE)));
    }

    @Test
    void aChangedPartnerTheErpKnowsInOperaHasItsProfileBroughtUpToDate() {
        var partner = partner("16120699");
        when(integration.partner("NORDTRAVEL")).thenReturn(partner);
        operaIsAt("XMAR");
        when(profiles.updatePartner("XMAR", "16120699", partner, "Agent")).thenReturn(true);

        dispatcher.dispatch(task("proyectar-interlocutor", "ensure-partner-profile", "ensure-partner-profile@1",
                new Variable("partnerCode", "NORDTRAVEL"), new Variable("processKey", "k"),
                new Variable("definitionId", "proyectar-interlocutor"), new Variable("prepareOutcome", "OK")));

        assertThat(sink.replies).containsExactly(
                "COMPLETED [profileOutcome=OK, pmsProfileIds=16120699, pmsProfileType=Agent]");
    }

    @Test
    void everyContractHasItsHandlerOnThePmsTopic() {
        var registry = new TaskRegistry(List.of(tasks.ensureGuestProfileTask(handlers, watch),
                tasks.upsertReservationTask(handlers, watch), tasks.cancelReservationTask(handlers, watch),
                tasks.ensurePartnerProfileTask(handlers, watch), tasks.projectStayTask(handlers, watch)));

        assertThat(registry.refs()).containsExactly("ensure-guest-profile@1", "upsert-reservation@1",
                "cancel-reservation@1", "ensure-partner-profile@1", "project-stay@1");
        assertThat(tasks.projectStayTask(handlers, watch).topic()).isEqualTo("pms-integration");
    }

    @Test
    void aPartnerTheErpAlreadyKnowsInOperaAnswersItsProfile() {
        when(integration.partner("NORDTRAVEL")).thenReturn(partner("16120699"));
        operaIsAt("XMAR");

        dispatcher.dispatch(task("proyectar-interlocutor", "ensure-partner-profile", "ensure-partner-profile@1",
                new Variable("partnerCode", "NORDTRAVEL"), new Variable("processKey", "k"),
                new Variable("definitionId", "proyectar-interlocutor"), new Variable("prepareOutcome", "OK")));

        assertThat(sink.replies).containsExactly(
                "COMPLETED [profileOutcome=STALE, pmsProfileIds=16120699, pmsProfileType=Agent]");
        verify(watch).succeeded("PROC-1", "ensure-partner-profile");
    }

    @Test
    void aStepThatWaitsAnswersOnlyItsOutcome() {
        when(integration.partner("NORDTRAVEL")).thenReturn(partner(null));
        when(integration.resolve(any(), any())).thenReturn(new IntegrationClients.Resolved(List.of(),
                List.of(io.mateu.ecdemo1.integration.model.mapping.Cause.missingPartner("NORDTRAVEL"))));

        dispatcher.dispatch(task("proyectar-interlocutor", "ensure-partner-profile", "ensure-partner-profile@1",
                new Variable("partnerCode", "NORDTRAVEL"), new Variable("processKey", "k"),
                new Variable("definitionId", "proyectar-interlocutor"), new Variable("version", "4"),
                new Variable("prepareOutcome", "OK")));

        assertThat(sink.replies).containsExactly("COMPLETED [profileOutcome=WAIT]");
        // The successor is started with the relaunch variables, not the outcomes of this run.
        verify(integration).await(eq("k"), eq("proyectar-interlocutor"), eq(null), eq("NORDTRAVEL"),
                eq(List.of(new Variable("definitionId", "proyectar-interlocutor"), new Variable("processKey", "k"),
                        new Variable("partnerCode", "NORDTRAVEL"), new Variable("version", "4"))), any());
    }

    @Test
    void theStayIsProjectedByItsPropertyAndId() {
        dispatcher.dispatch(task("proyectar-estancia", "project-stay", "project-stay@1",
                new Variable("pmsHotelCode", "XMAR"), new Variable("pmsReservationId", "39484608")));

        verify(stays).project("XMAR", "39484608");
        assertThat(sink.replies).containsExactly("COMPLETED []");
    }

    @Test
    void operaNotAnsweringFailsWithItsMessageAndIsWatched() {
        doThrow(new PmsTransientException("OHIP 503 on GET /rsv")).when(stays).project(anyString(), anyString());

        dispatcher.dispatch(task("proyectar-estancia", "project-stay", "project-stay@1",
                new Variable("pmsHotelCode", "XMAR"), new Variable("pmsReservationId", "39484608")));

        assertThat(sink.replies).containsExactly("ERROR OHIP 503 on GET /rsv");
        verify(watch).failed("PROC-1", "project-stay", "XMAR", "XMAR/39484608", "OHIP 503 on GET /rsv");
    }

    @Test
    void aStepWithoutWhatItNeedsFailsWithTheReasonItAlwaysGave() {
        dispatcher.dispatch(task("proyectar-estancia", "project-stay", "project-stay@1",
                new Variable("pmsHotelCode", "XMAR")));

        assertThat(sink.replies).containsExactly(
                "ERROR java.lang.IllegalArgumentException: Step project-stay needs the variable pmsReservationId");
    }

    @Test
    void anIdThatLooksLikeANumberReachesTheHandlerAsItIs() {
        dispatcher.dispatch(task("proyectar-estancia", "project-stay", "project-stay@1",
                new Variable("pmsHotelCode", "XMAR"), new Variable("pmsReservationId", "12E45")));

        verify(stays).project("XMAR", "12E45");
    }

}
