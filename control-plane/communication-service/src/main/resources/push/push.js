// The inbox's script, loaded by every shell (@Script) and served by communication-service through
// the gateway: the inbox badge's navigation (just below), and Web Push for the consoles and the
// front office.
//
// Web Push waits for the Keycloak token the bootstrap page keeps in localStorage, registers the service
// worker, and — once the person allowed notifications — tells the inbox where this browser is. It
// does so on every load, so the roles it receives for are always the current ones. The permission
// is only asked after a click: browsers ignore, or block, a request nobody asked for.
//
// The service worker lives under /_inbox/push/, so its scope is /_inbox/push/ and it controls no page
// of the console — it does not need to: a push reaches the registration whatever its scope. Which is
// also why this never waits on navigator.serviceWorker.ready: that promise is for a worker whose scope
// covers this page, and here it never resolves.
//
// What it puts on the page: a small offer in a corner the first time ("Activar avisos", dismissable
// for good), and <ec-push-toggle> — the "Avisos" line of the user menu (see intoRedwoodUserPopup and
// intoVaadinUserPopover for where it goes) — which says the state of this
// browser (activados / desactivados / bloqueados por el navegador / no disponibles) and turns them on
// or off, or sends a test. window.ecPush is the same, for a console to call.
// The inbox badge's way in (InboxBadge), and the user menu's Logout (UserWidget). Mateu sanitises
// the HTML it renders, so neither can carry an onclick (nor a javascript: href); this listens once,
// on the document:
//  - a click on an element carrying data-ec-route becomes the navigation-requested event the menu
//    sends, with the pod that owns the route. That is the Vaadin shell: it keeps data-* attributes.
//    The Redwood shell drops them, and makes the badge's <a> a route link by itself, from the
//    navigation-requested its onclick names (never run) — so a click there never reaches this.
//    A modified click (Ctrl, Cmd, Shift, middle button) is left to the browser: the link's href in a
//    new tab. Enter on the badge is a click already: it is a link.
//  - a click on a link to #ec-logout signs out with window.logout(), which the bootstrap page Mateu
//    writes for @KeycloakSecured defines — in both shells.
// Capture phase and composedPath: in the Vaadin shell both live inside shadow roots, and a click must
// reach this however the components in between handle it.
(() => {
  if (window.__ecRouteLinks) return;
  window.__ecRouteLinks = true;

  const target = (event, test) => {
    for (const node of event.composedPath()) {
      if (node instanceof Element && test(node)) return node;
    }
    return null;
  };

  const go = (link, event) => {
    event.preventDefault();
    link.dispatchEvent(new CustomEvent('navigation-requested', {
      detail: {
        route: link.getAttribute('data-ec-route'),
        consumedRoute: '',
        baseUrl: link.getAttribute('data-ec-base-url') || '',
        uriPrefix: '',
        serverSideType: link.getAttribute('data-ec-server-side-type') || undefined,
      },
      bubbles: true,
      composed: true,
    }));
  };

  document.addEventListener('click', event => {
    if (event.button > 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    const logout = target(event, node => node.localName === 'a' && node.getAttribute('href') === '#ec-logout');
    if (logout) {
      event.preventDefault();
      if (typeof window.logout === 'function') window.logout();
      return;
    }
    const link = target(event, node => node.hasAttribute('data-ec-route'));
    if (link) go(link, event);
  }, true);
})();

(() => {
  if (window.ecPush) return; // loaded twice: the first one is already at work

  const BASE = '/_inbox/push';
  const DISMISSED = '__ec_push_dismissed';
  const TURNED_OFF = '__ec_push_off';
  const supported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window && window.isSecureContext;

  let state = supported ? 'loading' : 'unsupported';
  let registration = null;
  let publicKey = null;
  let busy = false;
  let note = '';

  const get = k => { try { return localStorage.getItem(k); } catch (e) { return null; } };
  const put = (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (e) { /* private window */ } };
  const token = () => get('__mateu_auth_token');
  const auth = () => ({ Authorization: 'Bearer ' + token() });

  function set(next, message) {
    state = next;
    note = message || '';
    window.dispatchEvent(new CustomEvent('ec-push-state', { detail: { state, note } }));
  }

  async function waitForToken(ms) {
    const until = Date.now() + ms;
    while (!token() && Date.now() < until) {
      await new Promise(r => setTimeout(r, 500));
    }
    return token();
  }

  function urlBase64ToUint8Array(base64) {
    const padding = '='.repeat((4 - (base64.length % 4)) % 4);
    const raw = atob((base64 + padding).replace(/-/g, '+').replace(/_/g, '/'));
    return Uint8Array.from([...raw].map(c => c.charCodeAt(0)));
  }

  // The registration once its worker is active — its own, not navigator.serviceWorker.ready (see above).
  async function activated(reg) {
    if (reg.active) return reg;
    const worker = reg.installing || reg.waiting;
    if (worker) {
      await new Promise(resolve => {
        const check = () => { if (worker.state === 'activated' || reg.active) resolve(); };
        worker.addEventListener('statechange', check);
        check();
      });
    }
    return reg;
  }

  async function register() {
    if (registration) return registration;
    const reg = await navigator.serviceWorker.register(BASE + '/sw.js', { scope: BASE + '/' });
    registration = await activated(reg);
    return registration;
  }

  async function tellTheInbox(subscription) {
    const answer = await fetch(BASE + '/subscriptions', {
      method: 'POST',
      headers: { ...auth(), 'Content-Type': 'application/json' },
      body: JSON.stringify(subscription.toJSON()),
    });
    if (!answer.ok) throw new Error('The inbox answered ' + answer.status);
  }

  async function subscribe() {
    const reg = await register();
    let subscription = await reg.pushManager.getSubscription();
    if (!subscription) {
      subscription = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(publicKey),
      });
    }
    await tellTheInbox(subscription);
    return subscription;
  }

  // Where this browser stands, without asking anything.
  async function refresh() {
    if (!supported) return set('unsupported');
    if (!publicKey) return set('unavailable');
    if (Notification.permission === 'denied') return set('blocked');
    const reg = await register();
    const subscription = await reg.pushManager.getSubscription();
    if (Notification.permission === 'granted' && !get(TURNED_OFF)) {
      // Allowed, and not turned off here: subscribed, and the inbox told (again) — roles change.
      await subscribe();
      return set('on');
    }
    if (subscription && get(TURNED_OFF)) await subscription.unsubscribe();
    return set('off');
  }

  // After a click only: that is when a browser shows its permission prompt.
  async function enable() {
    if (busy || !supported || !publicKey) return state;
    busy = true;
    try {
      put(TURNED_OFF, null);
      put(DISMISSED, '1');
      const permission = await Notification.requestPermission();
      if (permission === 'granted') {
        await subscribe();
        set('on');
      } else {
        set(permission === 'denied' ? 'blocked' : 'off');
      }
    } catch (e) {
      console.warn('Web Push: could not enable notifications', e);
      set('error', String(e && e.message || e));
    } finally {
      busy = false;
    }
    return state;
  }

  async function disable() {
    if (busy || !supported) return state;
    busy = true;
    try {
      put(TURNED_OFF, '1');
      const reg = await register();
      const subscription = await reg.pushManager.getSubscription();
      if (subscription) {
        await fetch(BASE + '/subscriptions?endpoint=' + encodeURIComponent(subscription.endpoint), { method: 'DELETE', headers: auth() });
        await subscription.unsubscribe();
      }
      set(Notification.permission === 'denied' ? 'blocked' : 'off');
    } catch (e) {
      console.warn('Web Push: could not disable notifications', e);
      set('error', String(e && e.message || e));
    } finally {
      busy = false;
    }
    return state;
  }

  // "Enviarme una prueba": the service sends one straight to this browser.
  async function test() {
    const reg = await register();
    const subscription = await reg.pushManager.getSubscription();
    if (!subscription) return { outcome: 'NOT_FOUND', detail: 'Este navegador no tiene los avisos activados' };
    const answer = await fetch(BASE + '/test', {
      method: 'POST',
      headers: { ...auth(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ endpoint: subscription.endpoint }),
    });
    if (!answer.ok) return { outcome: 'FAILED', detail: 'El servicio respondió ' + answer.status };
    return answer.json();
  }

  // ── On the page ────────────────────────────────────────────────────────────────────────────────

  const LABELS = {
    loading: 'comprobando…',
    on: 'activados',
    off: 'desactivados',
    blocked: 'bloqueados por el navegador',
    unsupported: 'no disponibles en este navegador',
    unavailable: 'no disponibles',
    error: 'no se pudieron activar',
  };

  function offer() {
    if (get(DISMISSED) || state !== 'off' || Notification.permission !== 'default') return;
    const bar = document.createElement('div');
    bar.setAttribute('role', 'status');
    bar.style.cssText = 'position:fixed;left:16px;bottom:16px;z-index:10000;display:flex;gap:8px;align-items:center;'
      + 'max-width:calc(100vw - 32px);padding:8px 10px 8px 12px;border-radius:8px;background:#1f2937;color:#fff;'
      + 'font:14px system-ui,sans-serif;box-shadow:0 2px 8px rgba(0,0,0,.25)';
    const text = document.createElement('span');
    text.textContent = '¿Avisos en este navegador?';
    const enableButton = document.createElement('button');
    enableButton.textContent = 'Activar avisos';
    enableButton.style.cssText = 'border:0;border-radius:6px;padding:6px 10px;background:#2563eb;color:#fff;cursor:pointer;font:inherit';
    const later = document.createElement('button');
    later.textContent = '✕';
    later.title = 'Ahora no (se pueden activar luego en el menú de usuario)';
    later.setAttribute('aria-label', 'Ahora no');
    later.style.cssText = 'border:0;background:transparent;color:#cbd5e1;cursor:pointer;font:inherit;padding:4px 6px';
    enableButton.onclick = () => { bar.remove(); enable(); };
    later.onclick = () => { put(DISMISSED, '1'); bar.remove(); };
    window.addEventListener('ec-push-state', () => { if (state !== 'off') bar.remove(); });
    bar.append(text, enableButton, later);
    document.body.appendChild(bar);
  }

  // The "Avisos" line of the user menu: the state of this browser, and what can be done about it.
  class PushToggle extends HTMLElement {
    constructor() {
      super();
      this.attachShadow({ mode: 'open' });
      this.onState = () => this.render();
      this.testNote = '';
    }

    connectedCallback() {
      window.addEventListener('ec-push-state', this.onState);
      this.render();
    }

    disconnectedCallback() {
      window.removeEventListener('ec-push-state', this.onState);
    }

    render() {
      const s = state;
      const hint = s === 'blocked'
        ? 'Para activarlos, permite las notificaciones de este sitio en el candado de la barra de direcciones y recarga.'
        : s === 'error' ? note : s === 'unavailable' ? 'Este despliegue no tiene Web Push configurado.' : '';
      this.shadowRoot.innerHTML = `
        <style>
          :host { display: block; font: inherit; }
          .row { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
          .state { font-weight: 600; }
          .state.on { color: #15803d; } .state.blocked, .state.error { color: #b91c1c; }
          button { font: inherit; font-size: .875em; border: 1px solid rgba(128,128,128,.5); border-radius: 6px;
                   padding: 3px 8px; background: transparent; color: inherit; cursor: pointer; }
          button.primary { background: #2563eb; border-color: #2563eb; color: #fff; }
          .hint { margin: 4px 0 0; font-size: .8em; opacity: .75; max-width: 260px; }
        </style>
        <div class="row"><span>Avisos: <span class="state ${s}">${LABELS[s] || s}</span></span></div>
        <div class="row" style="margin-top:4px">
          ${s === 'off' || s === 'error' ? '<button class="primary" data-do="enable">Activar avisos</button>' : ''}
          ${s === 'on' ? '<button data-do="test">Enviarme una prueba</button><button data-do="disable">Desactivar</button>' : ''}
        </div>
        ${hint ? `<p class="hint"></p>` : ''}
        ${this.testNote ? `<p class="hint test"></p>` : ''}`;
      if (hint) this.shadowRoot.querySelector('.hint').textContent = hint;
      if (this.testNote) this.shadowRoot.querySelector('.hint.test').textContent = this.testNote;
      this.shadowRoot.querySelectorAll('button').forEach(b => b.addEventListener('click', e => this.act(e, b.dataset.do)));
    }

    async act(event, what) {
      event.stopPropagation(); // a click here is not a click that closes the menu
      this.testNote = '';
      if (what === 'enable') await enable();
      if (what === 'disable') await disable();
      if (what === 'test') {
        this.testNote = 'Enviando…';
        this.render();
        const r = await test();
        this.testNote = r.outcome === 'SENT' ? 'Enviada: debería aparecer en unos segundos.'
          : r.outcome === 'NOT_CONFIGURED' ? 'Web Push no está configurado en este despliegue.'
          : r.outcome === 'GONE' ? 'El navegador ya no tenía la suscripción: vuelve a activar los avisos.'
          : 'No se pudo enviar: ' + (r.detail || r.outcome);
        if (r.outcome === 'GONE') { put(TURNED_OFF, '1'); await refresh().catch(() => {}); }
      }
      this.render();
    }
  }

  if (!customElements.get('ec-push-toggle')) customElements.define('ec-push-toggle', PushToggle);

  // Where <ec-push-toggle> goes in the user menu. The widget cannot send the element itself: both
  // renderers sanitise its HTML (Mateu 3.0-alpha.430) and drop an unknown custom element.
  //  - Redwood draws the popover itself (#mateuUserPopup) from plain rows — text through
  //    oj-bind-text and links — so nothing of the widget's markup reaches its DOM: the element is put
  //    in the popup, before Logout, when the popup is drawn (a mutation of the document).
  //  - Vaadin draws the widget's own HTML, which keeps an inert <span data-ec-push-toggle>: the
  //    element is put inside it. Inside, not instead: Lit owns that span, and when it redraws the
  //    popover the span goes and comes back empty, to be filled again. It lives in shadow roots (the
  //    shell's, the popover's content is drawn in place in Vaadin 25), which a mutation observer on
  //    the document does not see: it is looked for, across shadow roots, a few times after each
  //    click, Enter or Space, which is what opens the popover.
  function intoRedwoodUserPopup() {
    const content = document.querySelector('#mateuUserPopup .oj-popup-content > div');
    if (content && !content.querySelector('ec-push-toggle')) {
      const toggle = document.createElement('ec-push-toggle');
      const logout = content.querySelector('a[href*="logout"]');
      content.insertBefore(toggle, logout || null);
    }
  }
  let scheduled = false;
  new MutationObserver(() => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => { scheduled = false; intoRedwoodUserPopup(); });
  }).observe(document.documentElement, { childList: true, subtree: true });

  // Each element matching selector under root, shadow roots included.
  function eachDeep(root, selector, fn) {
    root.querySelectorAll(selector).forEach(fn);
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (node.shadowRoot && node.localName !== 'ec-push-toggle') eachDeep(node.shadowRoot, selector, fn);
    }
  }
  function intoVaadinUserPopover() {
    eachDeep(document, '[data-ec-push-toggle]', slot => {
      if (!slot.querySelector('ec-push-toggle')) slot.appendChild(document.createElement('ec-push-toggle'));
    });
  }
  let pending = [];
  const lookForTheSlot = () => {
    pending.forEach(clearTimeout);
    pending = [50, 250, 750].map(ms => setTimeout(intoVaadinUserPopover, ms));
  };
  document.addEventListener('click', lookForTheSlot, true);
  document.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') lookForTheSlot(); }, true);

  window.ecPush = { state: () => state, enable, disable, test, refresh };

  (async () => {
    if (!supported) return set('unsupported');
    if (!await waitForToken(60000)) return;
    try {
      const answer = await fetch(BASE + '/public-key', { headers: auth() });
      if (!answer.ok) return set('unavailable'); // Web Push is not configured on this deployment
      publicKey = (await answer.json()).publicKey;
      await refresh();
      // A moment after the page settles, not while the shell is still drawing.
      setTimeout(offer, 4000);
    } catch (e) {
      console.warn('Web Push not available', e);
      set('error', String(e && e.message || e));
    }
  })();
})();
