-- Front-Office Suite persistence schema (PostgreSQL; H2 in the tests). Idempotent: run on every start.
-- One aggregate = one table tree, per Spring Data JDBC conventions.

-- ── Guest aggregate ──────────────────────────────────────────────────────────
create table if not exists guest (
    id                varchar(64)  primary key,
    name              varchar(200) not null,
    document          varchar(50),
    document_verified boolean      not null,
    email             varchar(200),
    phone             varchar(50),
    tier              varchar(20)  not null,
    loyalty_points    int          not null,
    stays             int          not null,
    nights            int          not null,
    years_as_client   int          not null,
    complaints        int          not null,
    hotels            int          not null,
    last_stay_summary varchar(300),
    last_stay_complementary_info varchar(300)
);

create table if not exists guest_preference (
    guest_id varchar(64)  not null references guest (id),
    idx      int          not null,
    text     varchar(200) not null,
    primary key (guest_id, idx)
);

-- ── Stay aggregate ───────────────────────────────────────────────────────────
create table if not exists stay (
    id             varchar(64)  primary key,
    guest_id       varchar(64)  not null references guest (id),
    room_number    varchar(10),
    room_type      varchar(100),
    board          varchar(100),
    check_in       date         not null,
    check_out      date         not null,
    pax            int          not null,
    agency         varchar(200),
    total          decimal(12, 2),
    status         varchar(20)  not null,
    wishes_granted int          not null,
    wishes_total   int          not null,
    vip_note       varchar(200)
);

create table if not exists stay_companion (
    stay_id           varchar(64)  not null references stay (id),
    idx               int          not null,
    companion_id      varchar(64)  not null,
    name              varchar(200) not null,
    document          varchar(64),
    document_verified boolean      not null default false,
    email             varchar(200),
    phone             varchar(50),
    description       varchar(300),
    primary key (stay_id, idx)
);

create table if not exists stay_incident (
    stay_id     varchar(64)  not null references stay (id),
    idx         int          not null,
    code        varchar(64)  not null,
    type        varchar(20),
    icon        varchar(10),
    title       varchar(200) not null,
    description varchar(400),
    status      varchar(20)  not null,
    complaint   boolean      not null,
    opened_at   timestamp,
    resolved_at timestamp,
    primary key (stay_id, idx)
);

create table if not exists stay_add_on (
    stay_id   varchar(64) not null references stay (id),
    add_on_id varchar(64) not null,
    primary key (stay_id, add_on_id)
);

-- ── Folio aggregate ──────────────────────────────────────────────────────────
create table if not exists folio (
    id            varchar(64) primary key,
    stay_id       varchar(64) not null references stay (id),
    preauthorized decimal(12, 2)
);

