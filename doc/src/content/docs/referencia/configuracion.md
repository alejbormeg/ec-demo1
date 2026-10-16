---
title: Configuración
description: Las variables de entorno de cada servicio en ec1 y los Secrets de los que salen, tal como están en los manifiestos.
---

La configuración de despliegue sale de `deploy/manifests/*.yaml` y `deploy/values/eventconductor.yaml`;
los valores por defecto, del `application.yaml` o `application.properties` de cada servicio.
Los valores entre paréntesis vienen de un Secret o ConfigMap que
crea `deploy.sh` desde `deploy/.secrets/credentials.env` (git lo ignora): **ningún secreto está en el
repositorio**.

## Lo común

| Variable | Valor en ec1 | Para qué |
| :------- | :----------- | :------- |
| `SERVER_PORT` | El puerto de cada servicio | Ver [Servicios](/referencia/servicios/) |
| `DB_URL` | `jdbc:postgresql://postgres:5432/<base>` | Su base de datos |
| `DB_USERNAME`, `DB_PASSWORD` | (Secret `ec-postgres`) | |
| `DB_POOL_SIZE` | 4–8 | |
| `KAFKA_BROKERS` | `redpanda:19092` | |
| `OTEL_SERVICE_NAME` | El nombre del servicio | Cómo aparece en Tempo |
| `OTLP_TRACING_ENDPOINT` | `http://tempo.observability.svc.cluster.local:4318/v1/traces` | |
| `TRACING_SAMPLING` | `1.0` | Todas las trazas |
| `<SERVICIO>_URL` | `http://<servicio>:<puerto>` | A quién llama por HTTP |

## Por servicio

### gateway

`CONSOLE_HOST=ec1.mateu.io`, `CONTROL_HOST=console.ec1.mateu.io` (y `RW_CONSOLE_HOST`,
`RW_CONTROL_HOST`, `FRONT_OFFICE_HOST`, con sus valores por defecto en `application.yaml`), y la URL de
cada backend que enruta. El manifiesto solo da algunas (`ORCHESTRATOR_URL`, `FORMS_URL`, `SHELL_URL`,
`BOOKING_URL`, `CONTENT_URL`, `USERS_URL`, `IA_AGENT_URL`, `IA_CONTROL_PLANE_URL`, `CONTROL_SHELL_URL`);
el resto —`NOTICES_URL`, `REGISTRATION_RULES_URL`, `COMMUNICATION_URL`, `INTEGRATIONS_URL`,
`MAPPING_URL`, `CUSTOMER_MDM_URL`, `AUDIT_URL`, `JOURNEY_URL`, `ERP_URL`, `FRONT_OFFICE_URL`…— va con
el valor por defecto de `application.yaml`, `http://<servicio>:<puerto>`.

| Variable | Valor | Para qué |
| :------- | :---- | :------- |
| `DATA_PLANE_AGENT` | `console-agent` por defecto | El agente por defecto de la consola de datos (`X-Default-Agent`, con `X-Agent-Channel: data-plane`) |
| `CONTROL_PLANE_AGENT` | `control-plane-agent` por defecto | El de la consola de control (`X-Agent-Channel: control-plane`) |
| `FRONT_OFFICE_AGENT` | `reception-agent` por defecto | El del front office (`X-Agent-Channel: front-office`) |
| `KEYCLOAK_JWKS_URI`, `KEYCLOAK_ISSUER_URI` | `http://keycloak:8080/realms/ec-demo1/…/certs`, `https://auth.ec1.mateu.io/realms/ec-demo1` | Las claves, por la red del clúster, y el emisor tal como viene en el token |

Las cabeceras se sustituyen siempre: lo que mande el navegador con esos nombres no llega al agente.

### shells

Las cuatro imágenes de consola necesitan **`MATEU_SELF_BASE_URL`** para resolver sus `RemoteMenu`
relativos contra el gateway:

| Despliegue | Valor en el manifiesto |
| :--------- | :--------------------- |
| `shell` | `https://ec1.mateu.io` |
| `shell-redwood` | `https://rw.ec1.mateu.io` |
| `control-shell` | `https://console.ec1.mateu.io` |
| `control-shell-redwood` | `https://rw-console.ec1.mateu.io` |

`deploy/local/up.sh` adapta los valores al entorno local. Ver [Mateu](/desarrollo/mateu/).

