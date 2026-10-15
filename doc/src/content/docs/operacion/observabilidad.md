---
title: Observabilidad
description: Prometheus, Grafana, Loki y Tempo en ec1 — los dashboards propios, las alertas y dónde mirar.
---

La pila está en el namespace `observability`, desplegada por `deploy.sh` desde
`deploy/observability/`: **kube-prometheus-stack** (Prometheus y Grafana, `grafana.ec1.mateu.io`),
**Loki** (logs de todos los pods, 7 días), **Tempo** (trazas) y **Alloy** (recolección). Va en hel1
con el resto de ec1, fijada solo a región y arquitectura; Alloy, que tiene que estar en todos los nodos,
lleva prioridad de sistema (ver [El clúster](/operacion/cluster/)). Los cuatro charts van fijados en
`deploy.sh` a la versión que corre en ec1 (kps 88.6.1, loki 7.3.0, tempo 1.24.4, alloy 1.12.1): un
redespliegue no es también una actualización.

- **Métricas**: Prometheus recoge `/actuator/prometheus` de los pods anotados con `prometheus.io/*` y los
  ServiceMonitors de `servicemonitors.yaml`. Retención de 24 h.
- **Logs**: todos los pods, en Loki.
- **Trazas**: los servicios de backend y el motor exportan por OTLP a `tempo.observability:4318`, con
  `OTEL_SERVICE_NAME` y muestreo completo; las consolas, el gateway, `users`, `content` y `api-mcp` no
  exportan trazas. Tempo tiene `max_attribute_bytes` subido a 65536 para no cortar el contenido de las
  conversaciones de los agentes.

## Dashboards

Se cargan como ConfigMaps que recoge el sidecar de Grafana, **nunca importados a mano**: Grafana guarda
su base de datos en un `emptyDir`, así que un dashboard importado desde la interfaz dura lo que dura el
pod. `deploy.sh` los aplica con `apply_dashboard <nombre> <clave> <fichero>`.

