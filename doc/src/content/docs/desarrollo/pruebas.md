---
title: Pruebas
description: Tests de cada servicio, tests de contrato, las pantallas de las cuatro consolas con Playwright y la historia de la demo.
---

## Tests de los servicios

Cada módulo tiene los suyos (`mvn -am test`). Además de los unitarios y de integración (con
Testcontainers para PostgreSQL y Redpanda), dos familias vigilan lo que cruza servicios:

- **Tests de contrato** (`*ContractsTest`): los productores validan lo que escriben contra el esquema del
  topic; los consumidores pasan cada ejemplo del esquema por su consumidor real.
- **`ServedTasksTest`** en cada worker: mantiene `contracts/workers/<servicio>.tasks` igual a sus
  `TaskRegistration`.

Ver [Contratos](/contratos/librerias-y-esquemas/).

## Las pantallas: `e2e/`

```sh
cd e2e && npm ci && npx playwright install chromium
npm test                 # playwright test: las pantallas de las cuatro consolas
npm run demo             # la historia de la demo contra ec1 (demo.config.ts)
```

Antes de lanzar Playwright, **`DEMO_PASSWORD` debe estar definida** con la contraseña del usuario de
pruebas; `e2e/tests/consoles.ts` rechaza arrancar sin ella. `DEMO_USER` vale `demo` por defecto. Los hosts
de cada suite y las opciones de ejecución se mantienen en `e2e/README.md` y sus `*.config.ts`.

**`npm test`** recorre las pantallas enumeradas en **`e2e/tests/consoles.ts`**, en los dos planos y con
los dos renderers, contra el clúster **desplegado** (ec1 o el clúster local completo, con sus cuatro
consolas, gateway y Keycloak). No escribe datos de negocio. Las mismas comprobaciones se repiten cuatro
veces, y esa repetición es la prueba: una pantalla que pinta en Vaadin y no en Redwood es un hueco del renderer; una
que pinta en un plano y no en el otro es un hueco de enrutado o de permisos.

Dos cosas que saber de lo que comprueba:

- **Los códigos de estado no prueban nada**: Mateu contesta una ruta que no puede resolver con un
  fragmento «Not found.» y un HTTP 200. Una pantalla cuenta como pintada solo si puso algo en pantalla y
  ningún marcador de «no soportado» del renderer.
- **La barra de menús se lee recorriendo shadow roots a mano**, porque los dos renderers anidan sus
  componentes distinto y un selector hecho para uno no devuelve nada en el otro.
- **Cada carga de página va a Keycloak y vuelve** (SSO) justo después de pintarse. Una comprobación que
  cae en medio choca con «Execution context was destroyed»: las comprobaciones lo cuentan como «todavía
  no» y siguen esperando. Contra el entorno local, donde esa ida y vuelta es más lenta, 58 de 73 pruebas
  fallaban por eso.

**Contra el entorno local** ([`deploy/local`](/desarrollo/entorno-local/)), con `E2E_SCHEME=http` y los
hosts con su puerto:

```sh
E2E_SCHEME=http CONSOLE_HOST=ec1.localhost:8800 RW_CONSOLE_HOST=rw.localhost:8800 \
CONTROL_HOST=console.localhost:8800 RW_CONTROL_HOST=rw-console.localhost:8800 npx playwright test
```

Como resultado **histórico**, el 07-10-2026 se anotaron **71 de 73** en local y 71 de 73 en ec1; fallaban
las dos comprobaciones de menús de las consolas Vaadin («mounts every menu the shell declares»).
El inventario ha crecido desde entonces: ese resultado no describe la ejecución del código actual.

**`npm run demo:ui`** (`DEMO_E2E_RESET=1`) hace **la demo entera por la interfaz**, en Redwood: el reset
desde la página *Demo* (paso 0) y los flujos **1–10** de `e2e/demo-ui/demo-ui.spec.ts`. Borra los datos de
la demo. Cómo se lanza y qué automatiza: `e2e/README.md`. Contra el entorno local, sin el paso 0 (en local no hay
línea base):

```sh
KUBECONFIG=~/.local/share/ec-demo1-local/kubeconfig E2E_SCHEME=http \
DEMO_DATA_HOST=rw.localhost:8800 DEMO_CONTROL_HOST=rw-console.localhost:8800 \
DEMO_FRONT_HOST=front.localhost:8800 DEMO_E2E_RESET=1 \
npx playwright test --config demo-ui.config.ts --grep-invert "0 · reset"
```

Los dos flujos añadidos después de los ocho iniciales son:

- **9**: reconocimiento de un cliente conocido por documento, candidatos y confirmación manual;
  check-out que alimenta el historial y los puntos de fidelización.
- **10**: check-in en la tableta simulada, anticipo en caja, cobro en el folio de Opera y devolución.

Son pruebas escritas en el repositorio; su presencia no indica que se hayan ejecutado en esta revisión.

El paso 3 aprueba el Case de Salesforce leyendo el secreto `ec-salesforce` con `kubectl`, así que
`KUBECONFIG` decide de qué clúster lo lee. El 07-10-2026, los flujos 2–8 pasaron en local (7 de 7,
18,7 min).

**`npm run demo`** recorre la historia de la demo contra ec1 (una reserva nueva hasta Opera y el front
office, un cambio de datos por Salesforce, un no show, la auditoría, la bandeja) y al final hace el reset
a la línea base (`E2E_RESET=0` para no hacerlo). Necesita la integración de MRU01 dada de alta y una
línea base: ver [La demo](/operacion/demo/).

### Pendiente

Lo que las pruebas aún no cubren, anotado para más adelante:

- **`npm test` no recorre los dos menús más nuevos**: *Avisos* (`/_notices`, consola de datos) y
  *Registro* (`/_registration-rules`, consola de control) no tienen entrada en `e2e/tests/consoles.ts`.
- **Schedules tampoco figura en `controlScreens`**: la versión del orquestador la incorpora, pero la
  suite de consolas aún no declara esa pantalla.
- **La batería reducida `e2e/poc-acl-local` no levanta `notices` ni `registration-rules`**: no están en
  el camino CRS → Opera de `scenario.py`. El **clúster completo `deploy/local` sí aplica sus
  manifiestos** mediante `deploy.sh`; permite probar esos servicios y los flujos del front office.

## De punta a punta en local

Dos formas, las dos en [Entorno local](/desarrollo/entorno-local/): la demo entera en un clúster local
(`deploy/local`, con los e2e de arriba), o `e2e/poc-acl-local`, que recorre el camino CRS → Opera contra
el orquestador real y el doble de Opera, con fallos inyectados.

## Formularios nuevos

Un formulario que pinta mal no siempre da un error (ver [Mateu](/desarrollo/mateu/)). Un formulario
nuevo se comprueba en un
navegador: Playwright contra la ruta propia del servicio (`/_<servicio>/<menú>/<entrada>`) funciona sin
la consola.
