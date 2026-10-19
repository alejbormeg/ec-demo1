package io.mateu.ecdemo1.pmsintegration.demo;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.ecdemo1.integration.model.integration.IntegrationStatus;
import io.mateu.ecdemo1.integration.model.integration.OhipConnection;
import io.mateu.ecdemo1.pmsintegration.connections.Connections;
import io.mateu.ecdemo1.pmsintegration.ohip.OhipClient;
import io.mateu.ecdemo1.pmsintegration.ohip.PmsRejectedException;
import lombok.extern.slf4j.Slf4j;

/**
 * Task cancel-earlier-runs (process reset-demo): cancels in Opera every reservation the demo wrote —
 * this run's and every earlier one's — and nothing else. Opera is the chain's UAT tenant, shared with
 * the chain's own tests; without this each run left its reservations there, holding rooms.
 *
 * <p>Ours are known by the context of the CRS locator in their external references, which only
 * {@code ReservationPayload} writes: {@code ECDEMO1} (the legacy one), {@code ECDEMO1-<MMddHHmm>} (a
 * reset's), {@code ECLOCAL-<MMddHHmm>} (deploy/local) and {@code EC1-…} (probes). The properties are
 * those of the integrations, which the reset has not emptied yet, and the configured ones — a fresh
 * environment has no integration, and Opera still has what other environments wrote.
 *
 * <p>In-house reservations cannot be cancelled: they are reported, for a person to check out. One that
 * Opera refuses to cancel is reported and the rest go on; Opera not answering fails the step, which the
 * engine retries — nothing is cancelled twice, a cancelled reservation is no longer a candidate.
 * Same as {@code deploy/demo/opera-cancel-ours.py}.
 */
@Slf4j
public class EarlierRuns {

    static final Pattern OURS = Pattern.compile("^(ECDEMO1|ECLOCAL|EC1-)");
    static final List<String> CANCELLABLE = List.of("Reserved", "Requested");
    /** Arrivals this far back and ahead: a search answers at most 1000 rows, so it goes month by month. */
    static final int MONTHS_BACK = 24;
    static final int MONTHS_AHEAD = 36;
    static final int PAGE = 200;

    public record Output(int cancelled, int inHouse, int refused, String summary) {
    }

    record Found(String hotel, String reservationId, String context, String locator, String status) {
    }

    private final OhipClient ohip;
    private final Connections connections;
    private final List<String> properties;
    private final String reasonCode;
    private final Clock clock;

    public EarlierRuns(OhipClient ohip, Connections connections, List<String> properties, String reasonCode, Clock clock) {
        this.ohip = ohip;
        this.connections = connections;
        this.properties = properties == null ? List.of() : properties;
        this.reasonCode = reasonCode;
        this.clock = clock;
    }

    public Output cancel(Consumer<String> progress) {
        int cancelled = 0, inHouse = 0, refused = 0;
        var lines = new ArrayList<String>();
        for (var hotel : properties()) {
            var connection = connections.of(hotel).or(() -> connections.chain(hotel));
            if (connection.isEmpty()) {
                lines.add(hotel + ": no connection, not cleaned");
                continue;
            }
            var found = ours(connection.get(), hotel);
            var todo = found.stream().filter(f -> CANCELLABLE.contains(f.status())).toList();
            progress.accept("%s: %d reservations of earlier runs, %d to cancel".formatted(hotel, found.size(), todo.size()));
            int done = 0;
            for (var f : todo) {
                try {
                    cancel(connection.get(), f);
                    cancelled++;
                } catch (PmsRejectedException e) {
                    refused++;
                    lines.add("%s %s/%s refused: %s".formatted(f.reservationId(), f.context(), f.locator(), e.reason()));
                }
                if (++done % 25 == 0) {
                    progress.accept("%s: %d/%d".formatted(hotel, done, todo.size()));
                }
            }
            for (var f : found) {
                if ("InHouse".equals(f.status())) {
                    inHouse++;
                    lines.add("%s %s/%s in house: check it out by hand".formatted(f.reservationId(), f.context(), f.locator()));
                }
            }
        }
        var summary = "Opera: %d cancelled, %d in house, %d refused".formatted(cancelled, inHouse, refused)
                + (lines.isEmpty() ? "" : " — " + String.join("; ", lines));
        log.info(summary);
        return new Output(cancelled, inHouse, refused, summary);
    }

    /** The integrations' properties, then the configured ones, each once. */
    List<String> properties() {
        var all = new LinkedHashSet<String>();
        for (var i : connections.integrations()) {
            if (i.status() != IntegrationStatus.DECOMMISSIONED && i.pmsHotelCode() != null && !i.pmsHotelCode().isBlank()) {
                all.add(i.pmsHotelCode());
            }
        }
        properties.stream().filter(p -> p != null && !p.isBlank()).forEach(all::add);
        return List.copyOf(all);
    }

    List<Found> ours(OhipConnection connection, String hotel) {
        var found = new java.util.LinkedHashMap<String, Found>();
        var month = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1).minusMonths(MONTHS_BACK);
        for (int m = 0; m < MONTHS_BACK + MONTHS_AHEAD; m++, month = month.plusMonths(1)) {
            var end = month.plusMonths(1).minusDays(1);
            for (int offset = 0; ; ) {
                var page = ohip.getWith(connection, hotel, "/rsv/v1/hotels/{h}/reservations?limit={l}&offset={o}"
                        + "&arrivalStartDate={f}&arrivalEndDate={t}", hotel, PAGE, offset, month, end).body();
                var reservations = page == null ? null : page.path("reservations");
                var infos = reservations == null ? null : reservations.path("reservationInfo");
                if (infos == null || !infos.isArray() || infos.isEmpty()) {
                    break;
                }
                for (var info : infos) {
                    ofOurs(hotel, info).ifPresent(f -> found.put(f.reservationId(), f));
                }
                if (!reservations.path("hasMore").asBoolean(false)) {
                    break;
                }
                offset += infos.size();
            }
        }
        return List.copyOf(found.values());
    }

    static Optional<Found> ofOurs(String hotel, JsonNode info) {
        for (var ref : info.path("externalReferences")) {
            var context = ref.path("idContext").asText("");
            if (OURS.matcher(context).find()) {
                String id = null;
                for (var i : info.path("reservationIdList")) {
                    if ("Reservation".equals(i.path("type").asText())) {
                        id = i.path("id").asText();
                    }
                }
                return id == null ? Optional.empty()
                        : Optional.of(new Found(hotel, id, context, ref.path("id").asText(null),
                        info.path("reservationStatus").asText(null)));
            }
        }
        return Optional.empty();
    }

    /** As the connector cancels one (OperaReservations.cancel): a real tenant wants the reason's description too. */
    void cancel(OhipConnection connection, Found f) {
        var body = Map.of(
                "reason", Map.of("code", reasonCode, "description", "ec-demo1: reset, an earlier run's reservation"),
                "reservations", List.of(Map.of("hotelId", f.hotel(),
                        "reservationIdList", List.of(Map.of("id", f.reservationId(), "type", "Reservation")))));
        ohip.postWith(connection, f.hotel(), "/rsv/v1/hotels/{h}/reservations/{id}/cancellations", body, f.hotel(), f.reservationId());
    }
}
