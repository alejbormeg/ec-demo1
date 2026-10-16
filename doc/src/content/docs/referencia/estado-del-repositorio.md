---
title: Versiones y alcance
description: Base de la revisión documental, versiones declaradas, fuentes del repositorio y límites de la comprobación.
---

Esta revisión se basa en **`master` de ec-demo1, commit `9b140e55`**, del 15 de octubre de 2026,
revisado el **16 de octubre de 2026**. Describe el código y la configuración versionada. Los hosts y
valores de ec1 que aparecen en las guías son los declarados por el repositorio; esta revisión no ha
consultado el clúster ni ha ejecutado llamadas a Opera, Salesforce o los LLM.

## Versiones declaradas

| Componente | Versión | Fuente |
| :--------- | :------ | :----- |
| Mateu | `3.0-alpha.422` | `mateu.version` en los POM con interfaz |
| Librerías de EventConductor | `2.25.1` | `eventconductor.version` en los módulos que las usan |
| Orquestador | `2.25.1` | `orchestrator.imageTag` en `deploy/values/eventconductor.yaml` |
| Formularios | `2.23.8` | `forms.imageTag` en el mismo fichero |
| Reglas | `2.23.6` | `rules.imageTag` en el mismo fichero |
| Aplicaciones con Mateu | `0.75.0` | Imágenes en `deploy/manifests/` |
| Gateway | `0.65.0` | `35-gateway.yaml` |
| `ia-agent`, `api-mcp` | `0.64.2` | `63-ia-agent.yaml`, `74-api-mcp.yaml` |
| Sitio de documentación | `0.4.17` | `81-docs.yaml` |

La versión de la librería usada al compilar y la de una imagen desplegable son decisiones separadas.
El `appVersion: 2.1.1` del chart vendorizado identifica su origen; **los `imageTag` de los values son
los que fijan las imágenes del motor**. Los tags de la tabla tampoco prueban qué revisión está
ejecutándose en un clúster: para eso hay que inspeccionar el despliegue.

## Qué cubre el código actual

- Integración de reservas **CRS → Opera → front office**, con mapeado, causas, reintentos y altas por hotel.
- Operaciones de recepción hacia Opera: check-in, check-out, no-show, cargos, cobros, devoluciones y
  empresa de facturación.
- Identidad del cliente, documentos y kárdex en el MDM, con Salesforce como maestro; avisos y reglas de
  registro distribuidos al front office.
- Historial de estancias (`customer-history`) y fidelización (`loyalty`, una demo de Riu Class),
  alimentados por `StayClosed` y con tratamiento de las fusiones de clientes.
- Asistente de check-in por operaciones pendientes; caja, selección de empresa y dispositivos simulados.
- Agentes configurables con MCP, RAG, pares A2A, rutas y guardarraíles; búsqueda y SQL de lectura para
  los datos del CRS y del front office.
- Administración de la demo mediante `reset-demo` y simulación de caída de Opera; programaciones cron
  del orquestador en [Workflow → Schedules](/operacion/programaciones/).

Las definiciones de procesos y formularios viven en **ec-definitions**; el catálogo GitOps utilizado
por ec1, en **ec-ia-config**. Los ejemplos de `gitops/example/` no son una copia del catálogo activo.

## Límites conocidos

Hay límites concretos en la implementación actual: la conversación de `ia-agent` se conserva en
memoria del pod ([El agente](/ia/agente/#la-conversación-y-sus-límites)); el reset de Demo omite cuatro
tablas recientes del front office que `zero.sh` sí vacía
([Alcance del reset](/operacion/pagina-demo/#alcance-actual-frente-a-zerosh)); y la suite de consolas aún
no enumera Avisos, Registro ni Schedules ([Pruebas](/desarrollo/pruebas/#pendiente)).

## Dónde contrastar cada dato

| Dato | Fuente del repositorio |
| :--- | :--------------------- |
| Módulos y dependencias | `pom.xml`, `contracts/pom.xml` y POM de cada módulo |
| Imágenes que se construyen y orden de las librerías | `deploy/build-images.sh` |
| Imágenes, puertos y variables del despliegue | `deploy/manifests/*.yaml`, `deploy/values/eventconductor.yaml` |
| Hosts, rutas y autorización del gateway | `consoles/gateway/src/main/resources/application.yaml` y `infra/config/SecurityConfig.java` |
| Mensajes de negocio | `contracts/schemas/` y sus ejemplos |
| Tareas atendidas por los workers | Los 13 inventarios de `contracts/workers/*.tasks` y sus `TaskRegistration` |
| Comportamiento de recepción | `systems/front-office/src/main/java/` y sus pruebas |
| Cobertura de las pantallas y de la demo | `e2e/tests/consoles.ts`, `e2e/demo/`, `e2e/demo-ui/` |

## Qué significa la evidencia

Una dependencia, una herramienta declarada o una prueba escrita demuestra que esa pieza existe en el
código. Para afirmar que funciona en un entorno concreto hace falta ejecutarla allí. Los resultados
con fecha que conservan las guías de desarrollo y operación son **resultados históricos**, no una
validación nueva de este commit.

La compilación del sitio comprueba que Astro puede generar las páginas. La comprobación de enlaces
internos permite detectar destinos y anclas ausentes; ninguna de las dos sustituye a una prueba de los
servicios ni a la revisión visual de las consolas. Ver [Pruebas](/desarrollo/pruebas/).
