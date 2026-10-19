package io.mateu.ecdemo1.integrations.rest;

import io.mateu.ecdemo1.integration.model.integration.IntegrationStatus;
import io.mateu.ecdemo1.integration.model.integration.IntegrationView;
import io.mateu.ecdemo1.integration.model.integration.OhipConnection;
import io.mateu.ecdemo1.integrations.lifecycle.Integrations;
import io.mateu.ecdemo1.integrations.store.BackfillRunRepository;
import io.mateu.ecdemo1.integrations.store.Integration;
import io.mateu.ecdemo1.integrations.store.IntegrationRepository;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * The integrations, over REST. Not routed by the gateway — only the screens are, under
 * {@code /_integrations} — because {@code /connections} hands out the secret the connector needs:
 * it is for the services of the cluster, not for anyone who can reach the console.
 */
@RestController
@RequestMapping("/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    final Integrations lifecycle;
    final IntegrationRepository integrations;
    final BackfillRunRepository runs;
    final io.mateu.ecdemo1.integrations.store.FrontOfficeIntegrationRepository frontOffices;
    final io.mateu.ecdemo1.integrations.frontoffice.FrontOfficeIntegrations frontOfficeLifecycle;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a hotel's integration and start its onboarding")
    public IntegrationDto register(@RequestBody Integrations.Registration registration,
                                   @RequestParam(defaultValue = "api") String by) {
        return dto(lifecycle.register(registration, by));
    }

    @GetMapping
    @Operation(summary = "Every integration, by CRS hotel")
    public List<IntegrationDto> list() {
        return integrations.findAllByOrderByCrsHotelCodeAsc().stream().map(this::dto).toList();
    }

    @GetMapping("/{id}")
    public IntegrationDto get(@PathVariable String id) {
        return dto(lifecycle.find(id));
    }

    @PutMapping("/{id}/connection")
    @Operation(summary = "Change how to reach the Opera property (a blank secret keeps the stored one), and try it")
    public IntegrationDto changeConnection(@PathVariable String id, @RequestBody Integrations.ConnectionChange change,
                                           @RequestParam(defaultValue = "api") String by) {
        return dto(lifecycle.changeConnection(id, change, by));
    }

    @PostMapping("/{id}/verify")
    public IntegrationDto verify(@PathVariable String id, @RequestParam(defaultValue = "api") String by) {
        return dto(lifecycle.verifyNow(id, by));
    }

    @PostMapping("/{id}/partners/import")
    @Operation(summary = "Import the chain's partners from Opera into the ERP, and record which profile each is; nothing is written to Opera")
    public IntegrationDto importPartners(@PathVariable String id, @RequestParam(defaultValue = "api") String by) {
        return dto(lifecycle.importPartners(id, by));
    }

    @PostMapping("/{id}/recheck")
    @Operation(summary = "Look again at what the current gate needs: the catalogue, the partners, the gaps")
    public IntegrationDto recheck(@PathVariable String id, @RequestParam(defaultValue = "api") String by) {
        return dto(lifecycle.recheck(id, by));
    }

    @PostMapping("/{id}/approve-mapping")
    public IntegrationDto approveMapping(@PathVariable String id, @RequestParam String by) {
        return dto(lifecycle.approveMapping(id, by));
    }

    @PostMapping("/{id}/activate")
    public IntegrationDto activate(@PathVariable String id, @RequestParam String by) {
        return dto(lifecycle.activate(id, by));
    }

    @PostMapping("/{id}/pause")
    public IntegrationDto pause(@PathVariable String id, @RequestParam String by) {
        return dto(lifecycle.pause(id, by));
    }

    @PostMapping("/{id}/resume")
    public IntegrationDto resume(@PathVariable String id, @RequestParam String by) {
        return dto(lifecycle.resume(id, by));
    }

    @PostMapping("/{id}/decommission")
    public IntegrationDto decommission(@PathVariable String id, @RequestParam String by) {
        return dto(lifecycle.decommission(id, by));
    }

    @PostMapping("/{id}/backfills")
    @Operation(summary = "Relaunch the backfill of a running integration (F013)")
    public IntegrationDto relaunchBackfill(@PathVariable String id, @RequestParam String by) {
        lifecycle.relaunchBackfill(id, by);
        return dto(lifecycle.find(id));
    }

    // ── for the other services ──────────────────────────────────────────────

    @GetMapping("/hotels/{crsHotelCode}")
    @Operation(summary = "The integration of a CRS hotel: which Opera property, and whether its reservations flow")
    public IntegrationView byHotel(@PathVariable String crsHotelCode) {
        return integrations.findByCrsHotelCode(crsHotelCode).map(Integration::view)
                .orElseThrow(() -> new NoSuchElementException("Hotel %s has no integration".formatted(crsHotelCode)));
    }

    @GetMapping("/views")
    public List<IntegrationView> views() {
        return integrations.findAllByOrderByCrsHotelCodeAsc().stream().map(Integration::view).toList();
    }

    @GetMapping("/connections/{pmsHotelCode}")
    @Operation(summary = "How to reach an Opera property, secret included — for the connector")
    public OhipConnection connection(@PathVariable String pmsHotelCode) {
        return integrations.findFirstByPmsHotelCodeAndStatusNot(pmsHotelCode, IntegrationStatus.DECOMMISSIONED)
                .map(lifecycle::connection)
                // A property only a front office integration reads: the chain's connection, for that property.
                .or(() -> frontOffices.findFirstByPmsHotelCodeAndStatusNot(pmsHotelCode,
                                io.mateu.ecdemo1.integrations.store.FoIntegrationStatus.DECOMMISSIONED)
                        .map(frontOfficeLifecycle::connection))
                .orElseThrow(() -> new NoSuchElementException("No integration reaches Opera property " + pmsHotelCode));
    }

    @GetMapping("/chain-connection")
    @Operation(summary = "The chain's own connection to Opera, secret included — for the connector's demo reset, "
            + "which cleans properties no integration names any more")
    public OhipConnection chainConnection() {
        return lifecycle.chainConnection();
    }

    IntegrationDto dto(Integration i) {
        return IntegrationDto.of(i, runs.findFirstByIntegrationIdOrderByStartedAtDesc(i.id).orElse(null));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
