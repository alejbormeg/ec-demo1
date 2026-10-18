package io.mateu.ecdemo1.shell.infra.in.ui;

import io.mateu.ecdemo1.uicommons.user.UserWidget;
import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.AI;
import io.mateu.uidl.annotations.App;
import io.mateu.uidl.annotations.FavIcon;
import io.mateu.uidl.annotations.KeycloakSecured;
import io.mateu.uidl.annotations.Script;
import io.mateu.uidl.annotations.Logo;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.PageTitle;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.RemoteMenu;
import io.mateu.uidl.fluent.AppVariant;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.HomeRouteSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.WidgetSupplier;

import java.util.List;

/**
 * The one page a user ever loads.
 *
 * <p>Everything below the menu bar is served by another pod: each {@link RemoteMenu} names a
 * path, the gateway routes that path to the app that owns it, and the shell renders whatever
 * menu that app declares. So the orchestrator, the forms engine and the three demo
 * services keep their own UIs — nothing about them is restated here — and the shell only has to
 * know where they live. Adding one is a field below, a route in the gateway and a manifest; the
 * path in all three has to match the {@code @UI} value the service itself declares.
 *
 * <p>The Keycloak URL is baked in at compile time: Mateu writes {@code @KeycloakSecured} into
 * the generated bootstrap page, so it cannot be an environment variable yet. Changing the
 * deployment's hostname means rebuilding this image.
 *
 * <p><b>Two renderers, one class.</b> The same module builds the Vaadin console (ec-demo1-shell) and,
 * with {@code -Predwood}, the Redwood one (ec-demo1-shell-redwood): {@code io.mateu:mateu-redwood} in
 * place of {@code io.mateu:mateu-vaadin}, and nothing else about it is renderer-aware. That is the
 * point being demonstrated — a {@link RemoteMenu} carries UIDL rather than HTML, so the
 * orchestrator, the forms engine and the demo services render through whichever renderer the shell
 * loaded, with no change on any of them. The two run BESIDE each other, because side by side
 * against one cluster is the demonstration. No Keycloak client for the Redwood one:
 * {@code @KeycloakSecured} names the Keycloak URL, not this app's host, so the demo client serves
 * both — its redirect URIs list both hosts.
 */
@UI("")
// Which plane this console is, next to the logo: the data plane is what the business uses, the
// control plane what governs the platform.
@Title("Data plane")
// The renderer in the tab's title: the Redwood build of this same class says so (Renderer).
@PageTitle("Data plane" + Renderer.TITLE_SUFFIX)
@KeycloakSecured(url = "https://auth.ec1.mateu.io", realm = "ec-demo1", clientId = "demo")
// Web Push: the inbox's script — it offers to enable notifications, and registers this browser for
// what enters the inbox of the user's roles. Served by communication-service, public at the gateway.
@Script(src = "/_inbox/push/push.js")
// Served by this app from src/main/resources/static, so it arrives through the gateway's
// catch-all like the rest of the shell — no route of its own, and no token: the browser loads a
// logo with an <img> tag, which sends no Authorization header.
@Logo("/images/riu.svg")
@FavIcon("/images/riu.svg")
// Edge-to-edge: the pages behind these menus are listings and workflow graphs, and capping the
// content at the default ~900px container squeezes them into cards for lack of horizontal room.
@Style(StyleConstants.FULL_WIDTH)
// The home is a page of its own, Inicio (homeRoute below): a welcome — hero and KPI tiles — which
// both renderers draw. It used to be this class's own page, as a landing template with a welcome
// banner, but Redwood asks for a home route with no class to build it from, and was answered
// "Not found": its home stayed on "…".
// The chat panel. Mateu's client POSTs the prompt here and reads the answer as a stream; the
// gateway routes /ai/** to the agent pod and requires a token on it, which this client sends.
// The agent itself knows nothing about these menus: it reaches the orchestrator, the forms engine
// and the booking service over MCP, and each of those decides what it is willing to expose.
@AI(sse = "/ai/api/agent/stream")
// The light/dark switch in the header, next to the chat toggle: the theme otherwise follows the OS only.
// Two levels, as in the control console and Opera Cloud: the hamburger lists the sections (each one a
// service's remote menu) and the band below the header shows the active section's own entries.
// Declared, since AUTO never picks it.
// RIU's red as the brand accent: the console name in it, and under the menu band a colour strip Mateu
// draws from it (Vaadin only; seed 11, the one chosen — Redwood shows Spectra's own). Not the primary
// colour — it never means "clickable".
@App(value = AppVariant.HAMBURGER_SECTIONS, themeToggle = true, accentColor = "#D2232A", accentStripSeed = 11)
public class ShellHome implements WidgetSupplier, HomeRouteSupplier {

