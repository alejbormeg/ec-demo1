package io.mateu.ecdemo1.mapping.ui.pages;

import io.mateu.ecdemo1.mapping.queries.CauseQueries;
import io.mateu.ecdemo1.mapping.store.CauseStatus;
import io.mateu.ecdemo1.uicommons.paging.DbPaging;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Listing;
import io.mateu.uidl.interfaces.Navigable;
import io.mateu.uidl.interfaces.Searchable;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;

/**
 * What is blocking processes, and how many wait behind each cause — open ones first, oldest first.
 * The count is the point: three thousand reservations behind one missing board code is one line.
 */
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Title("Causes")
public class CausesPage implements Listing<CauseRow>, Searchable, Navigable<CauseViewModel, String> {

    /**
     * The consoles' time, as everywhere else (the journey's, the demo page's, the front office's): the
     * pods run in UTC, and a cause opened at 11:46 read 09:46.
     */
    static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZONE);
    static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZONE);

    /** A moment, in the consoles' time; empty when there is none. */
    static String moment(java.time.Instant at) {
        return at == null ? "" : MOMENT.format(at);
    }

    final CauseQueries causes;
    final ObjectProvider<CauseViewModel> detail;
    final DiscardForm discardForm;

    /** Filtered by the cause's key, ordered (open first, oldest first) and paged by the database. */
    @Override
    public ListingData<CauseRow> search(SearchRequest request, HttpRequest httpRequest) {
        return DbPaging.page(request, p -> causes.page(request.searchText(), p), c -> new CauseRow(c.causeKey,
                c.type.name(), c.hotelCode, CauseRow.reason(c.type, c.description), causes.waitingOn(c.causeKey),
                c.openedAt == null ? "" : WHEN.format(c.openedAt), c.openings,
                c.status == CauseStatus.OPEN ? new Status(StatusType.WARNING, "Open")
                        : new Status(StatusType.SUCCESS, "Resolved")));
    }

    @Override
    public CauseViewModel view(String key, HttpRequest httpRequest) {
        return detail.getObject().load(causes.cause(key).orElseThrow(() -> new NoSuchElementException("Cause " + key + " not found")),
                !redwood(httpRequest));
    }

    /**
     * «Descartar» on a waiting process's row, in a cause's page. A row action is dispatched to the
     * listing the page was opened from — this one — with the cause's state and the clicked row: it
     * opens the cause's discard dialog with that process picked. {@code @Action} because strict action
     * mode (Mateu 3.0-alpha.426) refuses an unmarked method: the id is advertised by the cause's page,
     * not by this listing. It draws no button: only {@code @ListToolbarButton} does on a listing.
     */
    @Action
    public Object discardWaiter(HttpRequest httpRequest) {
        var request = httpRequest.runActionRq();
        var state = request.componentState();
        var causeKey = state == null || state.get("key") == null ? null : String.valueOf(state.get("key"));
        if (causeKey == null) {
            return io.mateu.uidl.data.Message.error("Open the cause to discard its processes");
        }
        return discardForm.dialogFor(causeKey, CauseViewModel.clickedProcess(httpRequest),
                request.route() == null || request.route().isBlank() ? "/mapping/causes/" + causeKey : request.route());
    }

    /**
     * Whether the page is drawn by the Redwood console: its renderer draws a row action inside a
     * form's grid as «[object Object]», so there the rows carry none and discarding is from the
     * toolbar's «Descartar…», which picks the process in the dialog.
     */
    static boolean redwood(HttpRequest httpRequest) {
        for (var header : new String[] {"Origin", "Referer", "X-Forwarded-Host", "Host"}) {
            var value = httpRequest.getHeaderValue(header);
            if (value != null && (value.contains("://rw-") || value.contains("://rw.") || value.startsWith("rw-") || value.startsWith("rw."))) {
                return true;
            }
        }
        return false;
    }
}