### pms-integration-service

| Variable | Valor | Para qué |
| :------- | :---- | :------- |
| `RETRY_ALERT_AFTER` | `10m` | Cuánto fallando antes del aviso `RETRYING_TOO_LONG` |
| `OPERA_VERSION_UDF` | `UDFN01` | El UDF donde se guarda la versión del CRS |
| `OPERA_PAY_AT_HOTEL_METHOD` | `CASH` | La forma de pago en hotel del tenant |
| `OPERA_PROFILE_REFERENCES` | `false` | Referencias externas en perfiles (el tenant no tiene la interfaz) |
| `OPERA_CRM_EXTERNAL_SYSTEM` | vacío | |
| `OPERA_POST_DEPOSITS` | `false` | Depósitos al folio (apagado en el tenant de la PoC) |
| `OPERA_CASHIER_ID` | `69721441` | El cajero con el que se hacen los check-outs y los cargos al folio (`registrar-checkout`, `registrar-cargo`): «EC-DEMO1 Integración», un InterfaceCashier creado para la PoC. Sin él Opera usa el del usuario de la integración, que no tiene (FOF00094) |
| `OPERA_PROPERTIES` | `XMAR,XMU` | Las propiedades que se ofrecen (listar las de la cadena da 403) |
| `OPERA_EXTERNAL_SYSTEM`, `OPERA_CUSTOM_REFERENCE` | (ConfigMap `ec-demo-run`, opcional; `ECDEMO1` y `EC-DEMO1` por defecto) | El contexto de la ejecución de la demo |

En `application.yaml`: `OPERA_CHARGE_DEFAULT_CODE` (`1851`, el código de transacción de un cargo sin
código propio; los demás, por tipo, en `ohip.charges.codes`) y `OPERA_NO_SHOW_CODES` (`NOSHOW`, los
motivos de cancelación de Opera que son un no-show, para las estancias del front office).

Los cobros de caja usan `ohip.payments`: `OPERA_PAYMENT_DEFAULT_METHOD` (`CASH`),
`OPERA_PAYMENT_CARD_METHOD` (`VI`) y `OPERA_PAYMENT_LINK_METHOD` (`VI/OL`); efectivo y manual se
traducen a `CASH`, transferencia a `BT`. `OPERA_OUTAGE_DELAY` (`5s`) es la espera de cada llamada
durante la caída simulada que controla la página Demo.

Cómo se llega a cada propiedad no está aquí: es de la integración de cada hotel, en `integrations-service`.

### integrations-service

| Variable | Valor | Para qué |
| :------- | :---- | :------- |
| `OPERA_GATEWAY_URL`, `OPERA_APP_KEY`, `OPERA_CLIENT_ID`, `OPERA_CLIENT_SECRET`, `OPERA_ENTERPRISE_ID` | (Secret `ec-opera`) | La conexión de la cadena, con la que se rellena una integración nueva |
| `INTEGRATIONS_CRYPTO_KEY` | (Secret `ec-integrations-crypto`) | Cifra el secreto de conexión de cada integración. **No regenerar** |
| `INTEGRATIONS_MAPPING_ASK_AGENT` | `false` | |
| `FO_HORIZON_DAYS` | `60` | La ventana de la integración pms-fo |
| `FO_POLL` | `60s` | El sondeo de cambios en Opera |
| `USAGE_SALESFORCE_URLS` | `http://customer-mdm-service:8127` | De dónde lee el consumo de Salesforce |
| `CONSOLE_URL` | `https://console.ec1.mateu.io` | Los enlaces de los avisos |

En `application.yaml`: `GATE_CHECK` (`5s`) y `GATE_RECHECK` (`30s`), las puertas del alta;
`BACKFILL_PER_TICK` (`20`) y `BACKFILL_TICK` (`2s`), el ritmo del backfill; `ACTIVATION_WINDOW_DAYS`
(`30`); `USAGE_OPERA_URLS` (por defecto `PMS_INTEGRATION_URL`), `USAGE_TIMEOUT` (`2s`) y `USAGE_CACHE`
(`15s`), el consumo de las API externas.

### customer-mdm-service