| Dashboard | Fichero | Qué enseña |
| :-------- | :------ | :--------- |
| **EventConductor** | `eventconductor.json` | Si el motor da abasto, dónde se va el tiempo, si el relay es el cuello de botella |
| **EventConductor — nodes** | `nodes.json` | CPU por componente, throttling, el pool JDBC, GC |
| **Booking traces** | `booking-traces.json` | Las trazas de las reservas, el lado técnico de «Ver recorrido» |
| **IA agents** | `ia-agents.json` | Prompts y llamadas a herramientas recientes, con su contenido si se captura; prompts por [camino de datos](/ia/datos-del-agente/#cuál-funciona-mejor) (búsqueda o SQL); **coste** por agente y LLM (tokens × el precio del [catálogo](/ia/gitops/#precios)); y los pasos **LLM_CALL** de los workflows: llamadas, latencia, fallos, tokens y coste |
| **IA token usage** | `ia-tokens.json` | Tokens por agente |
| **External APIs** | `external-apis.json` | El consumo de Salesforce y de Opera |
| **Errores del navegador** | `client-errors.json` | Los errores que los renderers de las consolas mandan desde el navegador (`client-error` en Loki) |

:::note[La clave de cada ConfigMap]
El sidecar escribe todos los dashboards en un único directorio y Grafana deriva el id interno de esa
ruta: dos ficheros con el mismo nombre en el clúster (el chart ya trae un `nodes.json`) hacen que el
segundo se rechace sin aviso. Por eso el de los nodos se guarda con la clave `eventconductor-nodes.json`.
:::

## Alertas

`deploy/observability/prometheus-rules.yaml`: dos `PrometheusRule` con la etiqueta `release: kps`, que es
por lo que el operador selecciona reglas.

| Alerta | Gravedad | Cuándo |
| :----- | :------- | :----- |
| `SalesforceAllowanceHigh` | warning | La org ha gastado el 80 % de su cupo diario |
| `SalesforceAllowanceCritical` | critical | El 95 %, o el MDM ha pausado sus llamadas porque se agotó |
| `SalesforceOurCallRateHigh` | warning | Nuestro ritmo de llamadas, proyectado a 24 h, pasa de 10.000 |
| `SalesforceOthersSpending` | warning | Otros (no ec1) han gastado más de 5.000 |
| `SalesforceNotAnswering` | critical | Todas las llamadas del MDM a Salesforce fallan desde hace 15 min (no es el cupo) |
| `OperaThrottled` | warning | OHIP ha contestado 429 |
| `OperaNotAnswering` | critical | Todas las llamadas de un servicio a OHIP fallan desde hace 10 min — la caída del 29/09 |
| `OperaErrorRateHigh` | warning | Más de un 25 % de las llamadas a OHIP fallan, pero alguna pasa |
| `ServiceDown` | critical | Prometheus no llega a un pod de `ec-demo1` desde hace 5 min |
| `PodCrashLooping` | critical | Un contenedor de `ec-demo1` está en CrashLoopBackOff |

Se prueban con `deploy/observability/test-rules.sh` (promtool, en la imagen de Prometheus): los casos
están en `prometheus-rules.test.yaml`, entre ellos la caída de OHIP tal como fue.

### Quién se entera

Todas las reglas de arriba llevan la etiqueta `notify: ec-demo1`. **Alertmanager** (en
`kube-prometheus-stack.yaml`) manda solo esas a **communication-service**, a su webhook interno
`POST /alerts/alertmanager` —el gateway no lo publica—; las reglas propias del chart (el Watchdog, los
componentes que un clúster gestionado no expone) no van a nadie.

- Una alerta que salta es una notificación de tipo **`PLATFORM_ALERT`** (warning) o
  **`PLATFORM_ALERT_CRITICAL`** (critical), con el resumen, la descripción y el enlace de la regla.
- La tabla de destinatarios decide quién la recibe y por dónde, como con el resto de notificaciones: de
  serie, los administradores (rol `ai-admin`) en la bandeja y en el navegador (Web Push). Para dejar el
  Web Push solo a las críticas, marca en ese destinatario los tipos que quieras (*Notifications →
  Recipients*).
- Cuando la alerta se resuelve, la notificación se cierra en todas las bandejas.
- La misma alerta enviada otra vez (cada 4 h si sigue activa, o tras reiniciarse Alertmanager) no se
  duplica: se identifica por su huella y su inicio. Si vuelve a saltar después de resolverse, es una
  nueva.
- Una alerta crítica silencia las de aviso del mismo servicio.

Alertmanager guarda su estado en un `emptyDir`: un reinicio olvida los silencios.

:::note[Lo que dura cada cosa]
Prometheus y Alertmanager van en `emptyDir`: un reinicio pierde las métricas y los silencios. Loki y
Tempo tienen volumen, pero **dormir ec1 borra el de Loki** (su StatefulSet borra el PVC al bajar a 0), y
moverlos de región también (los volúmenes de Hetzner son de una ubicación). Para lo que tiene que durar,
el lugar es la base de datos de cada servicio y la auditoría, no los logs.
:::

## Dónde mirar

- **Un proceso**: *Workflow → Processes* en la consola (los pasos, los intentos); o Kafka en
  `kafka.ec1.mateu.io` —`upstream` (lo que se pide al motor), `outbox` (cada cambio que registra),
  los topics de tareas—.
- **Una reserva**: «Ver recorrido» ([El recorrido de una reserva](/integracion/recorrido/)).
- **Un agente**: *IA agents → Recent requests*, o Tempo con TraceQL, p. ej.
  `{resource.service.name="ia-agent" && span.ia.outcome != "success"}`.
- **Salesforce y Opera**: los KPI de la página de inicio de las consolas y el dashboard *External APIs*
  ([Consumo de Salesforce y Opera](/operacion/consumo-apis-externas/)).
