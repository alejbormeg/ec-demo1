import { execFileSync } from 'node:child_process'
import { Browser, Page, expect, test } from '@playwright/test'
import { HOSTS, RENDERER, RUN, SalesforceFixture, Ui, after, day } from './ui'

/**
 * The demo, from a reset to flow 8, through the consoles only — what the 2026-10-04 UI-only run did
 * by hand (docs/poc-acl/demo.md, docs/poc-acl/grabacion-demo.md), as a spec that can be run again.
 *
 * It WIPES the demo data: run it only through demo-ui.config.ts with DEMO_E2E_RESET=1
 * (`DEMO_E2E_RESET=1 npm run demo:ui`, see e2e/README.md). Opera (a shared UAT) is never cleaned:
 * the reset moves the demo to a new Opera context, and the run only creates its own reservations.
 *
 * Serial: each flow builds on the one before (flow 1 creates the integrations everything else uses),
 * so a failure stops the chain and the following steps are reported as not run.
 *
 * Selector provenance: see ui.ts. Steps carry a «Not verified live» note listing what the first real
 * run has to confirm; individual lines carry `// UNVERIFIED (probe denied): …`.
 */

const D = HOSTS.data
const C = HOSTS.control
const FO = HOSTS.frontOffice

/** Room types to try, in order, when Opera refuses one with RSV00138 (not enough rooms of that type). */
const ROOM_TYPES = (process.env.DEMO_ROOM_TYPES ?? 'JS-SEA,JS-SIDESEA,JS-STD,JS-ACCESSIBLE,JS-STD-KING').split(',')
const OUTAGE_ROOM = process.env.DEMO_OUTAGE_ROOM ?? 'JS-SIDESEA'

// What the flows hand to each other.
const S: {
    original?: string, originalHolder?: Holder, duplicate?: string,
    outageBooking?: string,
} = {}

interface Holder { first: string, last: string, email: string, phone: string, nationality: string }

interface NewBooking {
    arrival: string, departure: string, holder: Holder,
    room: string, rate: string, board: string, adults: number, guests: [string, string][],
    channel?: string,
}

let browser: Browser
let page: Page

