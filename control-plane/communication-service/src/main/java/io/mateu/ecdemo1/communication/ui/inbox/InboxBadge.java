package io.mateu.ecdemo1.communication.ui.inbox;

import io.mateu.ecdemo1.communication.inbox.Inbox;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Trigger;
import io.mateu.uidl.annotations.TriggerType;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.State;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.ComponentTreeSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Hydratable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The shells' widget: how many things wait for me, and a way to them. It replaces the forms engine's
 * "my tasks" badge — a task is one of the things that wait, next to the notifications that link to
 * a screen. Refreshed every few seconds.
 *
 * <p>It counts what I have not seen yet — like unread mail, so it says when something new arrived —
 * and says "urgente" only of those. What I have seen is still in the inbox until it is resolved; the
 * link goes there all the same.
 */
@UI("/_inbox/badge")
@Title("")
@Service
@RequiredArgsConstructor
@Trigger(type = TriggerType.OnLoad, actionId = "refresh", timeoutMillis = 10000)
@Trigger(type = TriggerType.OnSuccess, actionId = "refresh", calledActionId = "refresh", timeoutMillis = 10000)
@Action(id = "refresh")
public class InboxBadge implements Hydratable, ComponentTreeSupplier {

    final Inbox inbox;

    String content = "";

    // Public: since Mateu 386 an unmarked method is an action only if it is public.
    public Object refresh() {
        return new State(this);
    }

    @Override
    public void hydrate(HttpRequest httpRequest) {
        var seen = inbox.seenBy(Caller.username(httpRequest));
        var waiting = inbox.openFor(Caller.roles(httpRequest), Caller.username(httpRequest)).stream().filter(i -> !seen.contains(i.id)).toList();
        var urgent = waiting.stream().filter(i -> i.urgent).count();
        content = html(waiting.size(), urgent);
    }

    /** What the badge says, in full: its tooltip and its accessible name. "Bandeja (2 · 1 urgente)". */
    static String label(int waiting, long urgent) {
        if (waiting == 0) {
            return "Bandeja";
        }
        return "Bandeja (" + waiting + (urgent > 0 ? " · " + urgent + (urgent == 1 ? " urgente" : " urgentes") : "") + ")";
    }

    /** Where the badge goes: the inbox's pending items, a route of the inbox's own pod. */
    static final String ROUTE = "/inbox/pending";

    /**
     * The badge as the header's other icon buttons: an icon link with an outline bell in the header's
     * icon colour, and the count as a small pill over the bell's top end — red while something urgent
     * waits. The words are in its tooltip and accessible name, not on the bar.
     *
     * <p>It is HTML because both shells draw it from the same string: Redwood draws a remote header
     * widget from its {@code Text}s alone, so a Mateu button or element would not be there at all.
     * Both sanitise it (Mateu 3.0-alpha.430): the Vaadin shell drops every custom element but
     * {@code <vaadin-icon>} (so no {@code <vaadin-button>} any more) and every handler, and keeps
     * {@code data-*}; the Redwood shell keeps only {@code class}, {@code title}, {@code aria-hidden}
     * and a filtered {@code style}, and its {@code <vaadin-icon>} becomes the Redwood bell. The colours
     * fall back to the header's own there (the {@code --lumo-*} ones are not defined, except the
     * urgent red, which the Redwood header sets to its light danger tone).
     *
     * <p>So it is an {@code <a>} that says where it goes twice, once for each shell. Its
     * {@code onclick} is never run by either: the Redwood renderer reads the {@code navigation-requested}
     * detail out of it (its only form of a header route link), rewrites the link to the route's href
     * and emits that same event on click. The Vaadin shell strips it, but keeps the
     * {@code data-ec-route} attributes, and the inbox's script, which every shell loads
     * ({@code /_inbox/push/push.js}), turns a click on them into the same event. The {@code href}
     * is for a modified click (a new tab) in the Vaadin shell; Redwood writes its own.
     */
    static String html(int waiting, long urgent) {
        var label = label(waiting, urgent);
        var bell = "<vaadin-icon icon=\"vaadin:bell-o\" style=\"width: var(--lumo-icon-size-m, 1.25em);"
                + " height: var(--lumo-icon-size-m, 1.25em);\"></vaadin-icon>";
        var count = waiting == 0 ? "" : "<span aria-hidden=\"true\" style=\"position: absolute; top: 0.125rem;"
                + " inset-inline-end: 0.0625rem; box-sizing: border-box; min-width: 1.15em; height: 1.15em;"
                + " padding: 0 0.3em; border-radius: 1em; font-size: var(--lumo-font-size-xxs, 0.6875rem);"
                + " font-weight: 600; line-height: 1.15em; text-align: center; pointer-events: none;"
                + (urgent > 0
                        ? " background: var(--lumo-error-color, #c62828); color: var(--lumo-error-contrast-color, #161513);"
                        : " background: var(--lumo-primary-color, currentColor); color: var(--lumo-primary-contrast-color, #161513);")
                + "\">" + (waiting > 99 ? "99+" : String.valueOf(waiting)) + "</span>";
        return "<a href=\"" + ROUTE + "\""
                + " onclick=\"this.dispatchEvent(new CustomEvent('navigation-requested', {detail: {route: '" + ROUTE
                + "', consumedRoute: '', baseUrl: '/_inbox', uriPrefix: '', serverSideType: '" + InboxHome.class.getName()
                + "'}, bubbles: true, composed: true})); return false;\""
                + " aria-label=\"" + label + "\" title=\"" + label + "\""
                + " data-ec-route=\"" + ROUTE + "\" data-ec-base-url=\"/_inbox\""
                + " data-ec-server-side-type=\"" + InboxHome.class.getName() + "\""
                + " style=\"position: relative; display: inline-flex; align-items: center; justify-content: center;"
                + " min-width: var(--lumo-size-m, 2.25rem); height: var(--lumo-size-m, 2.25rem); margin: 0; cursor: pointer;"
                + " border-radius: var(--lumo-border-radius-m, 0.25em); text-decoration: none;"
                + " color: var(--mateu-header-icon-color, var(--lumo-secondary-text-color, currentColor));\">"
                + bell + count + "</a>";
    }

    @Override
    public Component component(HttpRequest httpRequest) {
        return Text.builder().text("${state.content}").build();
    }
}
