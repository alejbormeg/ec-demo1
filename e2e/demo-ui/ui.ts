import { execFileSync } from 'node:child_process'
import { Locator, Page, TestInfo, expect } from '@playwright/test'

/**
 * Helpers for the UI-only demo run (demo-ui.spec.ts). Everything is driven through the consoles;
 * the only non-UI call is the Salesforce Case decision (see {@link SalesforceFixture}).
 *
 * Selectors carry one of two marks:
 *   - verified live (probes of 2026-10-04 against 0.60.0 / Mateu alpha.392), or seen working in the
 *     same day's UI-only run (e2e/demo-ui/README in the PR, ec-demo1-ux/demo-run-2026-10-04);
 *   - `// UNVERIFIED (probe denied): …` — written from the source code / the earlier run's page text,
 *     never exercised against the deployed UI. The first real run points straight at those.
 */

export type Renderer = 'redwood' | 'vaadin'
export const RENDERER: Renderer = (process.env.DEMO_RENDERER as Renderer) ?? 'redwood'

const vaadin = RENDERER === 'vaadin'
// https on ec1; http for the local cluster (deploy/local/up.sh: E2E_SCHEME=http, hosts *.localhost:8800).
const SCHEME = (process.env.E2E_SCHEME ?? 'https') + '://'
export const HOSTS = {
    data: SCHEME + (process.env.DEMO_DATA_HOST ?? (vaadin ? 'ec1.mateu.io' : 'rw.ec1.mateu.io')),
    control: SCHEME + (process.env.DEMO_CONTROL_HOST ?? (vaadin ? 'console.ec1.mateu.io' : 'rw-console.ec1.mateu.io')),
    frontOffice: SCHEME + (process.env.DEMO_FRONT_HOST ?? 'front.ec1.mateu.io'),
}

const USER = process.env.DEMO_USER ?? 'demo'
function password(): string {
    const p = process.env.DEMO_PASSWORD
    if (!p) throw new Error('DEMO_PASSWORD is not set (source deploy/.secrets/credentials.env first)')
    return p
}

/** The page's text, through every shadow root, joined with " | " (as the 2026-10-04 run read it). */
export function pageText(page: Page): Promise<string> {
    return page.evaluate(() => {
        const out: string[] = []
        const walk = (n: Node) => {
            if (n.nodeType === 3) { const t = (n.textContent ?? '').trim(); if (t) out.push(t); return }
            if (n.nodeType !== 1 && n.nodeType !== 11) return
            const el = n as HTMLElement
            if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE') return
            if (el.shadowRoot) walk(el.shadowRoot)
            for (const c of Array.from(n.childNodes)) walk(c)
        }
        walk(document.body)
        return out.join(' | ')
    }).catch(() => '')
}

/** The text after the first match of `re`, `n` characters of it: a field's value on a detail page. */
export async function after(page: Page, re: RegExp, n = 200): Promise<string> {
    const t = await pageText(page)
    const i = t.search(re)
    return i < 0 ? '' : t.slice(i, i + n)
}

export class Ui {
    constructor(readonly page: Page, readonly info: TestInfo) {}

    private shots = 0

    /** A screenshot attached to the report: one per checkpoint. */
    async checkpoint(name: string) {
        this.shots++
        const body = await this.page.screenshot({ fullPage: false }).catch(() => undefined)
        if (body) await this.info.attach(`${String(this.shots).padStart(2, '0')}-${name}`, { body, contentType: 'image/png' })
    }

    async signInIfAsked() {
        if (this.page.url().includes('/realms/')) {
            await this.page.fill('#username', USER)
            await this.page.fill('#password', password())
            await this.page.click('#kc-login')
            await this.page.waitForTimeout(3_000)
        }
    }

    /**
     * Goes to a URL and waits for `re` in its text. Redwood shells take 30–40 s to boot: 120 s by default.
     * The shell sends the browser to Keycloak only once its script runs, which on a slow machine (the
     * local cluster) is well after the page loads: it signs in whenever the login page turns up.
     */
    async go(url: string, re: RegExp, timeout = 120_000) {
        await this.page.goto(url, { waitUntil: 'domcontentloaded', timeout: 90_000 })
        await expect.poll(async () => {
            if (this.page.url().includes('/realms/') && await this.page.locator('#username').count()) {
                await this.signInIfAsked()
            }
            return re.test(await pageText(this.page))
        }, { message: `${url} never showed ${re}`, timeout, intervals: [1_000] }).toBe(true)
    }

    async waitText(re: RegExp, message: string, timeout = 90_000) {
        await expect.poll(async () => re.test(await pageText(this.page)), { message, timeout, intervals: [1_000] }).toBe(true)
    }

    async text() { return pageText(this.page) }

    async reload(re: RegExp) {
        await this.page.reload({ waitUntil: 'domcontentloaded' })
        await expect.poll(async () => {
            if (this.page.url().includes('/realms/') && await this.page.locator('#username').count()) {
                await this.signInIfAsked()
            }
            return re.test(await pageText(this.page))
        }, { message: `reload never showed ${re}`, timeout: 90_000, intervals: [1_000] }).toBe(true)
    }

