---
title: Tareas
description: Las tareas que sirven los workers de ec-demo1 — integración, administración de la demo y topics de cada servicio.
---

Las tareas son los contratos `.ectask` de
[ec-definitions](https://github.com/miguelperezcolom/ec-definitions/tree/master/definitions/tasks), todos
en su versión 1. Cada worker declara las que sirve en `contracts/workers/<servicio>.tasks`, y
`deploy/demo/check-contracts.sh` comprueba que coinciden (ver [Workers y tareas](/contratos/workers-y-tareas/)).

| Topic | Worker |
| :---- | :----- |
| `booking` | `booking` (el CRS) |
| `crs-integration` | `crs-integration-service` |
| `mapping` | `mapping-service` |
| `pms-integration` | `pms-integration-service` |
| `integrations` | `integrations-service` |
| `erp` | `erp` |
| `front-office` | `front-office` |
| `notices-tasks` | `notices` |
| `customer-mdm` | `customer-mdm-service` |
| `customer-history-tasks` | `customer-history` |
| `loyalty-tasks` | `loyalty` |
| `communication` | `communication-service` |
| `audit-tasks` | `audit-service` |

Los ids y topics se contrastan con los inventarios locales. Los procesos indicados son sus usos en la
integración y la demo; el chequeo contra la revisión de ec-definitions elegida se hace con
`check-contracts.sh`.

## Integración

| Tarea | Topic | Procesos | Qué hace |
| :---- | :---- | :------- | :------- |
| `register-no-show@1` | `booking` | `registrar-no-show` | El CRS registra el no-show de una reserva: la cancela con motivo NOS y la deja costando su cargo. Una vez: el mismo aviso dos veces es un solo no-show. |
| `annotate-partner-profile@1` | `crs-integration` | `proyectar-interlocutor` | Anota en el maestro de interlocutores (ERP) qué perfil es el interlocutor en el PMS. Un comando por el outbox de crs-integration. |
| `report-no-show@1` | `crs-integration` | `registrar-no-show-pms` | La integración crs-pms sube al CRS el no-show que el PMS registró: arranca `registrar-no-show`. Una vez por reserva; una que el CRS no tiene o ya cancelada se anota y no sube. |
| `annotate-pms-reference@1` | `crs-integration` | `proyectar-reserva` | Anota en el CRS el id que la reserva tiene en el PMS. Un comando a booking por el outbox de crs-integration, con el id de la ejecución: dos veces es una. |
| `activate@1` | `integrations` | `alta-integracion` | Activa la integración; el tráfico en tiempo real fluye. |
| `await-activation@1` | `integrations` | `alta-integracion` | La integración queda lista para activar; espera a que una persona la active. |
| `backfill-prepass@1` | `integrations` | `alta-integracion` | Pasada previa del backfill. |
| `contrast-catalogues@1` | `integrations` | `alta-integracion` | Contrasta los catálogos de la propiedad (Opera) y del CRS. |
| `request-mapping@1` | `integrations` | `alta-integracion` | Pide al mapping el mapeado de los códigos del hotel. |
| `start-backfill@1` | `integrations` | `alta-integracion` | Inicia el backfill, la llegada más próxima primero. |
| `sync-partners@1` | `integrations` | `alta-integracion` | Sincroniza con el PMS los interlocutores de las reservas del hotel. |
| `verify-connectivity@1` | `integrations` | `alta-integracion` | Verifica la conectividad con Opera; la puerta se abre cuando responde. |
| `fo-activate@1` | `integrations` | `alta-integracion-fo` | Activa la integración pms-fo; los cambios de Opera fluyen al front office. |
| `fo-await-activation@1` | `integrations` | `alta-integracion-fo` | La integración queda lista para activar; espera a que una persona la active. |
| `fo-start-backfill@1` | `integrations` | `alta-integracion-fo` | Inicia el backfill, las reservas de Opera de la ventana. |
| `fo-sync-catalogue@1` | `integrations` | `alta-integracion-fo` | Lleva el catálogo del PMS al front office. |
| `fo-verify-connectivity@1` | `integrations` | `alta-integracion-fo` | Verifica la conexión con Opera y con el front office. |
| `prepare-cancellation@1` | `mapping` | `proyectar-cancelacion` | Lee la reserva del CRS y comprueba que todos sus códigos tienen equivalencia en el PMS. Si falta alguna, abre sus causas y el proceso espera. |
| `prepare-partner@1` | `mapping` | `proyectar-interlocutor` | Lee el interlocutor del ERP y comprueba sus equivalencias. Si falta alguna, abre sus causas y el proceso espera. |
| `prepare-reservation@1` | `mapping` | `proyectar-reserva` | Lee la reserva del CRS y comprueba que todos sus códigos tienen equivalencia en el PMS. Si falta alguna, abre sus causas y el proceso espera. |
| `record-partner-profile@1` | `mapping` | `proyectar-interlocutor` | Registra qué perfil del PMS es el interlocutor, y en qué versión, y reanuda las reservas que esperaban a que existiera en el PMS. |
| `relaunch-process@1` | `mapping` | Proyecciones y operaciones de recepción que esperan causas, incluidos cobros, devoluciones y asignación de empresa | Un proceso que esperaba sus causas y ya no tiene ninguna abierta pide su sucesor: una instancia nueva de sí mismo, que lee otra vez lo que proyecta. Dos veces, un sucesor. |
| `resolve-projection@1` | `mapping` | `proyectar-reserva` | La reserva ya está en el PMS: resuelve la causa de la cancelación que esperaba a que se proyectara. |
| `assign-room@1` | `pms-integration` | `registrar-checkin` | Asigna en Opera la habitación que dio recepción, si Opera no la tiene; sin habitación elegida, la de Opera o la primera que sugiere. Un rechazo abre su causa y el proceso espera. |
| `check-in-reservation@1` | `pms-integration` | `registrar-checkin` | Hace el check-in en Opera. Ya en casa: no escribe (`STALE`). Un rechazo (llegada que no es la fecha de negocio de Opera, habitación…) abre su causa y se cuenta al front office. |
| `check-out-reservation@1` | `pms-integration` | `registrar-checkout` | Hace el check-out en Opera con el cajero de la integración. Ya fuera: no escribe. Un rechazo (saldo pendiente…) abre su causa. |
| `fetch-invoice@1` | `pms-integration` | `registrar-checkout` | Recupera la factura que Opera emitió en el check-out (número, fecha, importe y, si Opera lo da, el PDF) y la manda al front office. No espera. |
| `post-charge@1` | `pms-integration` | `registrar-cargo` | Postea un cargo de recepción en el folio de Opera con el cajero de la integración y el código de transacción de su tipo, referencia `FO:<línea>`. Ya posteado: no escribe. Sin los huéspedes en casa en Opera, o rechazado: causa. |
| `reverse-charge@1` | `pms-integration` | `anular-cargo` | Anula en Opera un cargo que recepción anuló: el mismo importe en negativo, el mismo código, referencia `FO:<línea>:R`. Ya anulado: no escribe. Si el cargo aún no está en Opera, espera a que llegue. |
| `post-payment@1` | `pms-integration` | `registrar-cobro` | Postea un cobro de la caja (pago o anticipo) en el folio de Opera (acción `Billing`) con la forma de pago que corresponde, referencia `FO:PAY:<cobro>`. Ya posteado: no escribe. Sin los huéspedes en casa en Opera, o rechazado: causa. |
| `refund-payment@1` | `pms-integration` | `devolver-cobro` | Devuelve en el folio de Opera un cobro devuelto en la caja: el mismo pago en negativo contra el original, referencia `FO:PAY:<cobro>:R`. Sin el cobro en Opera todavía: espera. |
| `assign-company@1` | `pms-integration` | `asignar-empresa` | Pone o quita el perfil *Company* y solicita el enrutado del folio a la empresa. En XMAR queda pendiente confirmar que Opera aplique ese enrutado. Ya así: no escribe. Empresa aún no proyectada a Opera: espera (`MISSING_PARTNER`). Rechazado: causa. |
| `record-no-show@1` | `pms-integration` | `registrar-no-show-pms` | Anota el no-show en la reserva de Opera como comentario (el estado «No Show» lo pone la auditoría nocturna). Ya anotado o cancelada: no escribe. En casa: causa. |
| `cancel-reservation@1` | `pms-integration` | `proyectar-cancelacion` | Cancela en Opera una reserva cancelada en el CRS, con su motivo; un no-show deja antes su cargo en la reserva. Si Opera aún no la tiene, espera a que se proyecte (R37). |
| `ensure-guest-profile@1` | `pms-integration` | `proyectar-reserva` | Asegura en Opera el perfil del huésped titular: quién es lo dice el MDM de clientes, y lo que el MDM no sabe, la reserva. Si Opera ya tiene esta versión de la reserva, no se escribe (salvo que el MDM la proyecte para reescribir al huésped). |
| `ensure-partner-profile@1` | `pms-integration` | `proyectar-interlocutor` | El interlocutor del ERP como perfil de Opera: el que el ERP ya conoce, el que Opera tenga con su CorporateId o uno nuevo. Si falta la equivalencia de su tipo o Opera lo rechaza, abre la causa y el proceso espera. |
| `project-stay@1` | `pms-integration` | `proyectar-estancia` | Relee de Opera una reserva —del CRS o nacida en Opera— y la manda al front office como estancia (`front-office-commands`), con el titular que diga el MDM. |
| `upsert-reservation@1` | `pms-integration` | `proyectar-reserva` | Graba la reserva en Opera: la busca por el localizador del CRS, compara la versión que Opera tiene (su UDF) con la que se graba, y crea o modifica —o no escribe nada si Opera ya tiene esta o una más nueva (`STALE`)—. Publica `pms-reservations`. |

## Administración de la demo

Estas tareas sirven a **`reset-demo`**, que pide confirmación humana antes de borrar datos. Ver
[La página Demo](/operacion/pagina-demo/) para su orden, alcance y recuperación de errores.

| Tarea | Topic | Qué hace |
| :---- | :---- | :------- |
| `pause-intake@1` | `booking` | Pausa las altas, cambios y cancelaciones del CRS durante el reset |
| `resume-intake@1` | `booking` | Reanuda la entrada del CRS |
| `seed-demo-bookings@1` | `booking` | Siembra las reservas demo una vez por ejecución, si se pidió |
| `clean-salesforce@1` | `customer-mdm` | Limpia los contactos y Cases de la demo y reinicia el punto de lectura de sus eventos |
| `new-opera-context@1` | `pms-integration` | Cambia el contexto de ejecución y lo guarda para próximos arranques; no borra Opera |
| `purge-engine@1` | `integrations` | Purga el estado del motor conservando el proceso del propio reset |
| `check-demo-health@1` | `integrations` | Comprueba los servicios y el contexto de Opera, y devuelve el resumen |
| `notify-reset-result@1` | `integrations` | Notifica y audita el resultado de la ejecución |

El contrato **`reset@1`** se atiende en los siguientes topics:

| Topic | Servicio y alcance |
| :---- | :----------------- |
| `booking` | CRS: sus reservas y datos asociados |
| `crs-integration` | Integración CRS: sus datos y mensajes pendientes |
| `mapping` | Diccionario, causas y estado del mapeado |
| `integrations` | Registro y estado de las integraciones |
| `erp` | Outbox del ERP; conserva los interlocutores |
| `front-office` | Tablas declaradas de estancias y recepción, incluida la copia del catálogo PMS; conserva el catálogo propio y libera habitaciones. Ver las diferencias con `zero.sh` en [Demo](/operacion/pagina-demo/#alcance-actual-frente-a-zerosh) |
| `notices-tasks` | Avisos de recepción |
| `customer-mdm` | Clientes, referencias y trabajo pendiente del hub |
| `customer-history-tasks` | Historial de estancias y alias de clientes |
| `loyalty-tasks` | Socios y acumulaciones de puntos |
| `communication` | Notificaciones y bandejas |
| `audit-tasks` | Auditoría salvo las acciones `Demo: …` |

El detalle de tablas y excepciones lo declara el `DemoResetPlan` de cada servicio. El conector de Opera
no sirve `reset@1`: usa `new-opera-context@1`.
