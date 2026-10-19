package io.mateu.ecdemo1.communication.ui.inbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The header's inbox: an icon link like the header's buttons, its words in Spanish in the tooltip
 * and the accessible name, the count as a pill over the bell.
 */
class InboxBadgeTest {

    @Test
    void itsLabelSaysHowManyWaitAndHowManyAreUrgentInSpanish() {
        assertThat(InboxBadge.label(0, 0)).isEqualTo("Bandeja");
        assertThat(InboxBadge.label(3, 0)).isEqualTo("Bandeja (3)");
        assertThat(InboxBadge.label(2, 1)).isEqualTo("Bandeja (2 · 1 urgente)");
        assertThat(InboxBadge.label(2, 2)).isEqualTo("Bandeja (2 · 2 urgentes)");
    }

    /** No {@code <vaadin-button>}: since Mateu 3.0-alpha.430 neither shell draws a custom element but the icon. */
    @Test
    void itIsAnIconLinkWithAnOutlineBell() {
        var html = InboxBadge.html(2, 2);
        assertThat(html)
                .startsWith("<a href=\"/inbox/pending\"")
                .endsWith("</a>")
                .contains("aria-label=\"Bandeja (2 · 2 urgentes)\"", "title=\"Bandeja (2 · 2 urgentes)\"")
                .contains("icon=\"vaadin:bell-o\"")
                .doesNotContain("<vaadin-button", "&nbsp;", "Inbox (", "vaadin:bell\"");
    }

    /**
     * Mateu sanitises rendered HTML, and each shell keeps a different way in: the Vaadin shell
     * (DOMPurify) the data attributes, for the inbox's script; the Redwood shell the
     * navigation-requested an {@code onclick} names, which it reads — never runs — into a route link.
     */
    @Test
    void itSaysWhereItGoesForBothShells() {
        var html = InboxBadge.html(2, 1);
        assertThat(html)
                .contains("data-ec-route=\"/inbox/pending\"", "data-ec-base-url=\"/_inbox\"",
                        "data-ec-server-side-type=\"" + InboxHome.class.getName() + "\"")
                .contains("onclick=\"this.dispatchEvent(new CustomEvent('navigation-requested', {detail: {route: '/inbox/pending',",
                        "baseUrl: '/_inbox'", "serverSideType: '" + InboxHome.class.getName() + "'}")
                .doesNotContain("javascript:", "<script");
    }

    @Test
    void theInboxScriptTurnsTheBadgeIntoANavigation() throws Exception {
        try (var in = InboxBadgeTest.class.getResourceAsStream("/push/push.js")) {
            assertThat(in).isNotNull();
            var script = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(script).contains("data-ec-route", "'navigation-requested'", "composedPath()");
        }
    }

    @Test
    void theCountIsAPillRedWhileSomethingUrgentWaits() {
        assertThat(InboxBadge.html(2, 1)).contains(">2</span>", "border-radius: 1em", "--lumo-error-color");
        assertThat(InboxBadge.html(3, 0)).contains(">3</span>", "--lumo-primary-color").doesNotContain("--lumo-error-color");
        assertThat(InboxBadge.html(120, 0)).contains(">99+</span>");
    }

    @Test
    void nothingWaitingIsJustTheBell() {
        assertThat(InboxBadge.html(0, 0)).doesNotContain("<span");
    }
}