test.describe.serial(`demo, UI only (${RENDERER})`, () => {

    test.beforeAll(async ({ browser: b }) => {
        browser = b
        const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 }, locale: 'es-ES', timezoneId: 'Europe/Madrid' })
        page = await ctx.newPage()
    })

    test.afterAll(async () => { await page?.context().close() })

    // ── 0 ──────────────────────────────────────────────────────────────────────────────────────────
    // Not verified live: the inbox row's «Open», the task page's Claim / checkbox / Complete (forms
    // 2.23.8 hides Claim once claimed), the Demo page's step list after the reset.
    test('0 · reset from the Demo page, confirmed in the inbox', async ({}, info) => {
        test.setTimeout(15 * 60_000)
        const ui = new Ui(page, info)
        await ui.go(`${C}/demo/admin`, /Resetear la demo/)
        const before = (await ui.text()).match(/Último reset:[^|]*/)?.[0] ?? ''
        await ui.checkpoint('demo-page')
        // Verified live: ⋯ → «Resetear la demo…» → «¿Resetear la demo?» Sí/No.
        await ui.more('Resetear la demo…')
        await ui.confirmIfAsked()
        await ui.checkpoint('reset-launched')

        // An administrator confirms it in the inbox.
        await ui.pollPage(`${C}/inbox/pending`, /Title/, t => /Confirmar el reset de la demo/.test(t),
            'the reset never asked for its confirmation in the inbox', 180_000, 10_000)
        await ui.checkpoint('inbox-reset-task')
        // UNVERIFIED (probe denied): the row's «Open» (0.60.0: it stays in the console).
        await ui.openInboxRow('Confirmar el reset de la demo')
        await ui.waitText(/Sí, resetear la demo/, 'the reset task never opened')
        // Verified live (2026-10-06): «Claim», then the «confirmado» switch (its label does not toggle it), then «Complete».
        if (await ui.button('Claim').isVisible().catch(() => false)) await ui.click(ui.button('Claim'), 5_000)
        await ui.switchOn('confirmado')
        await ui.checkpoint('reset-task-ticked')
        await ui.click(ui.button('Complete'), 6_000)
        await ui.checkpoint('reset-task-completed')

        // The Demo page follows the reset to its last step.
        await ui.pollPage(`${C}/demo/admin`, /Resetear la demo/,
            t => { const now = t.match(/Último reset:[^|]*/)?.[0] ?? ''
                   return now !== before && /completado/.test(now) && /notify-result \| hecho/.test(t) },
            'the reset never completed on the Demo page', 10 * 60_000, 15_000)
        await ui.checkpoint('reset-done')

        // The data plane is empty.
        await ui.go(`${D}/booking/bookings`, /\+ 10 reservas demo/)
        await ui.checkpoint('bookings-empty')
    })

    // ── 1 ──────────────────────────────────────────────────────────────────────────────────────────
    // Not verified live: the integration forms' selects («Opera property», «Crs hotel code») and the
    // «Front office» input, the dictionary's «Ask the agent», the booking edit's room dialog (see
    // fixRoomType).
    test('1 · onboarding MRU01 → XMAR', async ({}, info) => {
        test.setTimeout(45 * 60_000)
        const ui = new Ui(page, info)

        // Ten demo bookings in the CRS (verified live: the toolbar button; the dialog says «Crear»).
        await ui.go(`${D}/booking/bookings`, /\+ 10 reservas demo/)
        await ui.click(ui.button('+ 10 reservas demo'), 2_500)
        await ui.confirmIfAsked(8_000)
        await ui.reload(/Pms reservation id/)
        await ui.waitText(/Confirmed/, 'the 10 demo bookings never showed')
        await ui.checkpoint('bookings-10')

        // PMS → front office: XMAR → MRU01.
        await ui.go(`${C}/integrations/frontoffice`, /New/)
        await ui.click(ui.button('New'), 5_000)
        await ui.select('Opera property', /XMAR/) // verified live (2026-10-06)
        await ui.select('Front office', /MRU01/)  // a lookup of the front office's hotels, not an input
        await ui.checkpoint('fo-integration-form')
        await ui.click(ui.button('Save'), 6_000)
        await ui.pollPage(`${C}/integrations/frontoffice/XMAR`, /Onboarding/, t => /A person to activate it/.test(t),
            'the front office integration never got ready to activate', 5 * 60_000)
        await ui.more('Activate')
        await ui.confirmIfAsked()
        await ui.pollPage(`${C}/integrations/frontoffice/XMAR`, /Onboarding/, t => /Waiting for \| Nothing/.test(t),
            'the front office integration never became active', 3 * 60_000)
        await ui.checkpoint('fo-integration-active')

        // CRS → PMS: MRU01 → XMAR.
        await ui.go(`${C}/integrations/registry`, /New/)
        await ui.click(ui.button('New'), 5_000)
        await ui.select('Crs hotel code', /MRU01/) // UNVERIFIED (probe denied)
        await ui.select('Opera property', /^XMAR/) // UNVERIFIED (probe denied)
        await ui.checkpoint('crs-integration-form')
        await ui.click(ui.button('Save'), 6_000)
        await ui.pollPage(`${C}/integrations/registry/MRU01`, /Onboarding/, t => /A person to approve the mapping/.test(t),
            'the CRS integration never stopped at the mapping', 5 * 60_000)
        await ui.checkpoint('crs-waiting-mapping')

        // The dictionary: the agent proposes, a person approves everything it proposed.
        await ui.go(`${C}/mapping/dictionary?integration=MRU01`, /Crs code/)
        await ui.click(ui.button('Ask the agent'), 4_000) // UNVERIFIED (probe denied)
        await ui.pollPage(`${C}/mapping/dictionary?integration=MRU01`, /Crs code/,
            t => !/\| Unmapped \|/.test(t) && /\| Proposed \|/.test(t),
            'the agent never proposed every code', 8 * 60_000, 20_000)
        await ui.checkpoint('dictionary-proposed')
        // ⋯ → Approve with nothing selected approves every proposal the filters show.
        await ui.more('Approve')
        await ui.confirmIfAsked(8_000)
        await ui.checkpoint('dictionary-approved')

        // Backfill, then activate.
        await ui.pollPage(`${C}/integrations/registry/MRU01`, /Onboarding/, t => /A person to activate it/.test(t),
            'the CRS integration never got ready to activate (backfill)', 10 * 60_000)
        await ui.more('Activate')
        await ui.confirmIfAsked(6_000)
        await ui.checkpoint('crs-integration-active')

        // Opera may refuse a seeded booking's room type (RSV00138): change it, as the demo does.
        await settleRejections(ui)

        // Every booking has its Opera number (listing: … | Status | Version | Pms reservation id).
        await ui.pollPage(`${D}/booking/bookings`, /Pms reservation id/,
            t => (t.match(/\| Confirmed \| \d+ \| \d{6,} \|/g) ?? []).length >= 10,
            'not every demo booking reached Opera', 5 * 60_000)
        await ui.checkpoint('bookings-in-opera')
        await ui.go(`${FO}/reservas`, /Reservas/)
        await ui.checkpoint('front-office-stays')
    })

    // ── 2 ──────────────────────────────────────────────────────────────────────────────────────────
    // Not verified live: the wizard's room/guest dialogs (see createBooking), the front office's
    // «Escanear» on a pax row, the Consolidations listing's text.
    test('2 · returning customer: a duplicate, consolidated by scanning the document', async ({}, info) => {
        test.setTimeout(30 * 60_000)
        const ui = new Ui(page, info)

        // A customer of the chain: the holder of the first demo booking.
        await ui.go(`${D}/booking/bookings`, /Pms reservation id/)
        const original = (await ui.text()).match(/Pms reservation id \| ([A-Z0-9]{6}) \|/)?.[1]
        expect(original, 'no demo booking in the listing').toBeTruthy()
        S.original = original!
        await ui.go(`${D}/booking/bookings/${original}`, /In other systems/)
        const holder: Holder = {
            first: field(await ui.text(), 'Holder first name'),
            last: field(await ui.text(), 'Holder last name'),
            email: field(await ui.text(), 'Holder email'),
            phone: field(await ui.text(), 'Holder phone'),
            nationality: field(await ui.text(), 'Holder nationality') || 'ES',
        }
        S.originalHolder = holder
        const originalCustomer = await holderCustomer(ui, original!)
        await ui.checkpoint('original-booking')

        // The same person books again: same name and phone, another email → a provisional duplicate.
        const duplicate = await createBooking(ui, {
            arrival: day(35), departure: day(38),
            holder: { ...holder, email: `${holder.first}.${holder.last}.${RUN}@example.org`.toLowerCase().replace(/\s+/g, '') },
            room: ROOM_TYPES[0], rate: 'DIRECTA', board: 'DESAYUNO', adults: 2,
            guests: [[holder.first, holder.last]],
        }, 'f2')
        S.duplicate = duplicate
        await ensureInOpera(ui, duplicate)
        const duplicateCustomer = await holderCustomer(ui, duplicate)
        expect(duplicateCustomer, 'the returning customer should first be a separate (provisional) customer').not.toBe(originalCustomer)
        await ui.checkpoint('duplicate-customer')

        // At the desk: scan the holder's document on the ORIGINAL booking first (the chain customer
        // gets a document), then on the NEW one — the demo scanner reads that same document, and the
        // MDM consolidates (docs/poc-acl/demo.md §9 «O en recepción, al escanear el documento»).
        await scanHolder(ui, original!)
        await scanHolder(ui, duplicate)

        // Customers → Consolidations shows it, vía SCAN.
        await ui.pollPage(`${C}/customers/consolidations`, /Survivor|Absorbed|Via/i,
            t => new RegExp(`${duplicateCustomer}[^]{0,200}SCAN|SCAN[^]{0,200}${duplicateCustomer}`).test(t),
            `the MDM never consolidated ${duplicateCustomer} by scanning`, 8 * 60_000)
        await ui.checkpoint('consolidation-scan')

        // Both bookings now point at the same customer (the survivor) — the code Opera and the front
        // office get. The front office does not show the code on an arriving stay («En otros
        // sistemas» is hidden in the arrival), so it is read where it is shown: the CRS booking.
        await expect.poll(async () => (await holderCustomer(ui, duplicate)) === (await holderCustomer(ui, original!)),
            { message: 'the two bookings never shared one customer code', timeout: 5 * 60_000, intervals: [15_000] }).toBe(true)
        await ui.checkpoint('same-customer')
    })

    // ── 3 ──────────────────────────────────────────────────────────────────────────────────────────
    // Not verified live: the pax row's «Editar»/«A mano», the kárdex field «Teléfono», «Guardar kárdex»,
    // the change requests listing's columns.
    test('3 · the front office changes the holder\'s data → a Salesforce Case', async ({}, info) => {
        test.setTimeout(20 * 60_000)
        const ui = new Ui(page, info)
        const locator = S.duplicate!
        const newPhone = '+34 6' + String(Math.floor(Math.random() * 1e8)).padStart(8, '0')

        await openStay(ui, locator)
        // UNVERIFIED (probe denied): the holder is the first pax row; after the scan its second action is «Editar».
        const edit = page.getByRole('button', { name: /^(Editar|A mano)$/ }).first()
        await ui.click(edit, 4_000)
        await ui.type('Teléfono', newPhone) // UNVERIFIED (probe denied)
        await ui.click(ui.button('Guardar kárdex'), 6_000)
        await ui.waitText(/Pendiente de Salesforce/, 'the holder never showed «Pendiente de Salesforce»')
        await ui.checkpoint('kardex-pending')

        // The MDM opens a Case in Salesforce: Clientes → Solicitudes de cambio shows its id.
        const caseId = await ui.pollPage(`${D}/customers/changes`, /Solicitudes de cambio/,
            t => t.match(new RegExp(`${escape(newPhone)} \\| Pendiente \\| (500[A-Za-z0-9]{12,15})`))?.[1],
            'the change never became a Case in Salesforce', 5 * 60_000)
        await ui.checkpoint('change-request-case')

        // The decision is taken in Salesforce's UI, which needs MFA: a test fixture takes it instead.
        const sf = SalesforceFixture.tryCreate()
        test.skip(!sf, `Case ${caseId} exists; approving it needs Salesforce (its UI needs MFA) and the ` +
            'ec-salesforce secret could not be read with kubectl — approve it by hand to finish flow 3')
        await sf!.approveCase(caseId)

        // It comes back on its own: the request is decided and the desk loses the mark.
        await ui.pollPage(`${D}/customers/changes`, /Solicitudes de cambio/,
            t => new RegExp(`${escape(newPhone)} \\| (?!Pendiente)`).test(t),
            'the MDM never learnt the decision', 5 * 60_000)
        await openStay(ui, locator)
        await expect.poll(async () => !/Pendiente de Salesforce/.test(await ui.text()),
            { message: 'the front office kept «Pendiente de Salesforce»', timeout: 5 * 60_000, intervals: [15_000] }).toBe(true)
        await ui.checkpoint('kardex-approved')
    })

    // ── 4 ──────────────────────────────────────────────────────────────────────────────────────────
    // Not verified live: the stay's text after the no show.
    test('4 · no show', async ({}, info) => {
        test.setTimeout(20 * 60_000)
        const ui = new Ui(page, info)
        const locator = await createBooking(ui, {
            arrival: day(0), departure: day(2),
            holder: { first: 'Nora', last: `Lindqvist ${RUN}`, email: `nora.${RUN}@example.org`, phone: '+47 41234567', nationality: 'NO' },
            room: ROOM_TYPES[0], rate: 'DIRECTA', board: 'DESAYUNO', adults: 2,
            guests: [['Nora', `Lindqvist ${RUN}`], ['Erik', `Lindqvist ${RUN}`]],
        }, 'f4')
        await ensureInOpera(ui, locator)
        await openStay(ui, locator)
        await ui.checkpoint('stay-arriving')
        // Every pax a no show → the reservation is one.
        for (let i = 0; i < 2; i++) {
            // Verified live (2026-10-06): the pax's «No show» asks in the front office's own dialog
            // «¿Marcar no show?» → «Marcar no show» (not Mateu's confirm).
            await ui.click(page.getByRole('button', { name: 'No show', exact: true }).first(), 2_500)
            await ui.click(page.getByRole('dialog', { name: '¿Marcar no show?' }).getByRole('button', { name: 'Marcar no show', exact: true }), 4_000)
        }
        await ui.checkpoint('no-show-marked')
        await ui.pollPage(`${D}/booking/bookings/${locator}`, /In other systems/, t => /\| Cancelled \|/.test(t),
            'the CRS never cancelled the no show', 5 * 60_000)
        await ui.checkpoint('crs-cancelled-no-show')
        await openStay(ui, locator)
        await ui.waitText(/no show/i, 'the front office never showed the no show')
        await ui.checkpoint('fo-no-show')
    })

    // ── 5 ──────────────────────────────────────────────────────────────────────────────────────────
    // Not verified live: every walk-in field label (taken from the 2026-10-04 page text), its buttons.
    test('5 · walk-in (guided process in the front office)', async ({}, info) => {
        test.setTimeout(20 * 60_000)
        const ui = new Ui(page, info)
        await ui.go(`${FO}/reservas`, /Walk-in/)
        await ui.click(page.getByRole('button', { name: /Walk-in/ }).first(), 6_000)
        await ui.click(ui.button('Inicio'), 4_000) // UNVERIFIED (probe denied)
        // UNVERIFIED (probe denied): the options read «Junior suite con balcón y vista al mar (JS-SEA)».
        await ui.select('Habitación', new RegExp(`\\(${ROOM_TYPES[0]}\\)`))
        await ui.select('Tarifa', /\(DIRECTA\)/)
        await ui.select('Régimen', /\(DESAYUNO\)/)
        await ui.checkpoint('walk-in-stay')
        await ui.click(ui.button('Continuar'), 6_000)
        await ui.waitText(/Precio del CRS/, 'the walk-in never priced the stay')
        await ui.click(ui.button('Continuar'), 5_000)
        await ui.type('Nombre', 'Paolo')
        await ui.type('Apellidos', `Ricci ${RUN}`)
        await ui.type('Email', `paolo.${RUN}@example.org`)
        await ui.type('Teléfono', '+39 3471234567')
        await ui.type('Nacionalidad', 'IT')
        await ui.type('Documento', `YA${Math.floor(1e6 + Math.random() * 8e6)}`)
        await ui.checkpoint('walk-in-holder')
        await ui.click(ui.button('Continuar'), 5_000)
        await ui.click(ui.button('Confirmar walk-in'), 8_000)
        const crs = (await ui.text()).match(/El CRS la ha reservado como ([A-Z0-9]{6})/)?.[1]
        expect(crs, 'the walk-in never got its CRS booking').toBeTruthy()
        await ui.checkpoint('walk-in-confirmed')
        await ensureInOpera(ui, crs!)
        await ui.go(`${D}/booking/bookings/${crs}`, /In other systems/)
        expect(await after(page, /Channel code/, 80)).toContain('WALKIN')
        await ui.checkpoint('crs-walk-in')
    })

    // ── 6 ──────────────────────────────────────────────────────────────────────────────────────────
    // Not verified live: «Apagar la caída de Opera» (the menu only offers it while the outage is on),
    // the inbox notice's title, the journey's text.
    test('6 · Opera outage: the booking waits, retries and is written once', async ({}, info) => {
        test.setTimeout(30 * 60_000)
        const ui = new Ui(page, info)
        await ui.go(`${C}/demo/admin`, /Simulación/)
        // Verified live: the field's label and the menu item.
        await ui.type('Aviso de reintentos tras (min)', '2')
        await ui.more('Aplicar el umbral de aviso')
        await ui.confirmIfAsked()
        await ui.more('Encender la caída de Opera')
        await ui.confirmIfAsked()
        await ui.waitText(/Simulación: Opera no responde/, 'the outage banner never showed')
        await ui.checkpoint('outage-on')
        try {
            const locator = await createBooking(ui, {
                arrival: day(40), departure: day(43),
                holder: { first: 'Hanna', last: `Berg ${RUN}`, email: `hanna.${RUN}@example.org`, phone: '+49 1701234567', nationality: 'DE' },
                // A room type XMAR has on these dates (JS-SEA is refused there: RSV00138, both runs of
                // 2026-10-07): if Opera refused it after the outage, the version written would be the
                // corrected one, not the retried one the journey has to show.
                room: OUTAGE_ROOM, rate: 'DIRECTA', board: 'DESAYUNO', adults: 2,
                guests: [['Hanna', `Berg ${RUN}`], ['Jonas', `Berg ${RUN}`]],
            }, 'f6')
            S.outageBooking = locator
            // The step retries; after the threshold the inbox says so (RETRYING_TOO_LONG).
            await ui.pollPage(`${C}/inbox/pending`, /Title/, t => new RegExp(`${locator}[^|]*keeps failing|keeps failing[^|]*${locator}`).test(t),
                'the inbox never said the write keeps failing', 8 * 60_000, 20_000)
            await ui.checkpoint('inbox-retrying-too-long')
        } finally {
            // Always switch the simulation off, even when the step above failed.
            await ui.go(`${C}/demo/admin`, /Simulación/)
            const items = await ui.moreItems()
            if (items.some(i => /Apagar la caída de Opera/.test(i))) {
                await ui.more('Apagar la caída de Opera') // UNVERIFIED (probe denied)
                await ui.confirmIfAsked()
            }
            await ui.checkpoint('outage-off')
        }
        const locator = S.outageBooking!
        await ensureInOpera(ui, locator)
        await ui.go(`${D}/booking/bookings/${locator}`, /In other systems/)
        expect((await ui.text()).match(/Room 1 · ([A-Z-]+)/)?.[1],
            `Opera refused ${OUTAGE_ROOM} on these dates after the outage, so the retried version was replaced: set DEMO_OUTAGE_ROOM`).toBe(OUTAGE_ROOM)
        // Written once, after retries: the journey says how it ended.
        await ui.go(`${D}/journey/bookings/${locator}`, /Recorrido de/)
        await expect.poll(async () => {
            const t = await ui.text()
            if (!/tras reintentos/.test(t)) await ui.button('Actualizar').click().catch(() => {})
            return /En Opera[^|]*tras reintentos/.test(t)
        }, { message: 'the journey never showed the booking in Opera after retries', timeout: 3 * 60_000, intervals: [15_000] }).toBe(true)
        await ui.checkpoint('journey-after-retries')
    })

    // ── 7 ──────────────────────────────────────────────────────────────────────────────────────────
    // Not verified live: the cancellation dialog's reason select and its «Cancel booking» button.
    test('7 · modify and cancel from the CRS', async ({}, info) => {
        test.setTimeout(25 * 60_000)
        const ui = new Ui(page, info)
        const locator = S.outageBooking!
        // One more night (dates seen working by label on 2026-10-04), and another room type.
        await ui.go(`${D}/booking/bookings/${locator}/edit`, /Departure/)
        await ui.type('Departure', day(44))
        const next = ROOM_TYPES.find(r => r !== ROOM_TYPES[0]) ?? ROOM_TYPES[0]
        await changeRoomType(ui, next, false)
        await ui.click(ui.button('Save'), 6_000)
        await ui.checkpoint('crs-modified')
        await ensureInOpera(ui, locator, 45_000)
        await openStay(ui, locator)
        await ui.waitText(/4N/, 'the front office never showed the four nights')
        await ui.checkpoint('fo-modified')

        // Cancel it.
        await ui.go(`${D}/booking/bookings/${locator}`, /In other systems/)
        await ui.more('Cancel booking')
        await ui.select('Reason', /^OTR/) // UNVERIFIED (probe denied): the dialog's reason field label.
        await ui.click(page.getByRole('button', { name: 'Cancel booking', exact: true }).last(), 6_000)
        await ui.pollPage(`${D}/booking/bookings/${locator}`, /In other systems/, t => /\| Cancelled \|/.test(t),
            'the CRS never cancelled it', 3 * 60_000)
        await ui.checkpoint('crs-cancelled')
        await openStay(ui, locator)
        await ui.waitText(/[Cc]ancelad/, 'the front office never showed the cancellation')
        await ui.checkpoint('fo-cancelled')
    })

    // ── 8 ──────────────────────────────────────────────────────────────────────────────────────────
    // Not verified live: the rate plan form's «Save», the dictionary's «Withdraw», row selection.
    test('8 · a new rate plan with the integration already active (EMPLEADOS-27)', async ({}, info) => {
        test.setTimeout(30 * 60_000)
        const ui = new Ui(page, info)
        const code = 'EMPLEADOS-27'

        // Call center → Catalogue → Rate plans (ec-demo1 #218).
        await page.goto(`${D}/booking/catalogue/ratePlans`, { waitUntil: 'domcontentloaded' })
        await page.waitForTimeout(2_500)
        await ui.signInIfAsked()
        const present = await expect.poll(async () => /Rate plans/.test(await ui.text()), { timeout: 90_000 }).toBe(true).then(() => true, () => false)
        test.skip(!present, 'Call center → Catalogue → Rate plans (/booking/catalogue/ratePlans, ec-demo1 #218) is not deployed')
        await ui.checkpoint('rate-plans')
        if (!new RegExp(`\\| ${code} \\|`).test(await ui.text())) {
            // Verified live (2026-10-06): here «New» is a toolbar button of its own (no ⋯ menu).
            await ui.click(ui.button('New'), 5_000)
            await ui.select('Hotel', /MRU01/)
            await ui.type('Code', code)
            await ui.type('Name', 'Empleados de la cadena de vacaciones 2027')
            // The browser runs in es-ES: Redwood's number field reads «0.5» as 5 (the «.» groups
            // thousands) and the CRS refuses it (2026-10-06 run). The decimal comma is what a user types.
            await ui.type('Factor', '0,5')
            await ui.checkpoint('rate-plan-form')
            await ui.click(ui.button('Save'), 6_000)
            await ui.go(`${D}/booking/catalogue/ratePlans`, /Rate plans/)
            expect(await ui.pageUntil(new RegExp(`\\| ${code} \\|`)), `the rate plan ${code} was not created`).toBe(true)
        }

        // A code mapped earlier (the catalogue may have carried it into flow 1's approval) would not
        // wait: withdraw it, as the 2026-10-04 run did, so the booking shows the new-code path.
        await ui.go(`${C}/mapping/dictionary?integration=MRU01`, /Crs code/)
        if (await ui.pageUntil(new RegExp(`${code} \\| [^|]+ \\| \\d+ \\| Approved`))) {
            await ui.selectRow(code)
            await ui.more('Withdraw')
            await ui.confirmIfAsked(6_000)
        }

        const locator = await createBooking(ui, {
            arrival: day(47), departure: day(50),
            holder: { first: 'Lucía', last: `Navarro ${RUN}`, email: `lucia.${RUN}@example.org`, phone: '+34 611223344', nationality: 'ES' },
            room: 'JS-STD', rate: code, board: 'SOLO-ALOJAMIENTO', adults: 2,
            guests: [['Lucía', `Navarro ${RUN}`], ['Pablo', `Navarro ${RUN}`]],
        }, 'f8')
        const cause = `MISSING_MAPPING:MRU01:RATE_PLAN:${code}`
        await ui.pollPage(`${C}/mapping/causes`, /Key/, t => new RegExp(`${cause} \\|[^]{0,300}?\\| Open`).test(t),
            `the booking never waited on ${cause}`, 5 * 60_000)
        await ui.checkpoint('cause-missing-mapping')

        await ui.go(`${C}/mapping/dictionary?integration=MRU01`, /Crs code/)
        await ui.click(ui.button('Ask the agent'), 4_000)
        const proposed = new RegExp(`${code} \\| 432040HLXMU \\| \\d+ \\| Proposed`)
        await expect.poll(async () => { await ui.reload(/Crs code/); return ui.pageUntil(proposed) },
            { message: `the agent never proposed 432040HLXMU for ${code}`, timeout: 5 * 60_000, intervals: [15_000] }).toBe(true)
        // Approve only that one: select its row first (with nothing selected, Approve takes them all).
        await ui.selectRow('432040HLXMU')
        await ui.checkpoint('proposal-selected')
        await ui.more('Approve')
        await ui.confirmIfAsked(8_000)
        await ensureInOpera(ui, locator)
        await ui.pollPage(`${C}/mapping/causes`, /Key/, t => new RegExp(`${cause} \\|[^]{0,300}?\\| Resolved`).test(t),
            `${cause} never resolved`, 3 * 60_000)
        await ui.checkpoint('cause-resolved')
    })

    // ── 9 ──────────────────────────────────────────────────────────────────────────────────────────
    // Verified live on the local cluster (2026-10-09): the check-in's «Escanear documento», «Simular
    // pasaporte nuevo», the room cards, «Enviar a tablet», «Confirmar check-in»; the stay's «Check-out» and
    // «Confirmar — €…». Since 2026-10-13 the desk confirms and searches from the kárdex editor («Rellenar a
    // mano» / «Edit», its «Nº Riu Class», «Nombre»/«Apellidos»/«Fecha de nacimiento», «Save») — not yet run
    // live.
    test('9 · a known customer recognised at the check-in, and their stay in the history and Riu Class', async ({}, info) => {
        test.setTimeout(75 * 60_000)
        const ui = new Ui(page, info)

        // Fixture, as flow 3's Case approval: three holders of the coming arrivals made known customers
        // — a document in the MDM, a Riu Class member, past stays — by the demo's seeding (kubectl decides
        // which cluster). Each one then books again with another email, as a tour operator's booking comes:
        // unidentified, a provisional customer of its own.
        const seeded = JSON.parse(execFileSync('python3', [`${__dirname}/../../deploy/demo/ec1.py`, 'seed',
            'known-customers', '--count', '3', '--json'], { encoding: 'utf8', timeout: 180_000 })) as Known[]
        expect(seeded.length, 'the seeding found no arrival with a chain customer: walk the onboarding first').toBe(3)
        const bookings: string[] = []
        for (const [i, k] of seeded.entries()) {
            const [first, ...rest] = k.guestName.split(' ')
            const last = rest.join(' ')
            const locator = await createBooking(ui, {
                arrival: day(0), departure: day(2),
                holder: { first, last, email: `${first}.${last}.${RUN}.f9@example.org`.toLowerCase().replace(/\s+/g, ''),
                    phone: '', nationality: '' },
                room: ROOM_TYPES[1], rate: 'DIRECTA', board: 'DESAYUNO', adults: 1, guests: [[first, last]],
                channel: 'CALLCENTER',
            }, `f9-${i}`)
            await ensureInOpera(ui, locator)
            bookings.push(locator)
        }
        const [byDocument, byPassport, byHand] = bookings
        const [known1, known2, known3] = seeded

        // 0 · Before they arrive: the arrivals list marks the returning customers due by tomorrow — prepared
        // ahead (the seeding briefs them), with their stays and their Riu Class tier.
        const tomorrow = new Date(Date.now() + 86_400_000).toLocaleDateString('sv')   // ISO, local
        if (seeded.some(k => k.arrival && k.arrival <= tomorrow)) {
            await ui.go(`${FO}/reservas`, /Reservas/)
            expect(await ui.pageUntil(/Repite · \d+ estancias?/), 'the arrivals list marked no returning customer').toBe(true)
        } else {
            info.annotations.push({ type: 'note', description: 'no seeded customer arrives by tomorrow: the arrivals briefing not checked' })
        }
        // «¿es usted X?» with one candidate, «¿es usted uno de estos clientes?» and their names with several
        // (an environment where two customers share the name and the birth date): either way, X is offered
        // the panel, not a message toast that may still be on screen: «Cliente conocido — Name»
        const recognised = (name: string) => async () => (await ui.text()).includes(`Cliente conocido — ${name}`)
        const offered = (name: string) => async () => {
            const t = await ui.text()
            return /Posible cliente conocido/.test(t) && t.includes(name) && !/Cliente conocido — /.test(t)
        }
        const checkIn = async (locator: string) => {
            await ui.go(`${FO}/checkin/${locator}`, /Check-In/)
            await ui.waitText(/Documento|Habitación/, `the check-in of ${locator} never opened`)
        }
        // what the guest says, written in the pax's kárdex: saved, a Riu Class number confirms who they are
        const riuClassInTheKardex = async (number: string) => {
            await ui.click(ui.button('Edit'), 4_000)
            await ui.page.getByLabel('Nº Riu Class', { exact: true }).first().fill(number)
            await ui.click(ui.button('Save'), 6_000)
        }

        // 1 · The document we already know: certainty, with their history and their Riu Class tier.
        await checkIn(byDocument)
        await ui.click(ui.button('Escanear documento'), 9_000)
        await expect.poll(recognised(known1.guestName), { message: 'a known document did not recognise the customer', timeout: 60_000 })
            .toBe(true)
        await ui.waitText(/\d+ estancias · \d+ noches/, 'the known customer showed no stay history')
        await ui.checkpoint('f9-known-by-document')

        // 2 · A new passport of the same person: only possible, never the history — until the guest gives
        // their Riu Class number. The MDM then consolidates the provisional code into them (DESK_CONFIRMED).
        await checkIn(byPassport)
        await ui.click(ui.button('Simular pasaporte nuevo'), 9_000)
        await expect.poll(offered(known2.guestName), { message: 'a new passport did not ask «¿es usted…?»', timeout: 60_000 })
            .toBe(true)
        await ui.checkpoint('f9-possible-by-passport')
        await riuClassInTheKardex(known2.riuClass)
        await expect.poll(recognised(known2.guestName), { message: 'the Riu Class number did not confirm the customer', timeout: 60_000 })
            .toBe(true)
        await ui.checkpoint('f9-confirmed-by-riu-class')
        await ui.pollPage(`${C}/customers/consolidations`, /Survivor|Absorbed|Via/i,
            t => new RegExp(`${known2.customerId}[^]{0,300}DESK_CONFIRMED|DESK_CONFIRMED[^]{0,300}${known2.customerId}`).test(t),
            `the MDM never consolidated the confirmed guest into ${known2.customerId}`, 5 * 60_000)

        // 3 · No document: by name and birth date only possible; by Riu Class number, certainty.
        const pointsBefore = await riuClassPoints(ui, known3.riuClass) // read now: it leaves the check-in
        // the kárdex filled in by hand: saved with name, surnames and birth date, it looks for who they may be
        await checkIn(byHand)
        await ui.click(ui.button('Rellenar a mano'), 4_000)
        const [first3, ...rest3] = known3.guestName.split(' ')
        await ui.page.getByLabel('Nombre', { exact: true }).first().fill(first3)
        await ui.page.getByLabel('Apellidos', { exact: true }).first().fill(rest3.join(' '))
        const [y, m, d] = known3.birthDate.split('-')
        const birth = ui.page.getByLabel('Fecha de nacimiento').first()
        await birth.fill(`${d}/${m}/${y}`)
        await birth.press('Enter')
        await ui.click(ui.button('Save'), 6_000)
        await expect.poll(offered(known3.guestName), { message: 'the search by name and birth date found nobody', timeout: 60_000 })
            .toBe(true)
        await riuClassInTheKardex(known3.riuClass)
        await expect.poll(recognised(known3.guestName), { message: 'the Riu Class number did not confirm the customer', timeout: 60_000 })
            .toBe(true)
        await ui.checkpoint('f9-known-by-hand')

        // The stay of the customer found by hand, to the end: the check-in (document, room, signature) and
        // the check-out — the closed stay is the known customer's, in the history and in Riu Class.
        // the kárdex saved by hand is the pax's identity, and the check-in stays on them: on to the room
        await ui.click(ui.button('Siguiente'), 4_000)
        await ui.waitText(/Lista · /, 'the check-in never showed the rooms')
        await ui.click(ui.page.getByText(/^\d{3,4}$/).first(), 4_000) // the first room's card: ready ones come first
        for (let i = 0; i < 2; i++) await ui.click(ui.button('Siguiente'), 3_500)
        await ui.click(ui.button('Enviar a tablet'), 9_000)
        await ui.click(ui.button('Confirmar check-in'), 10_000)
        await ui.go(`${FO}/reservas/${byHand}`, /Huéspedes|Check-out/)
        await ui.click(ui.button('Check-out'), 4_000)
        await ui.click(ui.page.getByRole('button', { name: /^Confirmar — / }).first(), 8_000)
        await ui.waitText(/[Cc]heck-out enviado|salió el/, `the check-out of ${byHand} never went`)
        await ui.checkpoint('f9-checked-out')

        await ui.go(`${D}/history/search`, /Historial de clientes/)
        await ui.type('Código de cliente', known3.customerId)
        // the toolbar's «Buscar», not the menu entry of the same name (Redwood shows both)
        await ui.click(ui.page.getByRole('button', { name: 'Buscar', exact: true }).last(), 5_000)
        // «Recepción» is the origin of a stay closed at the desk; the seeded ones say «Demo»
        await ui.waitText(/MRU01[^]{0,400}Recepción|Recepción[^]{0,400}MRU01/,
            'the closed stay never reached the customer history')
        await ui.checkpoint('f9-history')
        await expect.poll(() => riuClassPoints(ui, known3.riuClass),
            { message: 'the stay never added Riu Class points', timeout: 3 * 60_000, intervals: [15_000] })
            .toBeGreaterThan(pointsBefore)
        await ui.checkpoint('f9-riu-class-points')
    })

    test('10 · the guest checks in on the lobby tablet (simulated), and the till takes an advance', async ({}, info) => {
        test.setTimeout(30 * 60_000)
        const ui = new Ui(page, info)
        const locator = await createBooking(ui, {
            arrival: day(0), departure: day(2),
            holder: { first: 'Tablet', last: `Guest ${RUN}`, email: `tablet.${RUN}.f10@example.org`, phone: '', nationality: '' },
            room: ROOM_TYPES[1], rate: 'DIRECTA', board: 'DESAYUNO', adults: 1, guests: [['Tablet', `Guest ${RUN}`]],
            channel: 'CALLCENTER',
        }, 'f10')
        await ensureInOpera(ui, locator)

        // «Check-in en tableta» (the toolbar's overflow in Redwood): the guest's five steps, simulated
        await openStay(ui, locator)
        let tablet = ui.button('Check-in en tableta')
        if (!(await tablet.isVisible().catch(() => false))) {
            await ui.click(ui.page.getByRole('button', { name: 'Más acciones' }).first(), 1_000)
            tablet = ui.page.getByText('Check-in en tableta', { exact: true }).first()
        }
        await ui.click(tablet, 1_000)
        await ui.waitText(/Check-in en tableta completado|Registro firmado en la tableta/, 'the tablet check-in never finished')
        await expect.poll(async () => {
            await openStay(ui, locator)
            const t = await ui.text()
            return /Firmada por el huésped/.test(t) && /Preautorización completada/.test(t)
        }, { message: 'the stay does not show the tablet\'s signature and guarantee', timeout: 90_000 }).toBe(true)

        // the till: an advance of 100, with its receipt
        await ui.go(`${FO}/caja/${locator}`, /Saldo pendiente/)
        await ui.page.getByLabel('Importe').first().fill('100')
        await ui.page.getByLabel('Importe').first().press('Tab')
        await ui.click(ui.button('Cobrar'), 3_000)
        await ui.waitText(/recibo nº \d+/, 'the till took no payment')
        await ui.waitText(/Cobrado [1-9][\d.]*,\d\d/, 'the account does not show what was paid')
        // Into Opera's folio. Opera checks in only arrivals on its business date (XMAR's does not follow the
        // calendar): the demo's seeding books one, with the rooms Opera has ready.
        const seeded = execFileSync('bash', [`${__dirname}/../../deploy/demo/demo-prep.sh`, 'seed', 'arriving-opera-today'],
            { encoding: 'utf8', timeout: 600_000 })
        const opera = seeded.match(/Created: (\w+)/)?.[1] ?? seeded.match(/Already there: (\w+)/)?.[1]
        const rooms = seeded.match(/inspected and vacant: ([\d, ]+)/)?.[1]?.split(',').map(r => r.trim()).filter(Boolean) ?? []
        expect(opera, 'the seeding gave no booking on Opera\'s business date').toBeTruthy()
        expect(rooms.length, 'Opera has no room ready for it').toBeGreaterThan(0)

        // an advance before the check-in: it waits until Opera has the guests in the house
        await ui.go(`${FO}/caja/${opera}`, /Saldo pendiente/)
        await ui.page.getByLabel('Forma de pago').first().click()
        await ui.page.getByRole('option', { name: 'Efectivo' }).first().click()
        await ui.page.getByLabel('Importe').first().fill('50')
        await ui.page.getByLabel('Importe').first().press('Tab')
        await ui.click(ui.button('Cobrar'), 3_000)
        await ui.waitText(/recibo nº \d+/, 'the till took no advance')
        await expect.poll(async () => {
            await ui.go(`${FO}/caja/${opera}`, /Saldo pendiente/)
            return /Opera: (pendiente|rechazado)/.test(await ui.text())
        }, { message: 'the advance never went to Opera', timeout: 3 * 60_000, intervals: [15_000] }).toBe(true)

        // the desk's check-in: a room Opera has ready, every pax's document, the signature, confirm
        await ui.go(`${FO}/reservas/${opera}`, /Huéspedes|Reserva/)
        await ui.click(ui.button('Cambiar').first(), 3_000)
        await ui.click(ui.page.getByText(rooms[rooms.length - 1], { exact: true }).first(), 6_000)
        await ui.go(`${FO}/checkin/${opera}`, /Check-In/)
        // each pax's scan (a recognised one keeps the wizard on it a turn), the extras, the confirmation
        let signed = false
        for (let i = 0; i < 16; i++) {
            const sign = ui.button('Enviar a tablet')
            if (!signed && await sign.isVisible().catch(() => false)) { await ui.click(sign, 9_000); signed = true; continue }
            const confirm = ui.button('Confirmar check-in')
            if (await confirm.isVisible().catch(() => false)) { await ui.click(confirm, 12_000); break }
            const scan = ui.button('Escanear documento')
            if (await scan.isVisible().catch(() => false)) { await ui.click(scan, 9_000); continue }
            const next = ui.page.getByRole('button', { name: /^\d\/\d$/ }).filter({ hasNotText: '1/' }).first()
            if (/pendiente de escaneo/.test(await ui.text()) && await next.isVisible().catch(() => false)) {
                await ui.click(next, 3_000)
                continue
            }
            await ui.click(ui.button('Siguiente'), 3_500)
        }
        // in the house in Opera, the advance that waited goes on its folio
        await expect.poll(async () => {
            await ui.go(`${FO}/caja/${opera}`, /Saldo pendiente/)
            return /Opera: en el folio/.test(await ui.text())
        }, { message: 'the advance never reached Opera\'s folio', timeout: 6 * 60_000, intervals: [20_000] }).toBe(true)
        await ui.checkpoint('f10-advance-in-operas-folio')

        // and a refund goes back the same way
        await ui.click(ui.button('Devolver'), 3_000)
        await expect.poll(async () => {
            await ui.go(`${FO}/caja/${opera}`, /Saldo pendiente/)
            return /Opera: devuelto/.test(await ui.text())
        }, { message: 'the refund never reached Opera\'s folio', timeout: 4 * 60_000, intervals: [20_000] }).toBe(true)
    })
})