| Variable | Valor | Para qué |
| :------- | :---- | :------- |
| `SF_DOMAIN`, `SF_CLIENT_ID`, `SF_CLIENT_SECRET` | (Secret `ec-salesforce`, opcional) | La org de Salesforce. Sin ellas resuelve identidades y no limpia |
| `FRONT_OFFICE_PUBLIC_URL` | `https://front.ec1.mateu.io` | Los enlaces al front office |

En `application.yaml`: `CONSOLIDATION_POLL`, `CHANGE_POLL`, `NOTICE_POLL` (`24h`: las redes de seguridad
bajo la Pub/Sub API — fusiones, decisiones de Cases, avisos sin confirmar), `PROJECTION_TICK` (`5s`),
`MARKING_TICK` (`2m`, las marcas de calidad que cambiaron), `CLEANUP_AFTER` (`30d`), `CLEANUP_CRON`
(`0 30 3 * * *`) y `CLEANUP_ENABLED` (`true`) — la anonimización de los «Solo nombre» —, y
`USAGE_LIMITS_STALE` (`2h`, cada cuánto se pregunta a `/limits`). `SF_PUBSUB_HOST`/`SF_PUBSUB_PORT`
(`api.pubsub.salesforce.com:7443`) y `SF_SUBSCRIBE` (`true`). `SF_API_VERSION` (`v67.0`).
`SALESFORCE_PAUSE` (`5m`) y `SALESFORCE_PAUSE_MAX` (`60m`): la pausa de todas las llamadas cuando
Salesforce contesta `REQUEST_LIMIT_EXCEEDED`, doblada en cada rechazo hasta el máximo.
`SF_LIGHTNING_URL` (vacío: se deriva de `SF_DOMAIN`) y `LINKS_TIMEOUT` (`3s`): los enlaces de la ficha
del cliente a cada sistema.

### communication-service

| Variable | Valor | Para qué |
| :------- | :---- | :------- |
| `SMTP_HOST`, `SMTP_PORT` | `postfix`, `25` | El relay de correo |
| `MAIL_FROM` | `integration@ec1.mateu.io` | |
| `DEFAULT_RECIPIENT` | `integration-admins@example.com` | El destinatario de los urgentes que se siembra con la tabla vacía |
| `GOOGLE_CHAT_WEBHOOK`, `GOOGLE_CHAT_WEBHOOK_2` | (Secret `ec-googlechat`) | Los dos espacios de Google Chat |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` | (Secret `ec-webpush`) | Web Push |

En `application.yaml`: `FRONT_OFFICE_HOST` (`front.ec1.mateu.io`: los navegadores que se suscriben desde
ese host son los de recepción, `FRONT_DESK_PUSH`), `INBOX_TASKS_LINK` (`/forms/tasks`, a dónde lleva
una tarea de la bandeja) y `HUMAN_TASKS_TOPIC` (`human-tasks`). Las alertas de Alertmanager entran por
`POST /alerts/alertmanager`, sin configuración aquí: a quién llegan lo dice la tabla de destinatarios.

### front-office

| Variable | Valor | Para qué |
| :------- | :---- | :------- |
| `FRONT_OFFICE_HOTEL` | `MRU01` | El hotel (código del CRS) |
| `FRONT_OFFICE_PMS_HOTEL` | `XMAR` | La propiedad del PMS de la que recibe estancias y catálogo |
| `FRONT_OFFICE_CURRENCY` | `MUR` | La moneda de los cargos que sube al folio del PMS |
| `FORCED_CHECKIN_DOCUMENT_DEADLINE` | `PT24H` | El plazo de los documentos de un check-in forzado; pasado, el aviso `CHECK_IN_INCOMPLETE` |
| `FRONT_OFFICE_URL` | `https://front.ec1.mateu.io` | Su propia URL, para el enlace de ese aviso |
| `CONSOLE_URL` | `https://ec1.mateu.io` | Los enlaces a la reserva del CRS y a los clientes |
| `CRS_INTEGRATION_URL`, `MDM_URL`, `AUDIT_URL`, `PMS_INTEGRATION_URL` | `http://<servicio>:<puerto>` | Los walk-ins y el escáner; las lecturas del MDM; el «Historial» de la reserva; las habitaciones libres y limpias de Opera |
| `HISTORY_URL` | `http://customer-history:8133` | El historial de estancias del cliente reconocido en el check-in; vacío, no se enseña |
| `LOYALTY_URL` | `http://loyalty:8134` | El nivel y los puntos Riu Class del cliente reconocido; vacío, no se enseñan |
| `KEYCLOAK_JWKS_URI`, `KEYCLOAK_ISSUER_URI` | Los mismos que el gateway | Su login |

