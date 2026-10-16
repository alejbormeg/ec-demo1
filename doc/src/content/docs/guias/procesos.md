---
title: Los procesos del motor
description: Los procesos de integración y de administración que referencia ec-demo1, sus tareas y cómo esperan o reintentan.
---

El motor de ec1 ejecuta **solo los procesos de la PoC**, importados de
[ec-definitions](https://github.com/miguelperezcolom/ec-definitions) (`definitions/workflows/*.ec`).
Cada paso `ACTION` nombra su **tarea** (`task:`, un contrato `.ectask`) y el **topic** del worker que
la atiende. Esta página describe los procesos que referencia el código de ec-demo1; sus definiciones
se mantienen en ese repositorio externo. El catálogo importado por un clúster depende de la revisión
de ec-definitions que haya cargado.

## `proyectar-reserva` — Proyectar reserva

Graba en Opera una reserva creada o modificada en el CRS. Lo arranca `crs-integration-service` para
`reservation-created` y `reservation-modified`, uno por evento.

| Paso | Tipo | Tarea · topic | Qué hace |
| :--- | :--- | :------------ | :------- |
| `prepare` | ACTION | `prepare-reservation` · `mapping` | Resuelve **todas** las traducciones de una vez: el payload listo o la lista completa de carencias |
| `prepared` | CHOICE | | ¿Falta algo? → `wait-prepare` → `relaunch-prepare` |
| `ensure-guest-profile` | ACTION | `ensure-guest-profile` · `pms-integration` | Resuelve la identidad en el MDM y asegura el perfil del huésped en Opera |
| `profiled` | CHOICE | | ¿Perfil asegurado? → `wait-profile` → `relaunch-profile` |
| `lock-write` | LOCK | | Toma el candado de la reserva |
| `upsert-reservation` | ACTION | `upsert-reservation` · `pms-integration` | Graba la reserva con la guarda de versión |
| `unlock-write` | UNLOCK | | Suelta el candado |
| `written` | CHOICE | | ¿Grabada? → `wait-write` → `relaunch-write` |
| `annotate-pms-reference` | ACTION | `annotate-pms-reference` · `crs-integration` | Anota en el CRS dónde ha quedado en Opera |
| `resolve-projection` | ACTION | `resolve-projection` · `mapping` | Libera lo que esperaba a que llegase al PMS (p. ej. una cancelación) |

## `proyectar-cancelacion` — Proyectar cancelación

Cancela en Opera una reserva cancelada en el CRS (`reservation-cancelled`). Si aún no está en Opera,
espera a que se proyecte y entonces la cancela (R37).

`prepare` (`prepare-cancellation` · `mapping`: hotel y motivo) → `lock-cancel` →
`cancel-reservation` (`pms-integration`) → `unlock-cancel` → ¿cancelada? Con una espera y un
relanzamiento en cada punto que puede no resolverse solo.

## `proyectar-interlocutor` — Proyectar interlocutor

Exporta un interlocutor del ERP a Opera (`partner-changed`): el perfil que el ERP ya conoce, el que
tenga su `CorporateId` o uno nuevo. Anota en el ERP cuál es y reanuda las reservas que lo esperaban.

`prepare` (`prepare-partner` · `mapping`) → `ensure-partner-profile` (`pms-integration`) →
`record-partner-profile` (`mapping`) → `annotate-partner-profile` (`crs-integration`).

## `proyectar-estancia` — Proyectar estancia

La integración PMS → front office: relee de Opera una reserva —del CRS o nacida en Opera— y la graba
en el front office como estancia, ordenada por la última modificación de Opera. Un solo paso:
`project-stay` (`pms-integration`). Ver [De Opera al front office](/integracion/pms-a-front-office/).

## La recepción, al PMS: `registrar-checkin`, `registrar-checkout`, `registrar-no-show-pms`

El PMS es el maestro de la estancia: lo que hace recepción sube a él. El front office escribe en su
outbox `front-office-events` (`guest-checked-in`, `guest-checked-out`, `no-show-reported`) en la
transacción de la decisión; `integrations-service`, si la integración pms-fo de la propiedad está
activa, arranca el proceso con clave `<proceso>:<hotel>/<localizador>` (el hotel y localizador del CRS,
o la propiedad y la reserva de Opera si nació en Opera). Las escrituras en Opera van dentro del candado
`reservation` (`hotelCode + '/' + locator`), el mismo que toman `proyectar-reserva` y
`proyectar-cancelacion`. Un rechazo de Opera es una causa `PMS_REJECTED` con su aviso en la bandeja, el
proceso espera y se relanza al resolverla (el sucesor conserva la estancia, la habitación y la reserva
de Opera); el front office muestra «Opera: rechazado — motivo». Lo transitorio se reintenta.

- **`registrar-checkin`**: `assign-room` (la habitación que dio recepción; sin ella, la de Opera o la
  primera que sugiere) → `check-in-reservation`. Opera solo admite el check-in de una llegada en su
  **fecha de negocio** (XMAR: 2026-05-13; si no, FOF00067). Hecho, la proyección de la estancia
  devuelve «en casa» al front office.
- **`registrar-checkout`**: `check-out-reservation` (con el cajero de la integración,
  `OPERA_CASHIER_ID`) → `fetch-invoice` (la factura que emitió Opera, al front office). Un saldo
  pendiente en Opera es una causa.
- **`registrar-no-show-pms`**: `record-no-show` (un comentario «No show» en la reserva de Opera: por
  API no hay «No Show», lo pone la auditoría nocturna) → `report-no-show` (`crs-integration`), que
  arranca `registrar-no-show`.

## Los cargos de recepción, al folio del PMS: `registrar-cargo`, `anular-cargo`

El PMS es también el maestro del **folio**: cada cargo que recepción pone en el folio de la estancia
—un extra contratado en el check-in, el late check-out, un consumo del catálogo— sale como
`charge-posted` en `front-office-events`, con su línea de folio (`lineId`), su tipo (`ADD_ON`,
`LATE_CHECK_OUT`, `CONSUMPTION`), su código, concepto e importe; anularlo, como `charge-voided`. El
alojamiento no: Opera cobra sus noches con su tarifa. Un proceso por línea y operación, clave
`registrar-cargo:<hotel>/<localizador>:<línea>` (o `anular-cargo:…`), dentro del candado
`reservation`: va detrás del check-in y delante del check-out que recepción hizo después.

- **`registrar-cargo`**: `post-charge` postea el cargo en el folio de Opera (ventana 1, con el cajero
  de la integración y el código de transacción de su tipo) con la referencia `FO:<línea>`: si Opera ya
  tiene un posteo con esa referencia, no escribe. Si Opera aún no tiene a los huéspedes en casa, espera
  (causa `PMS_REJECTED …:post-charge`), y el check-in en Opera la resuelve.
- **`anular-cargo`**: `reverse-charge` postea el mismo importe en negativo, con el mismo código y la
  referencia `FO:<línea>:R`. Si Opera aún no tiene el cargo, espera a que llegue (lo resuelve su
  `post-charge`).

Así, lo que Opera salda y factura en el check-out cubre los cargos de recepción, y el total de su
factura coincide con el del folio del front office (el alojamiento, si Opera lo factura igual). Cada
línea muestra en el front office dónde está en Opera («Opera: en el folio · 88731245»).

## Los cobros de la caja, al folio del PMS: `registrar-cobro`, `devolver-cobro`

Lo que la caja del front office cobra durante la estancia —un pago, un anticipo— sale como
`payment-taken` en `front-office-events`, con su cobro (`paymentId`), su tipo (`PAYMENT`, `DEPOSIT`), su
forma de pago, importe y referencia; devolverlo, como `payment-refunded`. Un proceso por cobro y
operación, clave `registrar-cobro:<hotel>/<localizador>:<cobro>` (o `devolver-cobro:…`), dentro del
candado `reservation`, como los cargos.

- **`registrar-cobro`**: `post-payment` postea el pago en el folio de Opera (`POST …/payments`, acción
  `Billing`, ventana 1, con el cajero de la integración) con la forma de pago que corresponde
  (`ohip.payments`) y la referencia `FO:PAY:<cobro>`: si Opera ya tiene un posteo con esa referencia, no
  escribe. Si Opera aún no tiene a los huéspedes en casa, espera (causa `PMS_REJECTED …:post-payment`) y
  el check-in en Opera la resuelve.
- **`devolver-cobro`**: `refund-payment` postea el mismo pago en negativo, con el número de transacción
  del original y la referencia `FO:PAY:<cobro>:R`. Si Opera aún no tiene el cobro, espera a que llegue.

La respuesta vuelve al front office como un `record-charge` de la línea `PAY:<cobro>`: la caja lo enseña
debajo de cada cobro.

## La empresa de la estancia, a la reserva del PMS: `asignar-empresa`

Cuando recepción elige, cambia o quita la empresa de una estancia (a quién se factura si no es ni la
agencia ni el huésped), el front office lo cuenta como `company-changed` en `front-office-events`, con el
código del partner del ERP (`companyCode`; sin él, «sin empresa»). Cada cambio es un proceso, con clave
`asignar-empresa:<hotel>/<localizador>:<evento>`, dentro del candado `reservation`: gana el último.

- `assign-company` adjunta a la reserva de Opera el perfil *Company* de la empresa (`PUT
  /rsv/v1/hotels/{h}/reservations/{id}`, solo `reservationProfiles`) y pide enrutarle el alojamiento y la
  pensión en la ventana 2 del folio (`PUT /csh/v1/…/routingInstructions/create`, códigos
  `ohip.company-routing-codes`, en XMAR 1100, 1137 y 1138). Sin empresa, borra el enrutado a su perfil y
  quita el perfil.
- Si la reserva ya tiene esa empresa, no escribe (`STALE`). Si la empresa aún no es perfil de Opera,
  espera a su proyección (`MISSING_PARTNER`). Si Opera la rechaza, causa `PMS_REJECTED` y espera.

La petición de enrutado está implementada, pero en XMAR se anotó que Opera la acepta sin devolver el
enrutado al consultar. Ver la comprobación histórica y lo pendiente en
[Problemas conocidos](/operacion/problemas-conocidos/#salesforce-y-opera).

## `registrar-no-show` — Registrar no-show

El CRS, maestro de la venta, cancela la reserva como no-show con su cargo. Un solo paso:
`register-no-show` (`booking`). Lo arranca `report-no-show` después de que el PMS lo registre (o
`POST /no-shows` de crs-integration). Lo que cuesta baja a Opera y al front office por la proyección de
la cancelación.

:::note[Solo en la PoC]
En producción el no-show lo marca el **Night Audit de Opera** y el front office solo lo refleja. El
flujo «recepción marca no show → el PMS lo anota → el CRS cobra» existe para enseñarlo en la demo.
:::

## `alta-integracion` — Alta de integración

El ciclo de vida de la integración CRS → PMS de un hotel, del registro a la activación, **por
puertas**. Cada paso lo hace `integrations-service` (topic `integrations`) y cada espera se abre cuando
ocurre lo que espera: conectividad con Opera → contraste de catálogos → mapeado aprobado por una
persona → interlocutores en Opera → pasada previa del backfill → backfill → activación por una persona.
Ver [Alta de un hotel](/integracion/alta-de-un-hotel/).

## `alta-integracion-fo` — Alta de integración PMS → front office

Lo mismo para la integración pms-fo: conexión con Opera y con el front office → catálogo del PMS en el
front office → backfill → activación.

## `reset-demo` — Administración de la demo

Lo lanza **Demo** en la consola de control, y pide confirmación mediante una tarea humana antes de
vaciar datos. Pausa la entrada del CRS, manda `reset@1` a cada servicio, limpia los datos de Salesforce,
cambia el contexto de Opera, purga el estado del motor conservando su propio proceso y reanuda el CRS.
Puede sembrar las reservas demo, comprueba la salud y notifica el resultado.

Los handlers de esta operación son workers deterministas. Cada servicio declara lo que borra mediante
`supporting/demo-reset`; el conector sirve `new-opera-context` porque no tiene base de datos propia.
Un reset en error puede reintentarse o cancelarse; los datos ya vaciados no se restauran al cancelar.
Ver el alcance y la confirmación en [La página Demo](/operacion/pagina-demo/).

## Esperar sin ciclos

El motor **no admite ciclos** en una definición y **descarta** un paso sucesor de un `CHOICE` que el
`CHOICE` no elige, así que un «vuelve a preparar» no se puede dibujar. Cada punto de suspensión es una
cadena propia:

1. El paso devuelve sus carencias y `mapping-service` registra cada una como **causa**, con los
   procesos que esperan detrás.
2. El proceso espera en un único `WAIT_FOR_MESSAGE` correlado por su propia clave (`causes-resolved`).
3. Cuando un proceso se queda sin causas abiertas, `mapping-service` le envía el mensaje.
4. El proceso reanudado ejecuta `relaunch-process` y **arranca una instancia nueva**, que relee la
   reserva, y termina (`end-relaunched-…`).

Una instancia de más es inofensiva: encaja con «una instancia por evento» del HLA. Un rechazo
determinista de Opera usa el mismo camino, como una causa más.

## El orden y la concurrencia

Varias instancias pueden tocar la misma reserva a la vez (un cambio del CRS y la reproyección que pide
el MDM por un cambio del cliente, p. ej.). Lo resuelven dos cosas:

- el **candado** de la reserva (`LOCK` / `UNLOCK`) alrededor de la escritura en Opera;
- la **guarda de versión**: la versión del CRS se escribe en un UDF de la reserva de Opera (`UDFN01`) y
  se compara antes de cada escritura. Una versión más antigua que la grabada termina como `STALE` sin
  escribir.

Y cada paso es idempotente por sí mismo: la reserva se busca por el localizador del CRS como referencia
externa, los perfiles de interlocutor por su `CorporateId`, los cobros por su referencia.

:::note[Versión del motor]
En la 2.18.0 el `LOCK` no funcionaba sobre PostgreSQL (la clave llevaba un separador NUL) y el conector
serializaba las escrituras en memoria. La corrección de la 2.23.4 evita que dos procesos que toman a la
vez una clave de `LOCK` libre manden el paso del perdedor al topic de mensajes muertos. Los values
actuales fijan el orquestador en **2.25.1**, formularios en **2.23.8** y reglas en **2.23.6**; las
definiciones usan el `LOCK` del motor.
:::