/** What the demo's seeding made known (POST /demo/known-customers, through ec1.py --json). */
interface Known { stayId: string, guestName: string, customerId: string, riuClass: string, documentType: string,
    documentNumber: string, birthDate: string, arrival?: string }

/** A Riu Class member's points, as the data console's «Riu Class → Socios» shows them. */
async function riuClassPoints(ui: Ui, member: string): Promise<number> {
    await ui.go(`${D}/loyalty/members/${member}`, /Puntos/)
    const field = ui.page.getByLabel('Puntos').first()
    const value = (await field.inputValue().catch(() => '')) || ((await ui.text()).match(/Puntos\s*\|?\s*([\d.]+)/)?.[1] ?? '0')
    return Number(value.replace(/[^\d]/g, '') || '0')
}

// ── the shared moves ────────────────────────────────────────────────────────────────────────────────

/** A detail page's field value: the cell after «label |». */
function field(text: string, label: string): string {
    return text.match(new RegExp(`\\| ${label} \\| ([^|]+) \\|`))?.[1]?.trim() ?? ''
}

function escape(s: string) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') }

/** The MDM customer the CRS shows for a booking's holder: «Holder · customer | Name · C-…». */
async function holderCustomer(ui: Ui, locator: string): Promise<string> {
    await ui.go(`${D}/booking/bookings/${locator}`, /In other systems/)
    const id = (await ui.text()).match(/Holder · customer \| [^|]*?(C-[0-9A-F]{12})/)?.[1]
    if (!id) throw new Error(`${locator} shows no holder customer`)
    return id
}