En `application.properties`: `FRONT_OFFICE_HOTEL_COUNTRY` (`MU`, el país del hotel para las reglas de
registro por país; tiene que coincidir con `HOTEL_COUNTRIES` de registration-rules),
`FRONT_OFFICE_PMS_READY_STATUSES` (`Inspected`, qué estado de limpieza de Opera hace una habitación
lista), `LINK_SECRET` (vacío: una clave propia del proceso; firma los enlaces «Abrir factura», que
dejan de valer al reiniciar) y `FORCED_CHECKIN_CHECK_INTERVAL` (`PT1M`).

### booking (el CRS)

`CUSTOMER_MDM_URL` (los enlaces de la ficha), `AUDIT_URL=http://audit-service:8129` (su «History»:
quién hizo qué con la reserva).

### SQL de los agentes (booking y front-office)

Los dos manifiestos inyectan **`AGENT_DB_USERNAME` y `AGENT_DB_PASSWORD`** desde el Secret
`ec-agent-reader`. El despliegue prepara el usuario `agent_reader` y cada servicio, al arrancar,
le concede lectura sobre sus vistas `agent.*`. Las propiedades `agent-sql.username` y
`agent-sql.password` seleccionan esa conexión lectora; no sustituyen la conexión de escritura del servicio.

La librería limita las consultas a `agent-sql.timeout=5s` y `agent-sql.max-rows=200` por defecto.
Si no se configura el usuario lector, usa el `DataSource` del servicio y registra un aviso: desaparece
la restricción de permisos que aporta el segundo login. Ver [Cómo lee los datos el agente](/ia/datos-del-agente/).

### journey-service

`TEMPO_URL=http://tempo.observability.svc.cluster.local:3200`,
`GRAFANA_URL=https://grafana.ec1.mateu.io`, y las URL del CRS, el MDM y el mapeado. En
`application.yaml`: `JOURNEY_LOOKBACK` (`P7D`, hasta dónde busca en Tempo), `JOURNEY_ZONE`
(`Europe/Madrid`) y `JOURNEY_CACHE_FOR` (`PT5S`).

### mapping-service

`CRS_INTEGRATION_URL`, `PMS_INTEGRATION_URL`, `INTEGRATIONS_URL`, `IA_AGENT_URL` (el agente de
mapeado, para sus propuestas) y `CONSOLE_URL=https://console.ec1.mateu.io` (los enlaces de los avisos).
En `application.yaml`: `RESEND_FOR` (`6h`), cuánto tiempo tras liberarlo se le vuelve a mandar el
mensaje de reanudar a un proceso que no contesta; pasado, queda `RELEASED` en su causa para que una
persona lo descarte.

### audit-service

`AUDIT_ZONE` (`Europe/Madrid` por defecto): la zona horaria en que se muestra y filtra por fecha el
rastro.

### notices

`ERP_URL=http://erp:8120`: el maestro de interlocutores; un aviso de agencia lleva el nombre de la agencia tal como la nombra el PMS.

### customer-history

Sin variables propias: lee `front-office-events` (solo `StayClosed`) y `customers` (solo `CustomersMerged`). Su API
(`/customers/{code}/summary`, `/customers/{code}/stays`, `/demo/stays`) y su servidor MCP no salen del clúster.

### loyalty

Sin variables propias: lee `front-office-events` (solo `StayClosed`, para acumular puntos) y `customers` (solo
`CustomersMerged`, para mover un socio al cliente superviviente). Su API (`/members`) y su servidor MCP no salen
del clúster. Es una demo del programa Riu Class.

### registration-rules

| Variable | Valor | Para qué |
| :------- | :---- | :------- |
| `HOTEL_COUNTRIES` | `MRU01=MU,PMI01=ES,CUN01=MX` | El país de cada hotel del CRS, donde aplica una regla de país. Ningún maestro lo tiene aún: se dice aquí y en cada front office (`FRONT_OFFICE_HOTEL_COUNTRY`) |

### ia-agent