    /** Polls a page (reloading it) until `probe(text)` is truthy; returns that value. */
    async pollPage<T>(url: string, ready: RegExp, probe: (text: string) => T | undefined | null | false,
                      message: string, timeout = 300_000, every = 15_000): Promise<T> {
        await this.go(url, ready)
        const end = Date.now() + timeout
        for (;;) {
            const v = probe(await this.text())
            if (v) return v as T
            if (Date.now() > end) throw new Error(`${message} (waited ${Math.round(timeout / 1000)} s on ${url})`)
            await this.page.waitForTimeout(every)
            await this.reload(ready)
        }
    }

    button(name: string | RegExp): Locator {
        return this.page.getByRole('button', { name, exact: typeof name === 'string' }).first()
    }

    async click(loc: Locator, after = 1_500) {
        await loc.scrollIntoViewIfNeeded({ timeout: 20_000 }).catch(() => {}) // without one it waits for ever
        await loc.click({ timeout: 20_000 })
        await this.page.waitForTimeout(after)
    }

    /**
     * The ⋯ overflow of a page header → one of its items. Redwood: oj-c-menu-button (verified live on
     * the Demo page and on Bookings). Vaadin: the header's "⋯" button.
     */
    async more(item: string | RegExp) {
        if (vaadin) {
            // UNVERIFIED (probe denied): the Vaadin header's overflow is a "⋯" button opening a vaadin-context-menu.
            await this.click(this.page.getByRole('button', { name: '⋯' }).first())
        } else {
            await this.click(this.page.locator('oj-c-menu-button').first())
        }
        await this.click(this.page.getByRole('menuitem', { name: item }).first(), 2_000)
    }

    /**
     * A header action that may be a button or an item of the ⋯ menu: Redwood (Mateu alpha.430) draws
     * the first toolbar action as a button and moves the rest into the menu.
     */
    async action(label: string) {
        const button = this.button(label)
        if (await button.isVisible().catch(() => false)) {
            await this.click(button, 2_500)
        } else {
            await this.more(label)
        }
    }

    /** The items of the ⋯ menu, then closes it. */
    async moreItems(): Promise<string[]> {
        await this.click(vaadin ? this.page.getByRole('button', { name: '⋯' }).first() : this.page.locator('oj-c-menu-button').first())
        const items = (await this.page.getByRole('menuitem').allInnerTexts()).map(s => s.trim())
        await this.page.keyboard.press('Escape')
        await this.page.waitForTimeout(500)
        return items
    }

    /** Mateu's confirmation dialog: #mateuConfirmAccept («Sí») / #mateuConfirmDeny («No») — verified live. */
    async confirmIfAsked(after = 4_000) {
        await this.page.waitForTimeout(1_000)
        const accept = this.page.locator('#mateuConfirmAccept')
        if (await accept.count() && await accept.first().isVisible().catch(() => false)) await this.click(accept.first(), after)
    }

    /** A text/date field by its label — getByLabel(exact) resolves once per input (verified live on the wizard). */
    async type(label: string, value: string) {
        const f = this.page.getByLabel(label, { exact: true }).first()
        await f.click({ timeout: 30_000 })
        await f.fill(value)
        await f.press('Tab')
        await this.page.waitForTimeout(500)
    }

    /**
     * A select by its label, then the option matching `option`. getByLabel matches three elements for a
     * Redwood select (verified live: «Hotel code» → 3), the combobox among them.
     */
    async select(label: string, option: RegExp, scope: 'first' | 'last' = 'first') {
        // UNVERIFIED (probe denied): role=combobox carries the label; the 2026-10-04 run clicked by coordinates.
        let f = this.page.getByRole('combobox', { name: label, exact: true })
        if (!await f.count()) f = this.page.getByLabel(label, { exact: true })
        const target = scope === 'first' ? f.first() : f.last()
        await this.click(target, 1_500)
        await this.click(this.page.getByRole('option').filter({ hasText: option }).first(), 1_000)
    }

    /**
     * Turns a boolean field on and checks it took. Redwood draws it as an oj-switch whose label does
     * not toggle it (seen in the 2026-10-06 run: the reset task completed with confirmado=false);
     * its thumb carries role=switch / aria-checked. Vaadin: the field's vaadin-checkbox.
     */
    async switchOn(fieldId: string) {
        const field = this.page.locator(`[data-field-id="${fieldId}"]`).first()
        const thumb = vaadin ? field : field.locator('[role=switch]').first()
        const on = async () => vaadin
            ? (await field.getAttribute('checked')) !== null
            : (await thumb.getAttribute('aria-checked')) === 'true'
        if (!(await on())) await this.click(thumb, 1_000)
        if (!(await on())) throw new Error(`the ${fieldId} switch did not turn on`)
    }

