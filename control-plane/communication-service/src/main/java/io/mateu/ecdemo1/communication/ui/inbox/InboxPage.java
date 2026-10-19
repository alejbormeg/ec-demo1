package io.mateu.ecdemo1.communication.ui.inbox;

import io.mateu.ecdemo1.communication.inbox.Inbox;
import io.mateu.ecdemo1.communication.store.InboxItem;
import io.mateu.ecdemo1.uicommons.paging.Paging;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Trigger;
import io.mateu.uidl.annotations.TriggerType;
import io.mateu.uidl.data.ColumnAction;
import io.mateu.uidl.data.ColumnActionGroup;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Listing;
import io.mateu.uidl.interfaces.Searchable;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What waits for me: the notifications and tasks that are mine — by name or by one of my roles — and
 * that nobody has resolved yet, newest
 * first. Clicking a row opens what it says in full; each one links to where it is resolved, and
 * resolving it there takes it off this list.
 *
 * <p>Seen is mine alone and resolves nothing: opening an item's link marks it, and so does "Mark
 * as seen" on the selected rows. What I have seen stays here, marked Seen, until it is
 * resolved — the badge in the shells counts only what I have not.
 */
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Title("Inbox")
@Trigger(type = TriggerType.OnCustomEvent, actionId = "search", eventName = InboxPage.SEEN)
public class InboxPage implements Listing<InboxRow>, Searchable {

