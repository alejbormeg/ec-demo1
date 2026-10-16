---
title: Programaciones del motor
description: Schedules en EventConductor 2.25.1 — arranques por cron, propiedad de la configuración, consola, REST y herramientas MCP.
---

El despliegue fija el orquestador en **2.25.1** (`deploy/values/eventconductor.yaml`), que incorpora la
administración de programaciones en **Workflow → Schedules** de la consola de control. La pantalla la
sirve el orquestador, a través del `RemoteMenu("/_workflow-admin")` que ya monta `control-shell`:
no requiere un servicio nuevo de ec-demo1.

Una programación arranca un workflow con un **id de programación**, una **expresión cron**, una
**zona horaria** y unas **variables iniciales**. El cron tiene seis campos, con los segundos primero:
`0 0 9 * * MON-FRI` significa las 09:00 de lunes a viernes en la zona elegida, por ejemplo
`Europe/Madrid`. El workflow debe existir y admitir instancias nuevas.

## Qué se puede hacer

La lista y el detalle muestran el workflow, cron, origen, pausa, estado y próximos arranques. Hay tres
orígenes, y el origen determina dónde se modifica la programación:

| Origen | Crear, editar y borrar | Pausar, reanudar y ejecutar ahora |
| :----- | :-------------------- | :-------------------------------- |
| Definición del workflow | En el fichero de la definición | Desde la consola, REST o MCP |
| `cron.yaml` importado de git, directorio o classpath | En ese fichero | Desde la consola, REST o MCP |
| UI/API | Desde la consola, REST o MCP | Desde la consola, REST o MCP |

- **Pausar** detiene los arranques automáticos; la pausa se conserva por id al reimportar el fichero.
- **Reanudar** continúa desde la siguiente ocurrencia: no reproduce las que pasaron durante la pausa.
- **Run now** pide un arranque inmediato con las variables de la programación, incluso si está pausada.
  El arranque es asíncrono: la clave devuelta permite buscar el proceso.
- **Exportar YAML** devuelve una entrada para `cron.yaml`. Para pasar una programación UI/API a git,
  se añade al fichero y se importa: git pasa a ser dueño de ese id, sustituye la copia UI/API y conserva
  la pausa. Quitarla después del fichero elimina esa programación importada.

Exportar no hace ningún commit ni push. Las definiciones del despliegue se importan desde
`definitions/workflows` de ec-definitions; los ficheros y las programaciones concretas pertenecen a ese
repositorio, no al catálogo GitOps de IA.

## REST y MCP

La API REST del orquestador parte de **`/workflow/api/schedules`**:

| Método | Ruta relativa | Operación |
| :----- | :------------ | :-------- |
| `GET` | `/` y `/{id}` | Listar y consultar |
| `POST` | `/` | Crear una programación UI/API |
| `PUT`, `DELETE` | `/{id}` | Editar o borrar una programación UI/API |
| `POST` | `/{id}/pause`, `/{id}/resume`, `/{id}/run-now` | Pausar, reanudar o pedir un arranque |
| `GET` | `/{id}/yaml` | Exportar la entrada YAML |

Las rutas de la tabla se añaden a la base sin duplicar la barra; listar y crear usan exactamente
`/workflow/api/schedules`. **El gateway de ec-demo1 no tiene una ruta para esta API REST**: la dirección
del servicio dentro del clúster es `http://orchestrator:8105`. La consola utiliza su ruta de UI
administrativa. La API comprueba `X-Api-Key` si el motor tiene configurada la clave de su API de mensajes.

El servidor MCP del orquestador ofrece diez operaciones de programación:

- `listSchedules`, `getSchedule`, `listWorkflowDefinitions`;
- `createSchedule`, `updateSchedule`, `deleteSchedule`;
- `pauseSchedule`, `resumeSchedule`, `runScheduleNow`, `exportScheduleYaml`.

Un agente configurado con ese servidor puede seleccionar estas herramientas. La validación del cron,
la propiedad del fichero, la persistencia de la pausa y el arranque del proceso los ejecuta el motor de
forma determinista. La disponibilidad de las herramientas en un chat depende del catálogo MCP del
agente y de la conexión con el orquestador.

## Fuentes y comprobación

La versión y el montaje de la pantalla se pueden contrastar en
[`deploy/values/eventconductor.yaml`](https://github.com/miguelperezcolom/ec-demo1/blob/master/deploy/values/eventconductor.yaml)
y [`ControlShellHome`](https://github.com/miguelperezcolom/ec-demo1/blob/master/consoles/control-shell/src/main/java/io/mateu/ecdemo1/controlshell/infra/in/ui/ControlShellHome.java).
El contrato de las operaciones se ha contrastado con
[`ScheduleMcpTools`](https://github.com/miguelperezcolom/eventconductor/blob/9d91f1509ec3406c4b1b70309b5ae5de62d2267d/modules/workflow-engine/src/main/java/io/mateu/workflow/infra/in/mcp/ScheduleMcpTools.java)
y [`ScheduleRestController`](https://github.com/miguelperezcolom/eventconductor/blob/9d91f1509ec3406c4b1b70309b5ae5de62d2267d/modules/workflow-engine/src/main/java/io/mateu/workflow/infra/in/rest/ScheduleRestController.java).
La suite de consolas de este repositorio aún no incluye Schedules: ver [Pruebas](/desarrollo/pruebas/).