    /** A listing row's selection checkbox: the selector at the same height as `text` (as the 2026-10-04 run did). */
    async selectRow(text: string | RegExp) {
        const cell = this.page.getByText(text, { exact: typeof text === 'string' }).first()
        const box = await cell.boundingBox()
        if (!box) throw new Error(`no row shows ${text}`)
        const boxes = this.page.locator('oj-c-selector, oj-selector, input[type=checkbox], vaadin-checkbox')
        const n = await boxes.count()
        for (let i = 0; i < n; i++) {
            const b = await boxes.nth(i).boundingBox()
            if (b && Math.abs(b.y + b.height / 2 - (box.y + box.height / 2)) < 15) { await this.click(boxes.nth(i), 1_000); return }
        }
        throw new Error(`no selection box on the row of ${text}`)
    }

    /** Pages a listing forward (its «Página siguiente») until `re` shows; false if it never does. */
    async pageUntil(re: RegExp, pages = 8): Promise<boolean> {
        for (let i = 0; i < pages; i++) {
            if (re.test(await this.text())) return true
            const next = this.page.getByRole('button', { name: /Página siguiente/ }).first()
            if (!(await next.isEnabled().catch(() => false))) return false
            await this.click(next, 3_000)
        }
        return re.test(await this.text())
    }

    /** The «Open» of an inbox row showing `title` (same height), as the 2026-10-04 run did. */
    async openInboxRow(title: string | RegExp) {
        const row = this.page.getByText(title).first()
        const box = await row.boundingBox()
        const opens = this.page.getByText(/^\s*Open\s*$/)
        const n = await opens.count()
        for (let i = 0; i < n; i++) {
            const b = await opens.nth(i).boundingBox()
            if (b && box && Math.abs(b.y - box.y) < 25) { await this.click(opens.nth(i), 4_000); return }
        }
        throw new Error(`the inbox row «${title}» has no Open`)
    }
}

/** dd/mm/yyyy, `days` from today (Europe/Madrid is the browser's zone; the date is computed locally). */
export function day(days: number): string {
    const d = new Date(Date.now() + days * 86_400_000)
    return `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()}`
}

/** A random tag so the bookings a run creates are its own. */
export const RUN = Math.random().toString(36).slice(2, 7)

/**
 * TEST FIXTURE — not the demo's UI. Salesforce decides a change request in its own UI, which needs MFA
 * and cannot be automated. The MDM's integration user (cluster secret ec-salesforce: SF_DOMAIN,
 * SF_CLIENT_ID, SF_CLIENT_SECRET — client credentials, the same e2e/demo/clients.ts and
 * deploy/demo/ec1.py use) sets the Case's Decision__c exactly as a steward would; the flow that applies
 * it in Salesforce, and everything downstream, run as in the demo. The secret is read at runtime with
 * kubectl and never printed or written anywhere.
 */
export class SalesforceFixture {
    private session?: { instance: string, token: string }

    /** Undefined when the secret cannot be read (no kubectl context, no access): the caller skips. */
    static tryCreate(): SalesforceFixture | undefined {
        try {
            const f = new SalesforceFixture()
            f.creds()
            return f
        } catch {
            return undefined
        }
    }

    private credsCache?: Record<string, string>
    private creds(): Record<string, string> {
        if (this.credsCache) return this.credsCache
        const ns = process.env.DEMO_NAMESPACE ?? 'ec-demo1'
        const values: Record<string, string> = {}
        for (const key of ['SF_DOMAIN', 'SF_CLIENT_ID', 'SF_CLIENT_SECRET']) {
            const raw = execFileSync('kubectl', ['-n', ns, 'get', 'secret', 'ec-salesforce', '-o', `jsonpath={.data.${key}}`],
                { stdio: ['ignore', 'pipe', 'ignore'], timeout: 30_000 }).toString()
            if (!raw) throw new Error(`ec-salesforce has no ${key}`)
            values[key] = Buffer.from(raw, 'base64').toString()
        }
        this.credsCache = values
        return values
    }

    private async open() {
        if (this.session) return this.session
        const c = this.creds()
        const body = new URLSearchParams({ grant_type: 'client_credentials', client_id: c.SF_CLIENT_ID, client_secret: c.SF_CLIENT_SECRET })
        const r = await fetch(`https://${c.SF_DOMAIN}/services/oauth2/token`, { method: 'POST', body })
        if (!r.ok) throw new Error(`Salesforce token: HTTP ${r.status}`)
        const j = await r.json() as { instance_url: string, access_token: string }
        this.session = { instance: j.instance_url, token: j.access_token }
        return this.session
    }

    async call(method: string, path: string, body?: unknown): Promise<any> {
        const s = await this.open()
        const r = await fetch(`${s.instance}/services/data/v67.0${path}`, {
            method, headers: { Authorization: `Bearer ${s.token}`, 'Content-Type': 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body),
        })
        if (!r.ok) throw new Error(`Salesforce ${method} ${path}: HTTP ${r.status}`)
        const t = await r.text()
        return t ? JSON.parse(t) : null
    }

    /** What a steward does in the Case: Decisión = Aprobada. */
    approveCase(caseId: string) {
        return this.call('PATCH', `/sobjects/Case/${caseId}`, { Decision__c: 'Aprobada' })
    }
}
