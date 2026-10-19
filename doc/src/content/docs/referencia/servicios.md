---
title: Servicios
description: Cada despliegue de ec1 — módulo, imagen, puerto, ruta del gateway y base de datos.
---

Las imágenes propias son `miguelperezcolom/ec-demo1-<carpeta>`; el tag de cada una está en su manifiesto
de `deploy/manifests/`. Las versiones se recogen en [Versiones y alcance](/referencia/estado-del-repositorio/).
Las bases de datos
`postgres:5432/<nombre>` están en el PostgreSQL del motor.

## Consolas

| Despliegue | Módulo | Puerto | Host / ruta |
| :--------- | :----- | -----: | :---------- |
| `gateway` | `consoles/gateway` | 8190 | Todos los hosts de consola y `front.ec1` |
| `shell` | `consoles/shell` | 8101 | `ec1.mateu.io/**` |
| `shell-redwood` | `consoles/shell` (`-Predwood`) | 8102 | `rw.ec1.mateu.io/**` |
| `control-shell` | `consoles/control-shell` | 8111 | `console.ec1.mateu.io/**` |
| `control-shell-redwood` | `consoles/control-shell` (`-Predwood`) | 8112 | `rw-console.ec1.mateu.io/**` |

## Sistemas

| Despliegue | Módulo | Puerto | Ruta | Base de datos |
| :--------- | :----- | -----: | :--- | :------------ |
| `booking` | `systems/crs/booking` | 8108 | `/_booking` | `booking` |
| `erp` | `systems/erp` | 8120 | `/_erp` | `partners` |
| `front-office` | `systems/front-office` | 8128 | `front.ec1.mateu.io` | `front_office` |
| `notices` | `systems/notices` | 8131 | `/_notices` (datos) | `notices` |
| `customer-history` | `systems/customer-history` | 8133 | `/_history` (datos) | `customer_history` |
| `loyalty` | `systems/loyalty` | 8134 | `/_loyalty` (datos) | `loyalty` |

## Integración (plano de datos)

| Despliegue | Módulo | Puerto | Ruta | Base de datos |
| :--------- | :----- | -----: | :--- | :------------ |
| `crs-integration-service` | `integration/crs-integration-service` | 8121 | — | `crs_integration` |
| `pms-integration-service` | `integration/pms-integration-service` | 8123 | — | — (sin estado propio) |
| `mapping-service` | `integration/mapping-service` | 8122 | `/_mapping` (control) | `mapping` |
| `customer-mdm-service` | `integration/customer-mdm-service` | 8127 | `/_customers` (datos), `/_mdm` (control) | `customer_mdm` |
| `journey-service` | `integration/journey-service` | 8130 | `/_journey` | — (lee Tempo) |

## Plano de control

| Despliegue | Módulo | Puerto | Ruta | Base de datos |
| :--------- | :----- | -----: | :--- | :------------ |
| `integrations-service` | `control-plane/integrations-service` | 8126 | `/_integrations`, `/_api-usage`, `/_demo` (control), `/_demo-banner` | `integrations` |
| `communication-service` | `control-plane/communication-service` | 8125 | `/_communication`, `/_inbox` (un `GET /_inbox` tecleado en la barra de direcciones redirige a `/inbox/pending`), `/_inbox/push` también en `front.ec1`; `POST /alerts/alertmanager` solo dentro del clúster (las alertas de Alertmanager a la bandeja) | `communication` |
| `audit-service` | `control-plane/audit-service` | 8129 | `/_audit` | `audit` |
| `registration-rules` | `control-plane/registration-rules` | 8132 | `/_registration-rules` | `registration_rules` |
| `users` | `control-plane/users` | 8102, gRPC 9191 | `/_users` | `users` |

## IA

| Despliegue | Módulo | Puerto | Ruta | Base de datos |
| :--------- | :----- | -----: | :--- | :------------ |
| `ia-agent` | `ai/ia-agent` | 8095 | `/ai` (todas las consolas y el front office; el gateway marca canal y agente por defecto) | — |
| `ia-control-plane` | `ai/ia-control-plane` | 8110 | `/_ia-cp`, `/cp-webhooks` | `cp-postgres:5432/controlplane` |
| `api-mcp` | `ai/api-mcp` | 8113 | — (solo dentro del clúster) | — |

## Soporte y terceros

El puerto es el del Service; si el contenedor escucha en otro, va entre paréntesis.

| Despliegue | Qué es | Puerto |
| :--------- | :----- | -----: |
| `content` | `supporting/content`, un CRUD (`/_content`, base de datos `content`) | 8104 |
| `orchestrator`, `forms` | EventConductor (chart vendorizado; `orchestrator-standalone-app:2.27.1`, `forms-standalone-app:2.27.1`): `/_workflow`, `/_forms` y sus `-admin`. El orquestador incluye [Schedules](/operacion/programaciones/) | 8105, 8106 (contenedor 8080) |
| `rules` | El motor de reglas de EventConductor (`rule-standalone-app:2.27.1`), del mismo chart. Configurado para importar `definitions/rules` de ec-definitions; ese catálogo pertenece al repositorio externo. Sin ruta en el gateway | 8107 (contenedor 8080) |
| `postgres`, `redpanda` | El PostgreSQL (`postgres:16`) y el Kafka (Redpanda `v24.1.7`) del motor (y de todos) | 5432, 19092 |
| `cp-postgres` | `pgvector/pgvector:pg16`, la base de datos del plano de control de IA | 5432 |
| `keycloak` | `quay.io/keycloak/keycloak:26.0`, en `auth.ec1.mateu.io` | 8080 |
| `postfix` | `boky/postfix`, el relay de correo a Gmail | 25 |
| `embeddings` | Text Embeddings Inference con `intfloat/multilingual-e5-small` | 80 (contenedor 3000) |
| `kafka-console` | Redpanda Console, en `kafka.ec1.mateu.io`, con basic auth | 8080 |
| `docs` | Esta documentación, en `doc.ec1.mateu.io`, con basic auth | 80 (contenedor 8080) |

`opera-mock` (`systems/pms/opera-mock`) no se despliega: solo existe para el
[entorno local](/desarrollo/entorno-local/).