/**
 * The call center's wizard: Bookings → ⋯ New → Stay, Rooms, Guests, Payments, Summary → «Create booking».
 * Verified live: ⋯ → «New» → /booking/newBooking, and the Stay step's fields by label. Not verified
 * live: the room/guest dialogs (their fields by label, their «Save»).
 */
async function createBooking(ui: Ui, b: NewBooking, tag: string): Promise<string> {
    const page = ui.page
    await ui.go(`${D}/booking/bookings`, /Pms reservation id/)
    await ui.more('New')
    await ui.waitText(/Holder first name/, 'the new booking wizard never opened')
    await ui.select('Hotel code', /^MRU01/)
    await ui.select('Channel code', new RegExp(`^${b.channel ?? 'WEB'} `))
    await ui.type('Arrival', b.arrival)
    await ui.type('Departure', b.departure)
    await ui.type('Holder first name', b.holder.first)
    await ui.type('Holder last name', b.holder.last)
    if (b.holder.email) await ui.type('Holder email', b.holder.email)
    if (b.holder.phone) await ui.type('Holder phone', b.holder.phone)
    if (b.holder.nationality) await ui.type('Holder nationality', b.holder.nationality)
    await ui.checkpoint(`${tag}-wizard-stay`)
    await ui.click(ui.button('Next'), 3_000)

    // Verified live (2026-10-06): the «New room» dialog numbers its Line itself (read-only); its
    // comboboxes «Room type code», «Rate plan code», «Board code», the «Adults» textbox and «Save».
    await ui.click(ui.button('Add'), 3_000)
    await ui.select('Room type code', new RegExp(`^${b.room} —`), 'last')
    await ui.select('Rate plan code', new RegExp(`^${b.rate} —`), 'last')
    await ui.select('Board code', new RegExp(`^${b.board} —`), 'last')
    await ui.type('Adults', String(b.adults))
    await ui.checkpoint(`${tag}-wizard-room`)
    await ui.click(page.getByRole('button', { name: 'Save', exact: true }).last(), 3_000)
    await ui.click(ui.button('Next'), 3_000)

    // UNVERIFIED (probe denied): the guest dialog's fields («Room line», «First name», «Last name», «Type»).
    for (const [first, last] of b.guests) {
        await ui.click(ui.button('Add'), 3_000)
        await ui.type('Room line', '1')
        await ui.type('First name', first)
        await ui.type('Last name', last)
        await ui.select('Type', /^Adult/, 'last')
        await ui.click(page.getByRole('button', { name: 'Save', exact: true }).last(), 3_000)
    }
    await ui.click(ui.button('Next'), 3_000) // Payments
    await ui.click(ui.button('Next'), 3_000) // Summary
    await ui.checkpoint(`${tag}-wizard-summary`)
    await ui.click(ui.button('Create booking'), 6_000)
    const createdLocator = async () => (await ui.text()).match(/Booking ([A-Z0-9]{6}) created/)?.[1]
        ?? page.url().match(/\/booking\/bookings\/([A-Z0-9]{6})$/)?.[1]
    await expect.poll(createdLocator, { message: `the wizard never created the booking (${(await ui.text()).match(/(Unknown|Invalid|inválid|rror)[^|]{0,200}/)?.[0] ?? ''})`, timeout: 60_000 }).toBeTruthy()
    const created = (await createdLocator())!
    await ui.checkpoint(`${tag}-created-${created}`)
    return created
}

