---
title: A2A y guardarraíles
description: Agentes que llaman a otros agentes por el protocolo A2A, y rutas con agentes de guardarraíl de entrada y de salida.
---

## Agentes que llaman a otros agentes

Un agente puede tener una lista de **pares**: otros agentes del catálogo a los que puede llamar. En el
editor es la sección *Agents it may call (A2A)*; en GitOps, `peers:`:

```yaml
kind: agent
id: console-agent
llm: anthropic
peers:
  - mapping-agent     # se convierte en la herramienta ask_mapping_agent
```

Cada par se le ofrece al modelo como **una herramienta**, `ask_<agente>`, que le manda un mensaje por el
[protocolo A2A](https://a2a-protocol.org) y devuelve su respuesta. La **descripción del par es la
descripción de la herramienta**: es lo que lee el modelo que llama para decidir cuándo delegar.

### El servidor A2A

Cada pod de `ia-agent` sirve **a todos los agentes** del catálogo:

| | |
| :-- | :-- |
| Agent Card | `GET /a2a/<id>/.well-known/agent-card.json` |
| Mensajes | `POST /a2a/<id>`, JSON-RPC 2.0, método `message/send` |

La configuración resuelta lleva la dirección A2A de cada par: `cp.a2a-base-url` (`A2A_BASE_URL`,
`http://ia-agent:8095` por defecto) más `/a2a/<id>`.

**Subconjunto soportado**: texto que entra, texto que sale, bloqueante. Sin streaming, sin
notificaciones push, sin tareas y sin agentes fuera de este despliegue. Lo que no está implementado
responde con el código de error del protocolo.

### Las reglas

- Un agente **no puede ser su propio par** (el dominio lo rechaza y el sync de GitOps lo informa como
  error).
- Los pares son referencias, como los MCP y las RAG: uno que falta o está desactivado se quita al
  resolver, con un aviso.
- **Se reenvía el token de quien pregunta**: el par actúa para la misma persona, con sus permisos, y
  gasta contra los mismos presupuestos. También se reenvían las cabeceras de traza.
- Una cadena de llamadas se corta en `IA_A2A_MAX_DEPTH` saltos (2, cabecera `X-A2A-Depth`), y se
  rechaza una llamada de vuelta a un agente que ya está en la cadena.
- Un fallo del par llega al modelo como texto, no como excepción.
- `/a2a/**` **no se expone**: el gateway devuelve 404 en todos los hosts públicos. Solo se llama de pod a
  pod.

### Desde fuera del clúster: `deploy/demo/a2a.py`

Un cliente más del plano de control, como Claude Code, habla con los agentes por A2A con
`deploy/demo/a2a.py`. Abre un `kubectl port-forward` a `ia-agent` (`/a2a/**` no se expone), pide a
Keycloak el token del usuario demo y manda `message/send`:

```bash
deploy/demo/a2a.py card mapping-agent
deploy/demo/a2a.py ask mapping-agent "¿Cuántos códigos pendientes tiene MRU01?"
deploy/demo/a2a.py ask console-agent "…" --context <contextId de la respuesta anterior>
```

El agente contesta con su LLM, sus MCP, su RAG y sus pares, gasta contra los presupuestos del usuario y
queda en el log de `ia-agent` como cualquier llamada A2A. Por defecto apunta al clúster local
(`deploy/local`); `A2A_URL`, `KEYCLOAK_URL` y `KUBECONFIG` lo cambian.

## Guardarraíles en una ruta

Una ruta puede poner agentes alrededor del agente que elige: los de **entrada** leen el texto del
usuario antes que él; los de **salida** leen su respuesta antes que el usuario. Son entradas `agent`
normales, llamadas por A2A, un salto cada una y sin herramientas de pares.

```yaml
kind: route
id: support-to-console-agent
role: support
targetAgent: console-agent
inputGuardrails: [content-guardrail]
outputGuardrails: [content-guardrail]
guardrailFailure: CLOSED    # u OPEN
```

**El contrato**: el guardarraíl recibe solo el texto (`message/send`) y contesta JSON
`{"verdict":"ALLOW"|"BLOCK"|"REWRITE","reason":"...","text":"..."}`, leído con tolerancia (acepta un
bloque de código o una frase alrededor).

- Se ejecutan **en el orden** de la lista.
- `BLOCK` para ahí: una entrada bloqueada nunca llega al agente, una respuesta bloqueada nunca llega al
  usuario, y se enseña el motivo.
- `REWRITE` sustituye el texto y el siguiente guardarraíl lee la versión reescrita.
- Un guardarraíl que no responde o no da un veredicto (un `REWRITE` sin `text` cuenta) **bloquea** con
  `CLOSED`, el valor por defecto, y **se salta con un aviso** con `OPEN`.
- El agente destino no puede ser su propio guardarraíl; uno que falta o está desactivado se quita al
  resolver, con un aviso.
- Con guardarraíles de salida, la respuesta no se envía por partes: sale una vez revisada.

Ejemplo de guardarraíl:
[`gitops/example/ia/agents/content-guardrail.yaml`](https://github.com/miguelperezcolom/ec-demo1/blob/master/gitops/example/ia/agents/content-guardrail.yaml).

## Lo que queda abierto

- Una llamada A2A no pasa por la comprobación previa de presupuesto: el consumo se registra y descuenta
  después, pero no se corta en el momento.
- Los guardarraíles se aplican al chat del usuario, no a las llamadas entre agentes.
- El consumo de un guardarraíl se apunta al agente guardarraíl, no al de la ruta.
