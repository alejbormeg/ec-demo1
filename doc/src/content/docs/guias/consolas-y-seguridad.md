---
title: Consolas, gateway y seguridad
description: Los hosts, cómo se federan las pantallas de cada servicio, qué comprueba el gateway y cómo se reparten los roles de Keycloak.
---

## Cuatro consolas y un front office

| | Vaadin | Redwood |
| :-- | :-- | :-- |
| **Plano de datos** (el producto) | `ec1.mateu.io` — `shell` | `rw.ec1.mateu.io` — `shell-redwood` |
| **Plano de control** (la plataforma) | `console.ec1.mateu.io` — `control-shell` | `rw-console.ec1.mateu.io` — `control-shell-redwood` |

Y además `front.ec1.mateu.io`, el front office del hotel (Redwood), con su propio agente de chat.

Las consolas Redwood son los **mismos módulos** compilados con **`-Predwood`**: ese perfil de Maven
pone `io.mateu:mateu-redwood` en el classpath donde el build por defecto tiene `io.mateu:mateu-vaadin`, y nada
más cambia. Un módulo, dos imágenes (`ec-demo1-shell` y `ec-demo1-shell-redwood`, igual para
`control-shell`), cada una con su propio `clean package` (ver [Compilar](/desarrollo/compilar/)).

Funciona porque un `RemoteMenu` transporta **UIDL, no HTML**: el orquestador, el motor de formularios
y cada servicio responden con la descripción de una pantalla, y la dibuja el renderer que cargó la
consola. Las cuatro consolas pintan los mismos backends, por las mismas rutas del gateway, con los
mismos clientes de Keycloak.

## Cada servicio trae sus pantallas

Ninguna pantalla se escribe en la consola. Cada servicio declara un `@UI` en su ruta (`/_booking`,
`/_mapping`, `/_integrations`…), la consola la nombra en un `RemoteMenu` y el gateway la enruta. Añadir
una son esas tres cosas y un manifiesto, y la ruta tiene que coincidir en las tres o el menú sale vacío.

La página de inicio de cada consola es `/inicio`: el banner y los **KPI de consumo de Salesforce y
Opera** (ver [Consumo de Salesforce y Opera](/operacion/consumo-apis-externas/)). En la cabecera de
las cuatro, el aviso de la bandeja («Inbox (n)») y el usuario.

## El gateway es la frontera

Todo entra por el mismo **gateway** (Spring Cloud Gateway, `consoles/gateway`). El ingress le pasa
cada ruta de cada host y él enruta por host y ruta. Los backends no autentican nada por sí mismos: el
orquestador y el motor de formularios solo entienden basic auth y los servicios de la demo no tienen
seguridad; **el gateway valida el token de Keycloak antes de que ningún backend vea la petición**.

`SecurityConfig.java` es el único sitio donde está escrita la regla:

- **Autenticado** en cualquier host: `/_workflow`, `/_forms`, `/_booking`, `/_content`, `/_erp`,
  `/_customers`, `/_journey`, `/_notices`, `/_inbox/**`, `/_api-usage/**` y **`/ai/**`** — cada prompt
  que llega al agente se factura, así que el chat no puede ser un endpoint abierto.
- **Rol `ai-admin`**, solo en los hosts de control: `/_ia-cp`, `/_users`, `/mateu/**`,
  `/_workflow-admin`, `/_forms-admin`, `/_integrations`, `/_mapping`, `/_communication`, `/_mdm`,
  `/_registration-rules`, `/_audit` y también **`/ai/**`**: el agente del control plane llega a
  aprobaciones de mapeado, decisiones de integración y datos personales del MDM.
- **Públicos, y por qué**: los webhooks de git del motor (`/workflow/webhooks/**`, `/forms/webhooks/**`,
  verificados por HMAC), el de GitOps del catálogo de IA (`/cp-webhooks/**`, también HMAC, y solo en
  los hosts de control), los scripts de Web Push de la bandeja (`/_inbox/push/push.js` y `sw.js`), los
  recursos estáticos del motor (`/eventconductor/**`) y la salud. Y dos `GET` que solo redirigen a una
  pantalla de la consola, que es la que pide el login: `/_inbox` (o `/_inbox/`), tecleado en la barra
  de direcciones, lleva a la bandeja (`/inbox/pending`), y `/_api-usage/apis`, a las APIs externas
(`/usage/apis`). Lo
  demás bajo `/_inbox` y `/_api-usage` sigue autenticado.
- **Nunca desde fuera** (rutas que devuelven 404 a propósito): `/a2a/**` en todos los hosts, y `/api/**`
  y el MCP del front office en `front.ec1`.

:::caution[Un host de control que falte en la lista no falla cerrado]
`SecurityConfig` guarda un **conjunto** de hosts de control. Un host de consola que no esté en él no
se rechaza: sus peticiones a `/_ia-cp` o `/_users` caen en la regla final, que lo permite todo. El
conjunto es la frontera de seguridad, no el ingress. Añadir una quinta consola exige añadirla ahí.
:::

Lo que **no** tiene ruta y no debe tenerla: el `/internal/**` del plano de control de IA (sirve la
credencial del LLM en claro al agente), el REST de `integrations-service` (`/integrations/connections`
entrega al conector el secreto de Opera), `api-mcp` (llama a APIs de terceros con credenciales
guardadas), el `POST /alerts/alertmanager` de `communication-service` (por donde entran las alertas de
la plataforma a la bandeja) y el puerto gRPC 9191 de `users` (no autentica nada). Son alcanzables solo desde dentro
del namespace.

## Keycloak

El realm es `ec-demo1`, en `auth.ec1.mateu.io`, importado de un fichero del repositorio.

- **Dos clientes**, uno por plano: `demo` para el plano de datos (y el front office) y
  `control-plane` para el de control, con `directAccessGrantsEnabled` desactivado. Cada cliente lista
  los hosts Vaadin y Redwood de su plano en sus redirect URIs.
- **Tres roles**: `user` y `admin` para el plano de datos, y **`ai-admin`** para el de control. Es un
  rol aparte y no el `admin` de la demo porque ese host llega a las credenciales de los LLM: son dos
  permisos distintos y ninguno implica al otro. El usuario `demo` tiene los tres porque la demo quiere
  un solo login; un despliegue real los separaría.
- **La URL de Keycloak está compilada en la consola.** Mateu escribe `@KeycloakSecured` en la página
  de arranque generada, así que el host, el realm y el cliente no pueden ser variables de entorno:
  cambiarlos es recompilar la imagen de la consola.

`users` es la fuente de verdad de quién es cada persona y Keycloak tiene la copia que autentica; la
propagación va por outbox. Ver [Usuarios, Keycloak y correo](/operacion/usuarios-y-correo/).

## Un host, dos puertas a un mismo pod

`orchestrator` y `forms` no se despliegan dos veces: cada uno declara dos `@UI` — `/_workflow` y
`/_workflow-admin`, `/_forms` y `/_forms-admin` —, y el gateway enruta la pareja normal en el plano de
datos (procesos, tareas: el trabajo) y la `-admin` en el de control (definiciones, analítica: la
configuración). El host decide qué rol se exige en cada una.