    /**
     * The welcome page, not the first menu: see {@link Inicio}. Routed in specs/ui/routes.yaml, so
     * the route answers whether or not the renderer names this class; the logo leads back to it.
     */
    @Override
    public String homeRoute() {
        return "/inicio";
    }

    // Every entry names its label, and that is not decoration.
    //
    // A RemoteMenu built from a base URL alone has no label, so Mateu fills one in from the FIELD
    // NAME until the remote pod answers with its own — see ActionableCompleter.completeActionable.
    // The two are then swapped in the browser, which is what makes the menu bar visibly change a
    // moment after it is drawn, and again on every navigation to the app root: clicking the logo
    // is the easiest way to see it. Four of these happened to match the field name and one did
    // not; naming all five is what stops that from being luck, and from breaking the day a field
    // is renamed.
    //
    // The cost is real and worth stating: these strings live in two repositories now. A section
    // renamed in its own pod and not here goes back to flickering, with the shell's version
    // showing first.

    /** Bookings — the CRUD, and the aggregate the booking saga confirms or cancels. */
    @Menu
    RemoteMenu booking = new RemoteMenu("/_booking").withLabel("Call center");

    /**
     * The master of trading partners — tour operators, agencies, companies — that the CRS sells
     * through and the CRS-PMS integration projects to the PMS (PoC ACL, docs/poc-acl).
     */
    @Menu
    RemoteMenu erp = new RemoteMenu("/_erp").withLabel("ERP");

    /**
     * The customers, as the business sees them: find one, and see its data, where it is known, its
     * reservations in every system and the changes asked for it. Read-only — served by the customer
     * MDM, whose technical screens are on the control console.
     */
    @Menu
    RemoteMenu customers = new RemoteMenu("/_customers").withLabel("Clientes");

    /**
     * The reception notices: what the desk must know of a guest, a reservation or an agency, and
     * when — before the arrival, at check-in, in house, at check-out. A reservation's and an agency's
     * are made here; a customer's are Salesforce's, shown read-only. Served by the notices service; the
     * front office keeps its copy of them.
     */
    @Menu
    RemoteMenu avisos = new RemoteMenu("/_notices").withLabel("Avisos");

    /**
     * A customer's stays in the chain — hotels, dates, rooms and what they spent — by the MDM's
     * customer code, for the head office. Served by customer-history; the front desk sees only its
     * summary, at the check-in.
     */
    @Menu
    RemoteMenu historial = new RemoteMenu("/_history").withLabel("Historial de clientes");

    /**
     * Riu Class, the loyalty programme: members, their tier and points, and what each stay added. A demo
     * service standing in for the chain's real one; the front office asks it at the check-in.
     */
    @Menu
    RemoteMenu riuClass = new RemoteMenu("/_loyalty").withLabel("Riu Class");

    // Contenidos is no longer on this bar. The pod is untouched and still serves its own @UI, so
    // /content/contents and the rest still resolve for a deep link or an embedder — what went is
    // the menu entry, not the screens.

    // Users, groups, roles and permissions moved to the control console: administering access is a
    // control-plane concern, not part of using the product. It is served by the same users pod,
    // now mounted by the control shell behind the ai-admin gate. See ControlShellHome.

    /**
     * A booking's journey across the chain — CRS, integration, engine, mapping, MDM, Opera, front
     * office, Salesforce — drawn from its traces by journey-service. Hidden from the bar: it is
     * opened from a booking ("Ver recorrido"), from Clientes and from the front office's stay.
     * Still declared, so /journey/bookings/{locator} resolves.
     */
    @Menu
    @Hidden
    RemoteMenu journey = new RemoteMenu("/_journey").withLabel("Recorrido");

    /**
     * The inbox, hidden from the bar: the badge in the widgets below is the way in, and it says how
     * much is waiting on the way. Still declared, so a deep link or a reload on /inbox/... resolves.
     */
    @Menu
    @Hidden
    RemoteMenu inbox = new RemoteMenu("/_inbox");

    /** The external APIs' usage, hidden from the bar like the inbox: the home's tiles are the way in. */
    @Menu
    @Hidden
    RemoteMenu apiUsage = new RemoteMenu("/_api-usage").withLabel("APIs externas");

    /**
     * Running the platform: Workflow and Forms, behind one entry — last on the bar: it is
     * what runs the product, not the product.
     *
     * <p>They used to sit on the bar beside Booking, which made four equals where there are
     * really two kinds of thing — see AdminMenu.
     */
    @Menu
    AdminMenu admin;

    /**
     * The inbox badge and who is signed in — the widgets every console shares (ui-commons). Nothing for an anonymous call: the bootstrap page is about
     * to redirect to Keycloak.
     */
    @Override
    public List<Component> widgets(HttpRequest httpRequest) {
        return UserWidget.withInboxBadge(httpRequest);
    }
}