| Variable | Valor | Para qué |
| :------- | :---- | :------- |
| `IA_CONTROL_PLANE_URL` | `http://ia-control-plane:8110` | De dónde saca su configuración. No tiene agente propio: el agente por defecto llega en cada petición (`X-Default-Agent`, del gateway) |
| `IA_CAPTURE_CONTENT` | `full` | Qué de la conversación va a Tempo (`none`, `redacted`, `full`) |
| `IA_CAPTURE_MAX_CHARS` | 16384 por defecto | Corte de cada valor capturado |
| `IA_A2A_MAX_DEPTH` | 2 por defecto | Saltos A2A |
| `IA_A2A_BASE_URL` | `http://ia-agent:8095` por defecto | La dirección A2A que dice su Agent Card; debe coincidir con `A2A_BASE_URL` del plano de control |
| `IA_A2A_TIMEOUT_SECONDS` | 120 por defecto | Por llamada A2A, el turno entero del otro agente |
| `IA_A2A_REQUIRE_TOKEN` | `true` por defecto | `message/send` exige el token de quien llama |

### ia-control-plane

| Variable | Valor | Para qué |
| :------- | :---- | :------- |
| `CP_DEFAULT_AGENT_ID` | `console-agent` por defecto | El agente por defecto del catálogo: contesta cuando ninguna regla encaja y la petición no trae agente por defecto (`GET /internal/agents/default`) |
| `DB_URL` | `jdbc:postgresql://cp-postgres:5432/controlplane` | Su propia base de datos |
| `DB_USERNAME`, `DB_PASSWORD` | (Secret `ec-cp-postgres`) | |
| `CP_CRYPTO_KEY` | (Secret `ec-cp-crypto`) | Cifra las credenciales de los LLM. **No regenerar** |
| `ANTHROPIC_API_KEY` | (Secret `ec-anthropic`) | Solo para sembrar el LLM en un despliegue nuevo |
| `EMBEDDINGS_URL`, `EMBEDDINGS_MODEL` | `http://embeddings/v1`, `intfloat/multilingual-e5-small` | El modelo de embeddings de RAG |
| `GITOPS_ENABLED`, `GITOPS_REPO`, `GITOPS_BRANCH`, `GITOPS_PATH`, `GITOPS_POLL_ENABLED` | `true`, `miguelperezcolom/ec-ia-config`, `master`, `ia`, `false` | GitOps del catálogo |
| `GITOPS_GITHUB_TOKEN`, `GITOPS_WEBHOOK_SECRET` | (Secret `cp-gitops`) | |
| `A2A_BASE_URL` | `http://ia-agent:8095` por defecto | La base de las direcciones A2A |
| `ORCHESTRATOR_URL`, `FORMS_URL`, `BOOKING_URL`, `RAG_STORE_URL` | `http://<servicio>:<puerto>`; `jdbc:postgresql://cp-postgres:5432/controlplane?user=…&password=…` | Solo para sembrar los catálogos en una base de datos vacía (`CatalogueSeeder`): las API y la fuente de RAG (pgvector, en su misma base de datos). Después, los catálogos son del operador |

En `application.yaml`: `USAGE_RETENTION_DAYS` (`90`) y `USAGE_PURGE_CRON` (`0 30 3 * * *`), el registro
de consumo de tokens; `GITOPS_SYNC_ON_STARTUP` (`true`) y `GITOPS_POLL_MS` (`300000`).

### api-mcp

`IA_CONTROL_PLANE_URL`, `CATALOGUE_REFRESH_INTERVAL=30s`.

### users

`KEYCLOAK_URL=http://keycloak:8080`, `KEYCLOAK_REALM=ec-demo1`, `KEYCLOAK_ADMIN_USERNAME=admin` y
`KEYCLOAK_ADMIN_PASSWORD` (Secret `keycloak-admin`): la propagación de usuarios a Keycloak.

### Terceros

- **keycloak**: `KC_HOSTNAME=https://auth.ec1.mateu.io`, base de datos en el PostgreSQL del motor,
  administrador inicial desde el Secret `keycloak-admin`.
- **postfix**: `RELAYHOST=[smtp.gmail.com]:587`, `RELAYHOST_TLS_LEVEL=encrypt`,
  `ALLOWED_SENDER_DOMAINS=mateu.io`, contraseña del Secret `postfix-relay`.
- **embeddings**: `HUGGINGFACE_HUB_CACHE=/data` (el modelo se descarga al arrancar).