    /** Dispatched after marking something seen, so the listing searches again and shows it Seen. */
    static final String SEEN = "inbox-seen";

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("Europe/Madrid"));

    final Inbox inbox;

    @Override
    public ListingData<InboxRow> search(SearchRequest request, HttpRequest httpRequest) {
        var text = request.searchText() == null ? "" : request.searchText().trim().toLowerCase();
        var seenAt = inbox.seenAtBy(Caller.username(httpRequest));
        var seen = seenAt.keySet();
        var rows = inbox.openFor(Caller.roles(httpRequest), Caller.username(httpRequest)).stream()
                .filter(i -> text.isEmpty() || (i.title + " " + i.body + " " + i.hotelCode + " " + i.type).toLowerCase().contains(text))
                .sorted(order(seenAt))
                .map(i -> row(i, inbox.linkOf(i), seen))
                .toList();
        return Paging.page(rows, request);
    }

    /**
     * What I have not seen first, the oldest first — it has waited longest; then what I have seen,
     * the most recently seen first.
     */
    static Comparator<InboxItem> order(Map<String, Instant> seenAt) {
        Comparator<InboxItem> unseenFirst = Comparator.comparing(i -> seenAt.containsKey(i.id));
        return unseenFirst.thenComparing((a, b) -> {
            var aSeen = seenAt.get(a.id);
            var bSeen = seenAt.get(b.id);
            if (aSeen == null) {
                return Comparator.nullsLast(Comparator.<Instant>naturalOrder()).compare(a.createdAt, b.createdAt);
            }
            return bSeen.compareTo(aSeen);
        });
    }

    static InboxRow row(InboxItem i, String link, Set<String> seen) {
        var open = link == null || link.isBlank() ? new ColumnAction[0] : new ColumnAction[] {new ColumnAction("open", "Open")};
        return new InboxRow(i.id, i.createdAt == null ? "" : WHEN.format(i.createdAt), kind(i), i.hotelCode, i.title, detailOf(i),
                seen.contains(i.id), new ColumnActionGroup(open));
    }

    static Status kind(InboxItem i) {
        return InboxItem.Kind.TASK.name().equals(i.kind) ? new Status(StatusType.INFO, "Task")
                : i.urgent ? new Status(StatusType.DANGER, "Urgent") : new Status(StatusType.WARNING, "To do");
    }

    @Override
    public boolean selectionEnabled() {
        return true;
    }

    /**
     * The row's Open: marks it seen and goes where it is resolved.
     *
     * <p>Only an item the listing would show this person: the clicked id comes from the request, and
     * an id typed into it is not a row they were shown — another role's item opens nothing.
     */
    public Object open(HttpRequest httpRequest) {
        var item = inbox.find(clickedId(httpRequest))
                .filter(i -> Inbox.visibleTo(i, Caller.roles(httpRequest), Caller.username(httpRequest)))
                .orElse(null);
        if (item == null) {
            return null;
        }
        var link = inbox.linkOf(item);
        if (link == null || link.isBlank()) {
            return null;
        }
        inbox.markSeen(List.of(item.id), Caller.username(httpRequest));
        // A task's link is a path, so it opens in the console the inbox is open in: the forms engine's
        // page for that task answers on both (see CommunicationProperties.Inbox).
        return List.of(UICommand.navigateTo(destination(link, origin(httpRequest))), UICommand.dispatchEvent(SEEN));
    }

    /**
     * Where Open goes. The shell opens an absolute URL in a new tab and navigates a path in this one, so
     * a link into the console the person is in becomes its path — the page they came to resolve opens
     * right here — and a link into the other console stays absolute, in a tab of its own.
     *
     * <p>Each console is served twice, by the Vaadin shell and by the Redwood one, under the same paths:
     * ec1 and rw.ec1 are the data plane, console.ec1 and rw-console.ec1 the control plane. A notice keeps
     * the link it was written with (the Vaadin host), so a link into either twin of the console the
     * person is in opens here too — they stay in the shell they chose.
     */
    static String destination(String link, String origin) {
        if (origin == null || origin.isBlank()) {
            return link;
        }
        try {
            var target = java.net.URI.create(link);
            var here = java.net.URI.create(origin);
            if (target.getHost() == null || here.getHost() == null
                    || !console(target.getHost()).equals(console(here.getHost()))) {
                return link;
            }
            var path = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath();
            return path + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery())
                    + (target.getRawFragment() == null ? "" : "#" + target.getRawFragment());
        } catch (IllegalArgumentException e) {
            return link;
        }
    }

    /** A host with its shell's prefix taken off: rw.ec1… and ec1…, rw-console.ec1… and console.ec1… are one console. */
    static String console(String host) {
        var h = host.toLowerCase(java.util.Locale.ROOT);
        return h.startsWith("rw.") || h.startsWith("rw-") ? h.substring(3) : h;
    }

    /** The console the request comes from: the browser's Origin, or the host the gateway forwarded. */
    static String origin(HttpRequest httpRequest) {
        var origin = httpRequest.getHeaderValue("Origin");
        if (origin != null && !origin.isBlank()) {
            return origin;
        }
        var host = httpRequest.getHeaderValue("X-Forwarded-Host");
        return host == null || host.isBlank() ? null : "https://" + host;
    }

    /** Marks the selected rows seen. The work is in handleAction, like every action of this listing. */
    @ListToolbarButton
    @Label("Mark as seen")
    public void markAsSeen() {
    }

    @Override
    public boolean supportsAction(String actionId) {
        return "markAsSeen".equals(actionId) || Listing.super.supportsAction(actionId);
    }

    @Override
    public Object handleAction(String actionId, HttpRequest httpRequest) {
        if ("markAsSeen".equals(actionId)) {
            var ids = httpRequest.getListOfMaps("crud_selected_items").stream()
                    .map(row -> row.get("id")).filter(Objects::nonNull).map(String::valueOf).toList();
            inbox.markSeen(ids, Caller.username(httpRequest));
            return UICommand.dispatchEvent(SEEN);
        }
        return Listing.super.handleAction(actionId, httpRequest);
    }

    /** The id of the row an action was clicked on: the whole row in {@code _clickedRow}, or its id. */
    static String clickedId(HttpRequest httpRequest) {
        var parameters = httpRequest.runActionRq().parameters();
        if (parameters == null) {
            return null;
        }
        if (parameters.get("_clickedRow") instanceof Map<?, ?> row && row.get("id") != null) {
            return String.valueOf(row.get("id"));
        }
        var direct = parameters.get("id");
        return direct == null ? null : String.valueOf(direct);
    }

    /** The row's detail: the whole title first — the column cuts it — and then what it says. */
    static String detailOf(io.mateu.ecdemo1.communication.store.InboxItem i) {
        var title = i.title == null ? "" : i.title;
        var body = i.body == null ? "" : i.body;
        return title.isBlank() ? body : body.isBlank() ? title : title + "\n\n" + body;
    }
}
