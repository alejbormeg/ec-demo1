---
title: Workers y tareas
description: Cómo atiende un servicio las tareas del motor — el TaskRegistration, el runtime worker-kafka, los contratos .ectask y la comprobación que cruza ambos lados.
---

Cada paso `ACTION` de un proceso es una **tarea** que el motor publica en un topic de Kafka y que
atiende un **worker**. Los de integración son `booking`, `crs-integration-service`,
`mapping-service`, `pms-integration-service` e `integrations-service`. También sirven tareas de
administración ERP, front office, avisos, MDM, historial de clientes, fidelización, comunicación y
auditoría: **13 servicios** tienen inventario en `contracts/workers/`.

Ser worker significa atender un `TaskRegistration`; no implica ejecutar un LLM. La lista de
servicios y topics está en [Tareas](/referencia/tareas/).

## Los dos lados de una tarea

| Lado | Dónde | Qué dice |
| :--- | :---- | :------- |
| El motor | ec-definitions: `definitions/tasks/<id>.ectask` y el paso que la nombra (`task:` y `topic:`) | El contrato: id, versión, topic, entradas y salidas |
| El worker | Un bean `TaskRegistration` en el servicio | Quién la atiende, con qué tipos de entrada y salida |

Un contrato `.ectask`, por ejemplo `ensure-guest-profile`:

```yaml
id: ensure-guest-profile
version: 1
group: pms-integration
topic: pms-integration
input:
  hotelCode: { type: string, required: true }
  locator:   { type: string, required: true }
  processKey: { type: string, required: true }
  # …
output:
  profileOutcome: { type: string }   # OK o WAIT
  guestProfileId: { type: string }
  # …
```

Al importar, el motor fija un `task: <id>` sin versión a `<id>@<la última>`, y despacha
`<id>@<versión>` como `taskId`.

## Registrar el handler

En el servicio basta con un bean `TaskRegistration` por tarea. `pms-integration-service` los declara en
`worker/PmsTasks.java`:

```java
@Bean
public TaskRegistration<TaskHandlers.ReservationTask, TaskHandlers.GuestProfile> ensureGuestProfileTask(
        TaskHandlers handlers, RetryWatch watch) {
    return new TaskRegistration<>(ENSURE_GUEST_PROFILE, 1, TOPIC,
            TaskHandlers.ReservationTask.class, TaskHandlers.GuestProfile.class,
            watched(watch, handlers::ensureGuestProfile,
                    TaskHandlers.ReservationTask::hotelCode, TaskHandlers.ReservationTask::locator));
}
```

- `id` y `version` forman la clave `<id>@<version>` con la que el motor despacha la tarea.
- `inputType` y `outputType` son records: el runtime rellena la entrada con las variables del proceso
  según los **tipos declarados** (un localizador `12E45` llega como `"12E45"`), y lo que devuelve el
  handler vuelve al motor como variables.
- `TaskHandler` es funcional: `O handle(I input, TaskContext context) throws Exception`. Si lanza, la
  tarea se responde como fallo y el motor la reintenta según el paso.
- `watched(...)` es de ec-demo1: avisa a `RetryWatch`, que es lo que manda `RETRYING_TOO_LONG` a la
  bandeja cuando un paso lleva demasiado fallando.

## El runtime: `worker-kafka`

El servicio no escribe ningún consumidor. La dependencia `io.mateu.workflow:worker-kafka` (de
EventConductor, `${eventconductor.version}`) trae su autoconfiguración:

- un `@Bean` **`consumeWorkerEvent`** (`Consumer<Message<DomainEvent>>`) que Spring Cloud Stream enlaza
  al binding `consumeWorkerEvent-in-0`;
- un `EnvironmentPostProcessor` que **añade** `consumeWorkerEvent` a `spring.cloud.function.definition`
  (junto a las funciones propias del servicio) y pone valores por defecto de baja prioridad: entrada
  desde `downstream`, grupo `spring.application.name`, respuestas a `upstream`;
- el `TaskRegistry`, que recoge **todos** los `TaskRegistration` del contexto, y el `TaskDispatcher`,
  que busca cada tarea por `<id>@<versión>`, llama al handler y publica la respuesta.

```
Kafka (topic pms-integration)
  → binding consumeWorkerEvent-in-0
  → bean consumeWorkerEvent (worker-kafka)
  → TaskDispatcher → TaskRegistry
  → TaskRegistration de PmsTasks → TaskHandlers
```

Lo único que pone el servicio es su topic y su grupo, en `application.yaml`:

```yaml
spring.cloud.stream.bindings.consumeWorkerEvent-in-0:
  destination: pms-integration
  group: ec-demo1-pms-integration-worker
  consumer:
    concurrency: 3
```

