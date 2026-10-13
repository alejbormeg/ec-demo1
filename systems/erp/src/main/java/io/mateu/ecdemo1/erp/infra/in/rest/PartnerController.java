package io.mateu.ecdemo1.erp.infra.in.rest;

import io.mateu.ecdemo1.erp.application.out.PartnerRepository;
import io.mateu.ecdemo1.erp.application.usecases.PartnerService;
import io.mateu.ecdemo1.erp.domain.partner.PartnerDetails;
import io.mateu.ecdemo1.erp.domain.partner.PartnerType;
import io.mateu.ecdemo1.erp.domain.partner.PmsProfile;
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
 * The master's API. {@code GET /partners/{code}} is how the integration reads a partner again after
 * an event says it changed.
 */
@RestController
@RequestMapping("/partners")
@RequiredArgsConstructor
public class PartnerController {

    final PartnerRepository repository;
    final PartnerService service;

    public record NewPartner(String code, PartnerDetails details) {
    }

    @GetMapping
    @Operation(summary = "List partners by code; the search text matches the code or the name, and type "
            + "(TravelAgent, TourOperator, OnlineAgency, Company) narrows them to those types, the active ones")
    public List<PartnerDto> list(@RequestParam(required = false) String search,
                                 @RequestParam(required = false) List<PartnerType> type,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "100") int size) {
        if (type != null && !type.isEmpty()) {
            return repository.search(search, type, null, true, page, size).partners().stream()
                    .map(PartnerDto::of).toList();
        }
        return repository.search(search, page, size).stream().map(PartnerDto::of).toList();
    }

    @GetMapping("/{code}")
    @Operation(summary = "Read a partner")
    public PartnerDto get(@PathVariable String code) {
        return repository.findByCode(code).map(PartnerDto::of)
                .orElseThrow(() -> new NoSuchElementException("Partner not found: " + code));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a partner")
    public void create(@RequestBody NewPartner partner) {
        service.create(partner.code(), partner.details());
    }

    @PutMapping("/{code}")
    @Operation(summary = "Replace a partner's details")
    public void update(@PathVariable String code, @RequestBody PartnerDetails details) {
        service.update(code, details);
    }

    @PutMapping("/{code}/pms-profile")
    @Operation(summary = "Record which profile the partner is in the PMS (Opera); announces nothing, projects nothing")
    public void recordPmsProfile(@PathVariable String code, @RequestBody PmsProfile profile) {
        service.recordPmsProfile(code, profile);
    }

    @PostMapping("/{code}/resync")
    @Operation(summary = "Announce the partner again, unchanged, so the integration projects it once more")
    public void resync(@PathVariable String code) {
        service.resync(code);
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