create table if not exists folio_line (
    folio_id       varchar(64)  not null references folio (id),
    idx            int          not null,
    concept        varchar(200) not null,
    amount         decimal(12, 2),
    included       boolean      not null,
    included_label varchar(50),
    primary key (folio_id, idx)
);
-- A line the desk charges goes onto the PMS's folio (pms-fo, registrar-cargo): its id is the posting's
-- reference there; what it is (kind, the catalogue's code); and whether the desk took it back.
alter table folio_line add column if not exists line_id varchar(40);
alter table folio_line add column if not exists kind varchar(20);
alter table folio_line add column if not exists code varchar(40);
alter table folio_line add column if not exists voided boolean default false;

-- ── Room aggregate ───────────────────────────────────────────────────────────
create table if not exists room (
    room_number      varchar(10)  primary key,
    floor            int          not null,
    type             varchar(100),
    occupancy        varchar(20)  not null,
    housekeeping     varchar(20)  not null,
    maintenance_note varchar(200)
);

-- ── Automation aggregate ─────────────────────────────────────────────────────
create table if not exists automation (
    id            varchar(64)  primary key,
    name          varchar(200) not null,
    ok_count      int          not null,
    warning_count int          not null,
    error_count   int          not null
);

create table if not exists automation_system (
    automation_id varchar(64)  not null references automation (id),
    idx           int          not null,
    name          varchar(100) not null,
    primary key (automation_id, idx)
);

-- ── Reference data: charge & add-on catalogs ─────────────────────────────────
create table if not exists charge_catalog_item (
    code  varchar(20)  primary key,
    name  varchar(200) not null,
    price decimal(12, 2)
);

create table if not exists add_on_catalog_item (
    id             varchar(64)  primary key,
    icon           varchar(10),
    title          varchar(200) not null,
    description    varchar(300),
    price          decimal(12, 2),
    unit           varchar(50),
    included_label varchar(100)
);

-- The last change the desk made to each guest's data, until the chain's master decides it.
create table if not exists guest_kardex (
    guest_id     varchar(64)  primary key,
    request_id   varchar(64),
    status       varchar(20)  not null,
    changes      varchar(500),
    requested_at timestamp,
    decided_at   timestamp,
    synced       boolean      not null
);
-- The change field by field (JSON) and why the master rejected it, if it said.
alter table guest_kardex add column if not exists fields varchar(2000);
alter table guest_kardex add column if not exists reason varchar(500);

-- The stays the desk opened for guests with no reservation, and the CRS booking each became.
create table if not exists walk_in (
    stay_id            varchar(64)   primary key,
    request            varchar(4000) not null,
    expected_total     decimal(12, 2),
    status             varchar(20)   not null,
    locator            varchar(64),
    pms_reservation_id varchar(64),
    message            varchar(500),
    created_at         timestamp     not null,
    booked_at          timestamp
);

-- The desk's check-in operations of each stay (wifi, key, signature, payment, ancillaries closed) and
-- the pax it marked as no-shows — kept with the stay, so a check-in writes them in its transaction.
create table if not exists check_in_ops (
    stay_id     varchar(64) primary key,
    wifi        boolean     not null,
    llave       boolean     not null,
    firma       boolean     not null,
    cobro       boolean     not null,
    extras      boolean     not null,
    no_show_pax varchar(100)
);

-- What the reception agent did here (AuditedActions, HLA F016) and what the front office asks of other
-- services — a change to a customer, a scanned document for the MDM, a no-show for the CRS — go through
-- the shared outbox (supporting/messaging), whose tables it creates itself: outbox_message, and
-- inbox_entry for the commands taken. The front office's own audit_outbox, command_outbox and
-- command_inbox came before it; LegacyOutboxes carries over what they still hold.

-- ── The PMS, through the pms-fo integration ─────────────────────────────────
-- Which PMS reservation each stay is, and the PMS's last modification of it written here: the order
-- guard (a later version is never overwritten by an earlier one). Kept outside the Stay aggregate's
-- mapping, so the desk's saves never touch them.
alter table stay add column if not exists pms_reservation_id varchar(64);
alter table stay add column if not exists pms_version varchar(32);
alter table stay add column if not exists rate_plan varchar(200);
create index if not exists stay_pms_reservation on stay (pms_reservation_id);
-- Where the reception's operations stand in the PMS (the master of the stay), in the desk's words:
-- «Opera: pendiente — check-in enviado», «Opera: en casa», «Opera: rechazado — motivo».
alter table stay add column if not exists pms_state varchar(500);

-- The price the CRS agreed (the rate Opera keeps fixed), next to the stay's total — what Opera bills,
-- with the packages it posts apart (XMAR's BRKFST): the desk sees both when they differ.
alter table stay add column if not exists agreed_total numeric(12,2);

-- The company billed when neither the agency nor the guest is — a partner of the ERP (its code), on
-- Opera's reservation as its Company profile. company_pending: the desk set it and Opera has not shown
-- it back yet, so a projection of the reservation as it was does not undo the desk's choice.
alter table stay add column if not exists company_code varchar(40);
alter table stay add column if not exists company_name varchar(200);
alter table stay add column if not exists company_pending boolean default false;

-- The invoice the PMS issued at the check-out (the PMS is the master of the folio): its number and
-- figures, and its document when the PMS gave one. None: the desk's «Abrir factura» is the front
-- office's proforma, labelled as such.
create table if not exists stay_invoice (
    stay_id      varchar(64)   primary key,
    source       varchar(20)   not null,
    number       varchar(40),
    invoice_date date,
    amount       decimal(12, 2),
    currency     varchar(3),
    pdf          bytea,
    received_at  timestamp     not null
);

-- The PMS's catalogue of the property — room types, rate plans, packages, rooms — in its words: what
-- the stays it sends are read with. Replaced whole each time the integration sends it.
create table if not exists pms_catalogue (
    pms_hotel   varchar(20)  not null,
    type        varchar(20)  not null,
    code        varchar(64)  not null,
    description varchar(300),
    extra       varchar(100),
    primary key (pms_hotel, type, code)
);
create table if not exists pms_catalogue_sync (
    pms_hotel  varchar(20) primary key,
    command_id varchar(64) not null,
    synced_at  timestamp   not null
);


-- ── Reception notices (avisos de recepción) ─────────────────────────────────
-- The reception notices as the notices service sends them (notices): of a customer — by the customer
-- code a guest or a companion carries; Salesforce is their master —, of a reservation (its CRS locator)
-- or of a partner (its code, and the name the PMS gives the agency). The table is the one the
-- customers' notices lived in before (customer-notices, straight from the MDM): its rows are kept, and
-- read as the customers' they are. customer_id is the subject's id; the service's version orders them.
create table if not exists customer_notice (
    notice_id   varchar(64)  primary key,
    customer_id varchar(64)  not null,
    version     bigint       not null,
    text        varchar(300),
    type        varchar(20),
    valid_from  date,
    valid_to    date,
    show_at     varchar(60),
    active      boolean      not null,
    updated_at  timestamp
);
create index if not exists customer_notice_customer on customer_notice (customer_id);
-- What a notice is about, since it can be of more than a customer. The default is what every row
-- before it was; and the show_at of those still says STAY where the notices say IN_HOUSE — both read.
alter table customer_notice add column if not exists subject_type varchar(20) default 'CUSTOMER' not null;
alter table customer_notice add column if not exists subject_name varchar(200);
alter table customer_notice add column if not exists hotel_code varchar(20);

-- What the desk said it read before a check-in (blocking notices) or a check-out (notices, kárdex
-- pending or rejected by Salesforce): the last acknowledgement of each stay and moment, and what it
-- covered — a warning that changed after it is not acknowledged.
create table if not exists stay_notice_ack (
    stay_id         varchar(64)   not null,
    moment          varchar(20)   not null,
    fingerprint     varchar(2000) not null,
    acknowledged_by varchar(200),
    acknowledged_at timestamp,
    primary key (stay_id, moment)
);

-- How the PMS took each charge of the desk and its void (RecordCharge): its posting — the transaction
-- number on the PMS's folio — or its refusal. Its own table, written by the PMS's answers: the folio
-- itself is the desk's.
create table if not exists folio_line_pms (
    line_id      varchar(40)  primary key,
    stay_id      varchar(64)  not null,
    posting_id   varchar(40),
    reversal_id  varchar(40),
    state        varchar(500),
    updated_at   timestamp    not null
);

-- ── Forced check-ins (check-in incompleto) ─────────────────────────────────
-- A check-in the desk forced with steps missing (documents, signature): who, when, why and what was
-- missing then. Open until its steps are completed; the stay cannot check out meanwhile. Reception is
-- told once (overdue_notified_at) when a document is still missing past the traveller's-registration
-- deadline (24 h from the arrival).
create table if not exists forced_check_in (
    stay_id             varchar(64)   primary key,
    forced_by           varchar(200),
    forced_at           timestamp     not null,
    reason              varchar(1000) not null,
    missing             varchar(2000),
    completed_by        varchar(200),
    completed_at        timestamp,
    overdue_notified_at timestamp
);

-- ── Registration rules (reglas de registro del kárdex) ──────────────────────────
-- Which of a guest's data the destination's law requires, as the control plane publishes them
-- (registration-rules): one row per rule, its last version, the whole rule as JSON — kept here, not
-- asked for, so the desk still applies them when the network does not answer (F017).
create table if not exists registration_rule (
    rule_id    varchar(64)   primary key,
    version    bigint        not null,
    scope_key  varchar(40)   not null,
    active     boolean       not null,
    payload    varchar(4000) not null,
    updated_at timestamp
);

-- The registration data of each pax the kárdex keeps apart from the guest's identity: nationality,
-- birth date, address… as the scanner read them or the desk wrote them, one row per field.
create table if not exists pax_registration_data (
    stay_id    varchar(64)  not null,
    pax        int          not null,
    field      varchar(40)  not null,
    field_value varchar(300),
    updated_at timestamp,
    primary key (stay_id, pax, field)
);

-- The nationality of each customer the front office has (a guest or a companion, by customer code),
-- for the flag next to their name: the MDM's golden record, or the walk-in's holder. A null
-- nationality is kept too — asked for and unknown, not asked for again.
create table if not exists customer_nationality (
    customer_id varchar(64) primary key,
    nationality varchar(3),
    source      varchar(20),
    updated_at  timestamp
);

-- Who each pax of a stay was recognised as at the desk (the chain's customer, by document, email,
-- Riu Class number, or only possibly — candidates by name and birth date, as JSON). Kept, not
-- recomputed: the check-in's screens are rebuilt on every interaction, the MDM is asked once per scan.
create table if not exists pax_recognition (
    stay_id       varchar(64)   not null,
    pax           int           not null,
    customer_id   varchar(64),
    customer_name varchar(200),
    certainty     varchar(10)   not null,
    matched_by    varchar(20)   not null,
    candidates    varchar(4000),
    riu_class     varchar(40),
    confirmed_at  timestamp,
    confirmed_by  varchar(200),
    updated_at    timestamp,
    primary key (stay_id, pax)
);

-- The last document scanned of each pax and what the MDM said of it: what a later confirmation of
-- who the pax is sends the MDM again, so that the new document joins the customer confirmed.
create table if not exists pax_scan (
    stay_id           varchar(64)  not null,
    pax               int          not null,
    first_name        varchar(100),
    last_name         varchar(200),
    document_type     varchar(20),
    document_number   varchar(50),
    birth_date        date,
    nationality       varchar(10),
    issuing_country   varchar(10),
    expiry            date,
    lookup            varchar(20),
    found_customer_id varchar(64),
    scanned_at        timestamp,
    primary key (stay_id, pax)
);

-- The summary of each arriving pax the chain knows — their stays and their Riu Class standing —,
-- prepared before they arrive (today's and tomorrow's arrivals): the desk sees it in the arrivals list
-- and at the check-in without asking customer-history and loyalty then. As JSON, as those services said it.
create table if not exists arrival_briefing (
    stay_id       varchar(64)   not null,
    pax           int           not null,
    customer_id   varchar(64)   not null,
    customer_name varchar(200),
    history       varchar(8000) not null,
    loyalty       varchar(1000),
    prepared_at   timestamp     not null,
    primary key (stay_id, pax)
);

-- The rest of each pax's kárdex as the desk filled it in with the guest — what the registration data
-- (pax_registration_data) does not hold: the name split, the Riu Class number, the document's issue date,
-- language, province, fax, advertising consent. A row means the desk filled it in: no row, a provisional kárdex.
create table if not exists pax_kardex (
    stay_id            varchar(64)  not null,
    pax                int          not null,
    first_name         varchar(100),
    last_name          varchar(200),
    riu_class          varchar(40),
    document_issue_date date,
    language           varchar(10),
    province           varchar(100),
    fax                varchar(40),
    marketing_consent  boolean,
    filled_at          timestamp,
    filled_by          varchar(200),
    primary key (stay_id, pax)
);

-- The desk's cashiering on a stay's account: each payment or advance (anticipo) taken — cash, the
-- card terminal (pinpad), a payment link sent by email, a transfer, a manual entry —, its state and
-- its receipt number. The balance still due is the folio's charges less what was captured.
create table if not exists folio_payment (
    id           varchar(64)    not null primary key,
    stay_id      varchar(64)    not null,
    kind         varchar(20)    not null,
    method       varchar(20)    not null,
    amount       decimal(12, 2) not null,
    currency     varchar(3)     not null,
    status       varchar(20)    not null,
    reference    varchar(200),
    link_token   varchar(64),
    email        varchar(200),
    receipt_no   int,
    created_at   timestamp      not null,
    created_by   varchar(200),
    captured_at  timestamp
);
create index if not exists folio_payment_stay on folio_payment (stay_id);

-- A stay's credit at the desk: its limit (none set: the check-in's pre-authorization) and whether it was
-- cancelled — «crédito cancelado»: the limit is zero, nothing goes on the room's account without paying.
create table if not exists folio_credit (
    stay_id      varchar(64)    not null primary key,
    credit_limit decimal(12, 2),
    cancelled    boolean        not null,
    reason       varchar(500),
    changed_by   varchar(200),
    changed_at   timestamp      not null
);