/**
 * Waits until the booking has its Opera number. On a PMS_REJECTED cause for it (RSV00138: Opera has no
 * rooms of that type those nights — XMAR is a shared UAT and its availability moves), changes the room
 * type through the booking's edit and tries the next one of ROOM_TYPES, as the demo's operator does.
 */
async function ensureInOpera(ui: Ui, locator: string, settle = 0) {
    if (settle) await ui.page.waitForTimeout(settle)
    const tried = new Set<string>()
    for (let attempt = 0; attempt <= ROOM_TYPES.length; attempt++) {
        const end = Date.now() + 4 * 60_000
        let refused = false
        while (Date.now() < end) {
            refused = await rejected(ui, locator)
            if (refused) break
            await ui.go(`${D}/booking/bookings/${locator}`, /In other systems/)
            if (/^\d+$/.test(field(await ui.text(), 'Opera reservation'))) return
            await ui.page.waitForTimeout(15_000)
        }
        if (!refused) throw new Error(`${locator} never reached Opera in 4 min (and no PMS_REJECTED cause for it)`)
        await ui.checkpoint(`${locator}-pms-rejected`)
        await ui.go(`${D}/booking/bookings/${locator}`, /In other systems/)
        tried.add((await ui.text()).match(/Room 1 · ([A-Z-]+)/)?.[1] ?? '')
        const next = ROOM_TYPES.find(r => !tried.has(r))
        if (!next) break
        tried.add(next)
        await ui.go(`${D}/booking/bookings/${locator}/edit`, /Departure/)
        await changeRoomType(ui, next, true)
        // The cause closes on its own when the new version is in Opera («vN is in Opera»); if it is
        // still open after that, Resolve it from the cause, which retries the write.
        await ui.page.waitForTimeout(45_000)
        if (await rejected(ui, locator)) {
            await ui.go(`${C}/mapping/causes/PMS_REJECTED:MRU01:${locator}:upsert-reservation`, /Cause/)
            if (await ui.button('Resolve').isVisible().catch(() => false)) {
                await ui.click(ui.button('Resolve'), 3_000)
                await ui.confirmIfAsked(10_000)
            }
            await ui.page.waitForTimeout(30_000)
        }
    }
    throw new Error(`${locator}: Opera refused every room type tried (${[...tried].filter(Boolean).join(', ')}) — set DEMO_ROOM_TYPES`)
}

