---
title: El agente (ia-agent)
description: Cómo contesta un prompt el agente — configuración pedida al plano de control, herramientas MCP por petición, disponibilidad y observabilidad.
---

`ia-agent` es la otra mitad del panel de chat: ejecuta el turno del LLM con las herramientas y el
contexto del agente resuelto. Las operaciones de negocio las implementan los servidores MCP. No tiene
pantallas ni base de datos; obtiene modelo, credencial, prompt y herramientas del plano de control
(`IA_CONTROL_PLANE_URL`). **No tiene agente propio**: qué agente contesta se decide en cada petición.

Un solo despliegue, `ia-agent`, atiende el chat de todas las consolas. El gateway marca cada petición
a `/ai/**` según el host, y borra lo que el navegador hubiera mandado con esos nombres:

| Host | `X-Agent-Channel` | `X-Default-Agent` |
| :--- | :---------------- | :---------------- |
| `ec1`, `rw.ec1` | `data-plane` | `console-agent` |
| `console.ec1`, `rw-console.ec1` (con rol `ai-admin`) | `control-plane` | `control-plane-agent` |
| `front.ec1` | `front-office` | `reception-agent` |

Los agentes por defecto son configuración del gateway (`DATA_PLANE_AGENT`, `CONTROL_PLANE_AGENT`,
`FRONT_OFFICE_AGENT`), no del pod.

`ia-agent` pasa el canal y el agente por defecto a `/internal/agents/resolve`, y el plano de control
elige, en este orden:

1. la primera **regla de ruta** que encaje (por prioridad), con sus condiciones: rol, tenant, locale,
   prefijo de pantalla y **canal**. El canal existe porque la pantalla no basta: las del front office
   no comparten prefijo y el control plane comparte pantallas con el plano de datos (`/mapping`);
2. si ninguna encaja, el **agente por defecto de la petición**;
3. si la petición no trae ninguno —una llamada desde dentro del clúster—, el **agente por defecto del
   catálogo** (`cp.default-agent-id`, por defecto `console-agent`; `GET /internal/agents/default`).

Desde las pantallas de mapeado de cualquier consola contesta `mapping-agent`, por la regla
`mapping-screens-to-mapping-agent`.

## Un prompt

1. El panel de chat hace POST a `/ai/api/agent/stream` con el token de la sesión (el gateway lo exige:
   cada prompt se factura).
2. El agente resuelve **qué agente contesta** (`/internal/agents/resolve`, por la ruta de la pantalla,
   el rol, el locale y el canal, con el agente por defecto que marcó el gateway) y pide su **configuración**: modelo, credencial, prompt, servidores MCP, fuentes
   RAG y agentes pares.
3. Abre una **conexión nueva a cada servidor MCP** —por prompt, no en un pool: el cliente MCP que
   autoconfigura Spring AI mantiene una conexión SSE persistente y, si se cae, queda roto para toda la
   vida del pod—, reúne las herramientas que anuncian, añade las de RAG y las de los pares, y se las da
   al LLM.
4. El prompt de sistema le dice que conteste solo llamando herramientas y que informe de un fallo de
   una herramienta en vez de inventar alrededor.

## La configuración, en caché

- **30 segundos de caché por agente**, y la **última copia buena sobrevive** al plano de control: una
  ráfaga de prompts es una sola consulta, y un cambio en la consola llega en uno o dos prompts. Cada
  configuración que da el plano de control —pedida por id o resuelta por contexto— se guarda con el id
  de su agente. Si `/resolve` no contesta, el prompt se responde con la última copia buena **del agente
  que pedía** (el por defecto de su consola), o del agente por defecto del catálogo si no pedía ninguno;
  con un aviso en el log y `degraded` en la salud. Una negativa (409: agente inservible, presupuesto
  agotado) no se tapa con la caché.
- **La disponibilidad sigue al plano de control.** Un pod que nunca ha llegado a él se declara DOWN y
  queda fuera del Service: sin modelo solo produciría errores más despacio. Perder el plano
  de control **después** no lo tumba. La sonda de *readiness* es también el bucle que refresca; la de
  *liveness* ignora todo esto (reiniciar no arregla que otro servicio no conteste).
- **Sin configuración local de respaldo**: una segunda fuente que solo aparece cuando falla la primera
  es como dos configuraciones divergen en silencio.
- Una clave nueva no es un parámetro sino un cliente nuevo: `ChatClientRegistry` guarda uno por
  (proveedor, URL base, clave). Cambiar de modelo no construye nada.

## La conversación y sus límites

El estado de conversación es **local al proceso**. `ConversationStore` usa Caffeine: conserva los
últimos **cinco intercambios** por sesión, con hasta **1.000 sesiones** y caducidad tras **30 minutos
sin acceso**. Los totales de tokens de la sesión y el contexto del menú también se mantienen en
memoria. Reiniciar el pod pierde esas copias y añadir réplicas no las comparte.

Esto es distinto de la persistencia del catálogo y del consumo en el plano de control. La
implementación actual no aporta una memoria de conversación externa para continuar una sesión en
cualquier réplica. La política de usar herramientas se transmite en el prompt del agente; las
validaciones de negocio y las confirmaciones de operaciones las aplica cada servicio.

Fuentes: `ai/ia-agent/src/main/java/io/mateu/ecdemo1/iaagent/ConversationStore.java`,
`MenuContextStore.java` y los handlers MCP del servicio correspondiente.

## Observabilidad

`ia-agent` y el plano de control envían trazas a Tempo, y Grafana tiene los dashboards **IA agents** e
**IA token usage**.

- **Una traza por prompt**: `invoke_agent <agente>` es la raíz, con el agente, el modelo, el resultado
  (`ia.outcome`: `success`, `refused`, `no_tools`, `error`) y los totales de tokens y llamadas. Debajo,
  `chat <modelo>` por cada ida y vuelta al LLM y `execute_tool <herramienta>` por cada herramienta, con
  su origen (`mcp` o `rag`). La llamada MCP lleva las cabeceras de traza, así que un servidor que traza
  añade su span a la misma traza.
- **El contenido de la conversación**, en Tempo y nunca en Loki, según `IA_CAPTURE_CONTENT`:
  `none` (solo tokens, tiempos y herramientas), `redacted` (emails, teléfonos, tarjetas, IBAN, DNI/NIE y
  pasaportes enmascarados antes de exportar) o `full`. Cada valor se corta en `IA_CAPTURE_MAX_CHARS`.

:::caution[Datos personales]
En ec1 los manifiestos ponen **`full`** porque es una demo: nombres, emails, teléfonos y reservas quedan
en Tempo durante su retención, legibles para quien entre en Grafana. Con datos reales, `redacted` o
`none`.
:::

- **Métricas** en `/actuator/prometheus`: `ia_agent_prompt_seconds`, `gen_ai_client_operation_seconds`,
  `gen_ai_client_token_usage_total` y `spring_ai_tool_seconds`, con la etiqueta `gen_ai_agent_id`.
  Además, por prompt, `ia_agent_prompt_tool_calls` e `ia_agent_prompt_tokens`, y en ellas y en
  `ia_agent_prompt_seconds` la etiqueta `ia_data_path`: cómo llegó el prompt a los datos (búsqueda, SQL,
  otras herramientas). Lo explica [Cómo lee los datos el agente](/ia/datos-del-agente/).
- **El consumo** se informa al plano de control por agente (`UsageReporter`), que es contra lo que se
  comprueban los presupuestos.