La guía del motor lo explica en
[Implementing Workers](https://github.com/miguelperezcolom/eventconductor/blob/main/doc/src/content/docs/guides/workers.md)
(allí con el camino de interfaces generadas desde el `.ectask`; aquí los registros se escriben a mano)
y en [Task Contracts](https://github.com/miguelperezcolom/eventconductor/blob/main/doc/src/content/docs/reference/task-contracts.md).

### Cómo encuentra el handler

1. `taskId` con versión (`ensure-guest-profile@1`): exactamente ese registro. Una versión que el worker
   no sirve no se resuelve (nunca se cambia por otra, cuya entrada puede ser distinta).
2. `taskId` sin versión: la versión más alta que registra el worker.
3. `stepId`, solo si el `taskId` está vacío (un `ACTION` sin `task:`). Todas las definiciones de
   ec-definitions nombran su tarea, así que esto solo cubre procesos arrancados antes de que lo
   hicieran.

:::caution[Una tarea sin handler]
Por defecto, una tarea que llega sin handler registrado solo deja un aviso en el log y se ignora: el
proceso espera hasta el timeout del paso. Con `eventconductor.worker.strict: true` se responde `ERROR` y
el hueco se ve al momento. En ec-demo1 lo que lo detecta antes es la comprobación de contratos (abajo).
:::

## Lo que hay que saber del runtime

- **Al menos una vez.** El handler se ejecuta y su respuesta se publica en el hilo del listener, y el
  offset se confirma después. Una caída antes de responder hace que la tarea se reentregue: **los
  handlers son idempotentes**.
- **Concurrencia**: una tarea por hilo consumidor; `…consumer.concurrency` hasta el número de
  particiones del topic.

  | Servicio | Topic | Particiones | Concurrencia | Por qué |
  | :------- | :---- | ----------: | -----------: | :------ |
  | pms-integration | `pms-integration` | 3 | 3 | Cada proyección espera a Opera aquí; con un hilo, un backfill haría cola detrás de cada llamada |
  | mapping | `mapping` | 3 | 3 | Las preparaciones de cada proyección esperan al CRS y a las integraciones |
  | crs-integration | `crs-integration` | 1 | 1 | Dos pasos que solo escriben una orden en el outbox |
  | integrations | `integrations` | 1 | 1 | Los pasos de un alta van uno detrás de otro |
  | booking | `booking` | 1 | 1 | Un no-show de vez en cuando |

  Lo que no debe correr a la vez lo separa el `LOCK` del motor sobre la reserva, no la partición.
- **Sin DLQ.** Una tarea cuya respuesta el broker sigue rechazando se descarta tras los intentos del
  binder, y el motor la vuelve a lanzar cuando vence el timeout del paso (todos tienen timeout, `PT2M`,
  y reintentos). Un topic de mensajes muertos que nadie reproduce solo lo duplicaría.

## La comprobación de contratos

`contracts/workers/<servicio>.tasks` lista lo que sirve cada worker, `<id>@<versión> <topic>`,
**generado** de sus `TaskRegistration` por su `ServedTasksTest` (con la misma regla
`-Dcontracts.write=true`). El de `pms-integration-service`:

```
# pms-integration-service: the tasks it serves, <id>@<version> <topic> — generated from its TaskRegistrations (-Dcontracts.write=true)
assign-company@1 pms-integration
assign-room@1 pms-integration
cancel-reservation@1 pms-integration
check-in-reservation@1 pms-integration
check-out-reservation@1 pms-integration
ensure-guest-profile@1 pms-integration
ensure-partner-profile@1 pms-integration
fetch-invoice@1 pms-integration
new-opera-context@1 pms-integration
post-charge@1 pms-integration
post-payment@1 pms-integration
project-stay@1 pms-integration
record-no-show@1 pms-integration
refund-payment@1 pms-integration
reverse-charge@1 pms-integration
upsert-reservation@1 pms-integration
```

`reset@1` es un contrato compartido servido en **12 topics**, uno por servicio. La librería
`supporting/demo-reset` pausa los consumidores declarados, vacía las tablas del `DemoResetPlan` en una
transacción y reanuda los consumidores en `finally`. El listener que ejecuta el propio reset se
mantiene activo. Cada worker registra su tarea y la incluye en `ServedTasksTest` como las demás.

`deploy/demo/check-contracts.sh` lee ec-definitions y **falla** con cualquier paso `ACTION` cuya tarea
no tenga contrato, no tenga topic o no la sirva ningún worker en ese topic:

```sh
deploy/demo/check-contracts.sh                  # ec-definitions en master
deploy/demo/check-contracts.sh --ref <ref>      # en una rama, tag o commit
deploy/demo/check-contracts.sh --cluster        # lo que importó el motor de ec1, y los consumidores vivos
```

`deploy/demo/demo-prep.sh health` ejecuta la forma `--cluster`. La lista completa de tareas está en
[Tareas](/referencia/tareas/).