/** Flow 1: every seeded booking Opera refused (RSV00138), put right one by one. */
async function settleRejections(ui: Ui) {
    await ui.page.waitForTimeout(60_000)
    await ui.go(`${C}/mapping/causes`, /Key/)
    const locators = new Set([...(await ui.text()).matchAll(/PMS_REJECTED:MRU01:([A-Z0-9]{6}):[^|]+ \|[^]{0,300}?\| Open/g)].map(m => m[1]))
    for (const l of locators) await ensureInOpera(ui, l)
}

async function rejected(ui: Ui, locator: string): Promise<boolean> {
    const p = await ui.page.context().newPage()
    try {
        const u = new Ui(p, ui.info)
        await u.go(`${C}/mapping/causes`, /Key/)
        return new RegExp(`PMS_REJECTED:MRU01:${locator}:[^|]+ \\|[^]{0,300}?\\| Open`).test(await u.text())
    } finally {
        await p.close()
    }
}

/**
 * On the booking's edit page: the room row's pencil → «Room type code» → the new one → the dialog's
 * Save; then (if `save`) the booking's Save. Seen working on 2026-10-04 with the pencil located by its
 * icon class and the select by coordinates; the select is reached by its label here.
 */
async function changeRoomType(ui: Ui, roomType: string, save: boolean) {
    const page = ui.page
    await ui.waitText(/Room type code/, 'the edit page never showed the rooms')
    const pencil = page.locator('oj-c-button, oj-button, button').filter({ has: page.locator('[class*=edit], [class*=pencil]') })
    await ui.click(pencil.first(), 3_000)
    // UNVERIFIED (probe denied): the room dialog's select by label (the run clicked at its coordinates).
    await ui.select('Room type code', new RegExp(`^${roomType} —`), 'last')
    await ui.checkpoint(`room-type-${roomType}`)
    await ui.click(page.getByRole('button', { name: 'Save', exact: true }).last(), 2_500)
    if (save) await ui.click(page.getByRole('button', { name: 'Save', exact: true }).first(), 6_000)
}

