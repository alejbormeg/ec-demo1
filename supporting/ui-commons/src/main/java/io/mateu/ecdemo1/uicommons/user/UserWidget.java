package io.mateu.ecdemo1.uicommons.user;

import io.mateu.ecdemo1.uicommons.html.Html;
import io.mateu.uidl.data.Anchor;
import io.mateu.uidl.data.HorizontalLayout;
import io.mateu.uidl.data.MicroFrontend;
import io.mateu.uidl.data.Popover;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.data.VerticalLayout;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.HttpRequest;

import java.util.List;
import java.util.Map;

/**
 * Who is signed in, and a way out: a greeting that opens a popover with the email and Logout. The
 * header widget of the four consoles' shells and of the front office.
 *
 * <p>The identity is the Keycloak token the page sends with every call, read by
 * {@link DisplayOnlyTokenClaims} — display only; the app's resource server has already verified
 * it. The name is escaped: it goes into markup.
 *
 * <p>The popover also says whether this browser receives notifications (Web Push), and turns them on
 * or off: {@code <ec-push-toggle>}, a custom element that the inbox's push script
 * ({@code /_inbox/push/push.js}, loaded by every shell with {@code @Script}) defines and puts in the
 * popover. The widget cannot send the element: Mateu sanitises rendered HTML and drops an unknown
 * custom element (3.0-alpha.430). It sends an inert {@code <span data-ec-push-toggle>}, which
 * survives, and the script fills it (Vaadin); Redwood draws its own popover from plain rows, and the
 * script puts the element there by itself.
 *
 * <p>Logout is {@code window.logout()}, which the bootstrap page Mateu writes for
 * {@code @KeycloakSecured} defines. A {@code javascript:} href is not a link for Mateu (safeHref drops
 * it, in both renderers), so the link goes to {@code #ec-logout} and the same script turns a click on
 * it into that call. Where the script is not loaded neither line works.
 *
 * <p>Anonymous calls get no widget: the bootstrap page is about to redirect to Keycloak, so there
 * is no one to greet yet.
 */
public final class UserWidget {

    /** The icon a narrow header keeps; the popover still has who and Logout. */
    static final String NARROW_ICON = "<vaadin-icon icon=\"vaadin:user\" style=\"display: var(--mateu-header-narrow-only, none); width: 1em; height: 1em; vertical-align: -0.125em;\"></vaadin-icon>";

    /**
     * Where Web Push for this browser goes — its state, and Activar / Desactivar / Enviarme una
     * prueba: the push script puts an {@code <ec-push-toggle>} in it.
     */
    static final String PUSH_TOGGLE = "<span data-ec-push-toggle></span>";

    /** The Logout link's target: the push script signs out on a click on it. */
    static final String LOGOUT = "#ec-logout";

    private UserWidget() {
    }

    /**
     * The banner a simulated Opera outage shows in every console while it is on — «Simulación: Opera no
     * responde» — and nothing at all otherwise: the control plane's Demo page switches it
     * (integrations-service, /_demo-banner, asked again every few seconds).
     */
    public static Component demoBanner() {
        return MicroFrontend.builder()
                .baseUrl("/_demo-banner")
                .route("/banner")
                .build();
    }

    /** The greeting and its popover alone. */
    public static List<Component> of(HttpRequest httpRequest) {
        return DisplayOnlyTokenClaims.of(httpRequest)
                .map(claims -> List.<Component>of(popover(claims)))
                .orElse(List.of());
    }

    /** The front office's header: the demo's banner, then the greeting — one layout, as the consoles'. */
    public static List<Component> withDemoBanner(HttpRequest httpRequest) {
        return DisplayOnlyTokenClaims.of(httpRequest)
                .map(claims -> List.<Component>of(HorizontalLayout.builder()
                        .content(List.of(demoBanner(), popover(claims)))
                        .style("align-items: center; gap: 4px; --mateu-content-gutter: 0px;") // no gutter: see withInboxBadge
                        .build()))
                .orElse(List.of());
    }

    /**
     * The consoles' header: the demo's banner (empty unless an Opera outage is simulated), the inbox badge — what waits for the signed-in user, the notifications
     * and the forms engine's tasks of their roles — and then the greeting.
     */
    public static List<Component> withInboxBadge(HttpRequest httpRequest) {
        return withInboxBadge(httpRequest, List.of());
    }

    /**
     * The same, with other widgets before the inbox badge. One layout for all
     * of them: the header puts each widget in its "widgets" slot, and a bare MicroFrontend does not
     * carry the slot through — it would render outside the header, invisible.
     */
    public static List<Component> withInboxBadge(HttpRequest httpRequest, List<Component> before) {
        return DisplayOnlyTokenClaims.of(httpRequest)
                .map(claims -> {
                    var content = new java.util.ArrayList<Component>();
                    content.add(demoBanner());
                    content.addAll(before);
                    content.add(MicroFrontend.builder()
                            .baseUrl("/_inbox")
                            .route("/badge")
                            .build());
                    content.add(popover(claims));
                    return List.<Component>of(HorizontalLayout.builder()
                            .content(content)
                            // The remote widgets in it are views, and Mateu gives a view the content gutter (24px each
                            // side): an empty demo banner was 48px of nothing between the chat and the bell. Here
                            // there is no content to frame, so no gutter, and a small gap between the icons.
                            .style("align-items: center; gap: 4px; --mateu-content-gutter: 0px;")
                            .build());
                })
                .orElse(List.of());
    }

    /** "Hola, name", opening the email, this browser's notifications and Logout. */
    public static Popover popover(Map<String, Object> claims) {
        return Popover.builder()
                .wrapped(Text.builder()
                        .text(NARROW_ICON
                                + "<span style=\"display: var(--mateu-header-wide-only, inline)\">Hola, " + Html.escape(String.valueOf(claims.get("name"))) + "</span>")
                        .style("margin-right: 20px; cursor: pointer;") // it opens the popover: who, and Logout
                        .build())
                .content(VerticalLayout.builder()
                        .content(List.of(
                                new Text("Email: " + claims.get("email")),
                                new Text(PUSH_TOGGLE),
                                new Anchor("Logout", LOGOUT)))
                        .spacing(true)
                        .padding(true)
                        .build())
                .build();
    }
}
