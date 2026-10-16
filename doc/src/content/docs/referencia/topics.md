---
title: Topics de Kafka
description: Cada topic de ec1 — su dueño, quién produce, quién consume y para qué.
---

El broker es **Redpanda** (`redpanda:19092`), y la consola web está en `kafka.ec1.mateu.io`. Los topics se
crean solos (`auto-create-topics: true` en el binder).

## Los del negocio

Cada uno tiene su esquema versionado en `contracts/schemas/<topic>/v<N>.schema.json` (ver
[Librerías y esquemas](/contratos/librerias-y-esquemas/)). «Lo genera» es el build que escribe el
esquema; el servicio dueño del lenguaje es el `x-owner` del propio esquema.

| Topic | Lo genera (`x-owner`) | Productores | Consumidores | Qué lleva |
| :---- | :---------------- | :---------- | :----------- | :-------- |
| `crs-bookings` | booking | booking | crs-integration | `booking-created` / `-modified` / `-cancelled`: id, hotel, versión |
| `booking-commands` | booking | crs-integration | booking | `annotate-pms-reference` |
| `partners` | erp | erp | crs-integration | Un interlocutor cambió |
| `partner-commands` | erp | integrations, crs-integration | erp | `resync-partner`, `import-partner`, `record-pms-profile` |
| `integration-events` | crs-integration | crs-integration | crs-integration | Los eventos de negocio que enruta a procesos |
| `projection-requests` | contracts-schemas (crs-integration) | integrations | crs-integration | Proyectar una reserva (backfill) |
| `mapping-commands` | contracts-schemas (mapping) | integrations | mapping | `define-equivalence`, `request-agent-proposal`, `resolve-cause-if-open`, `record-partner-profile` |
| `pms-reservations` | contracts-schemas (pms-integration) | pms-integration | integrations | `PmsReservationChanged`: una reserva se escribió en Opera |
| `front-office-commands` | contracts-schemas (front-office) | pms-integration, integrations | front-office | `write-stay` (estado de Opera: también `IN_HOUSE`, `CHECKED_OUT`), `replace-catalogue`, `record-reception` (cómo tomó el PMS lo que hizo recepción: rechazo y motivo, la habitación, la factura del check-out), `record-charge` (cómo quedó en el folio del PMS un cargo que puso o anuló recepción) |
| `front-office-events` | contracts-schemas (front-office) | front-office | integrations, customer-history, loyalty | `guest-checked-in`, `guest-checked-out`, `no-show-reported`, `charge-posted`, `charge-voided`, `payment-taken`, `payment-refunded`, `company-changed`: operaciones de recepción, cargos, cobros, devoluciones y empresa de facturación, para que el PMS los registre; y `stay-closed`, la estancia cerrada al hacer el check-out (fechas, habitación, huéspedes, consumos), para el historial del cliente y los puntos Riu Class (integrations la ignora). Clave `hotel/estancia` |
| `customers` | contracts-schemas (customer-mdm) | customer-mdm | crs-integration, front-office, customer-history, loyalty | `CustomerChanged`, `CustomersMerged` (el golden record lleva todos los documentos del cliente) |
| `customer-notices` | contracts-schemas (customer-mdm) | customer-mdm | notices | `CustomerNoticeChanged`: un aviso de recepción de un cliente, entero, como lo confirmó Salesforce; gana la versión mayor |
| `notices` | contracts-schemas (notices) | notices | front-office | `NoticeChanged`: un aviso de recepción de un cliente, una reserva o una agencia, entero, con cuándo lo ve recepción; gana la versión mayor |
| `registration-rules` | contracts-schemas (registration-rules) | registration-rules | front-office | `RegistrationRuleChanged`: una regla de registro del kárdex (qué datos exige un destino, por país u hotel, nacionalidad, edad y rol), entera; gana la versión mayor |
| `customer-commands` | contracts-schemas (customer-mdm) | front-office | customer-mdm | `propose-change`, `record-scanned-identity`, `record-kardex` |
| `no-show-reports` | contracts-schemas (crs-integration) | — | crs-integration | `ReportNoShow`. El front office ya no lo manda: su no-show sube al PMS (`front-office-events`) y de él al CRS por el motor |
| `notifications` | contracts-schemas (communication) | integrations, mapping, customer-mdm, pms-integration, front-office | communication | Un aviso (el del front office: `CHECK_IN_INCOMPLETE`, un check-in forzado al que se le pasó el plazo de los documentos) |
| `notification-resolutions` | contracts-schemas (communication) | integrations, mapping, customer-mdm, pms-integration, front-office | communication | Lo que causó un aviso se resolvió |
| `audit` | contracts-schemas (audit) | integrations, mapping, front-office, booking, registration-rules | audit | `AuditedAction` |
| `human-tasks` | communication (eventconductor-forms; es `HumanTaskChanged` del motor) | forms | communication | Las tareas del motor de formularios, para la bandeja |

## Los del motor

Llevan el protocolo de EventConductor; sus cargas son los contratos de tarea de ec-definitions.

| Topic | Qué lleva |
| :---- | :-------- |
| `upstream` | Lo que se le pide al motor: arrancar procesos, las respuestas de los workers, los mensajes que reanudan y las cancelaciones (`ProcessCancellationRequested`: mapping, al descartar un proceso que esperaba en Causas) |
| `outbox` | Cada cambio de estado que registra el motor |
| `downstream` | El destino por defecto de un paso sin topic. **Nadie lo escucha**: todos los pasos nombran el suyo |
| `booking`, `crs-integration`, `mapping`, `pms-integration`, `integrations` | Las tareas de cada worker (ver [Tareas](/referencia/tareas/)) |
| `erp`, `front-office`, `customer-mdm`, `communication`, `notices-tasks`, `customer-history-tasks`, `loyalty-tasks`, `audit-tasks` | Las tareas de administración de los demás workers, principalmente `reset@1`. Son distintos de los topics de eventos (`notices`, `audit`…) |
| `forms` | Las tareas de usuario (`USER_TASK` con `topic: forms`) |

Las alertas de la plataforma no van por Kafka: Alertmanager las manda por HTTP a
`communication-service` (`POST /alerts/alertmanager`), que las deja en la bandeja (ver
[Observabilidad](/operacion/observabilidad/)).

## Grupos de consumo

Cada consumidor tiene su grupo, `ec-demo1-<servicio>-<qué>` (p. ej. `ec-demo1-mapping-commands`,
`ec-demo1-front-office-commands`, `ec-demo1-pms-integration-worker`). Para desbloquear una partición
detenida por un registro que no se puede leer: ver [Problemas conocidos](/operacion/problemas-conocidos/#kafka).