/** The front office's stay: from the listing (a cold deep link has not always rendered the reservation). */
async function openStay(ui: Ui, locator: string) {
    await ui.go(`${FO}/reservas`, /Reservas/)
    const row = ui.page.getByText(locator, { exact: true }).first()
    if (await row.isVisible().catch(() => false)) {
        await ui.click(row, 6_000)
    } else {
        await ui.go(`${FO}/reservas/${locator}`, /Huéspedes|Reservas/)
    }
    await ui.waitText(/Huéspedes|Estancia/, `the front office never opened ${locator}`)
}

/** «Escanear» on the holder (the first pax row) and wait for the scan to land. */
async function scanHolder(ui: Ui, locator: string) {
    await openStay(ui, locator)
    // UNVERIFIED (probe denied): the pax row's action button «Escanear» (HuespedesPanel.paxItem),
    // «Reescanear» once the kárdex is complete — then nothing to do.
    const scan = ui.page.getByRole('button', { name: 'Escanear', exact: true }).first()
    if (!(await scan.isVisible().catch(() => false))) return
    await ui.click(scan, 6_000)
    await ui.confirmIfAsked()
    await ui.waitText(/Doc [A-Z0-9]|Kárdex OK|Reescanear/, `the scan never landed on ${locator}`)
    await ui.checkpoint(`scanned-${locator}`)
}
