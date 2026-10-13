---
title: Mateu
description: Cómo se usa Mateu en ec-demo1 — pantallas federadas, dos renderers, subir de versión y los comportamientos que hay que conocer.
---

[Mateu](https://mateu.io) es el framework de interfaces de todas las pantallas de ec-demo1: cada servicio
describe sus pantallas en Java (view models con anotaciones) y el renderer las dibuja. La versión es la
misma en todos los módulos (`mateu.version`, hoy `3.0-alpha.415`).

## Cómo se usa aquí

- **Cada servicio declara un `@UI`** en su ruta (`/_mapping`, `/_integrations`…) con sus menús, y la
  consola lo monta con un `RemoteMenu`. La etiqueta del `RemoteMenu` tiene que ser la de la sección del
  pod remoto (su único `@Menu` de arriba, en mayúscula): una sección por servicio.
- **Dos renderers, el mismo servidor.** La consola se compila con `io.mateu:vaadin-lit` (por defecto) o
  con `io.mateu:redwood` (`-Predwood`). Un `RemoteMenu` transporta UIDL, así que las pantallas de los
  servicios no saben quién las pinta.
- **`ui-commons`** tiene lo que comparten: el paginado, el widget del usuario y la bandeja de la
  cabecera, las tablas de «otros sistemas», la base de los CRUD de catálogo y los KPI de consumo.
- **Keycloak** va en la anotación `@KeycloakSecured` de la consola, **compilada** en la página de
  arranque: la URL (`https://auth.ec1.mateu.io`) no se puede cambiar al arrancar. El entorno local la
  reescribe en el ingress (ver [Entorno local](/desarrollo/entorno-local/#cómo-está-hecho)); que Mateu
  la deje cambiar en ejecución está pendiente.
- **Un registro que no existe es una página «no encontrado»**, no un error. Si al cargar una ruta la
  pantalla lanza `NoSuchElementException`, Mateu pinta en su lugar una página con un icono, **el mensaje
  de la excepción como título** y la vuelta a la ruta padre (en Redwood, el *empty state* de Spectra). El
  mensaje es lo que lee el usuario, en el idioma de la pantalla: `new NoSuchElementException("Reserva " +
  id + " no encontrada")`, no «No stay X» ni un `Optional.get()` pelado (sin mensaje útil, Mateu pone uno
  genérico con el id). Una acción sobre una pantalla que sí existe no cambia: sigue siendo un error.


**Desde la `3.0-alpha.415`, cada shell necesita `MATEU_SELF_BASE_URL`.** Mateu resuelve un `RemoteMenu`
relativo (`/_booking`…) contra `mateu.self-base-url` y ya no contra el `Origin` de la petición, que era un
SSRF. Sin esa variable, el shell se llama a sí mismo (`localhost:8101/_booking/…`, 404) y todas las
pantallas remotas fallan. Su valor es el host público de cada consola (`https://ec1.mateu.io`,
`https://rw.ec1.mateu.io`, `https://console.ec1.mateu.io`, `https://rw-console.ec1.mateu.io`), porque el
gateway enruta cada `/_<servicio>` por host. `deploy/local/up.sh` lo traduce a `http://*.localhost:8800`.

## Subir de versión

1. Mirar la última publicada en Maven Central
   (`https://repo1.maven.org/maven2/io/mateu/core/maven-metadata.xml`, `<latest>`; el índice de
   search.maven.org va con meses de retraso).
2. Cambiar `mateu.version` en **todos** los módulos a la vez.
3. Reconstruir todas las imágenes con `clean` (el procesador de anotaciones no se vuelve a ejecutar si
   solo cambió la versión) y desplegar todo.
4. Repasar en un navegador los formularios: los comportamientos de abajo cambian entre versiones.

## Lo que hay que saber

- `@Action` no pinta ningún botón: dice cómo se comporta la acción. El botón lo pone `@Toolbar` (o
  `@Button`). Una acción que devuelve un `String` lo muestra como mensaje y deja la pantalla como
  estaba; si además cambió el estado del formulario, devolver `List.of(new Message(...), new State(this))`.
  Una acción de `@Toolbar` también aparece en el modo vista, donde los campos de edición están vacíos.
- Un formulario en un `Dialog` o un `Drawer` es un componente propio, con su estado y sus acciones
  (`new EmbeddedView(form)`, o un `ModelViewComponent`, que dentro de un overlay se trata igual);
  título con `TitleSupplier` y botones con `ButtonsSupplier`.
- `VisibilitySupplier` decide qué acciones y campos se ven según el estado (confirmar solo una reserva
  pendiente, p. ej.).

Desde la `3.0-alpha.374` ya no hacen falta los rodeos de antes: un `Listing` carga al abrirse aunque no
sea `Navigable`, un `@Lookup` en casillas trae sus opciones, las validaciones de los campos ocultos no se
envían, un `@Section` sobre un campo oculto conserva su encabezado y un `@Notice` a `null` no se pinta.

Y lo que trajeron las últimas:

- **`@FoldoutDetail`** (`3.0-alpha.377`, con arreglos del renderer en la 378 y la 379): una página como
  resumen más paneles desplegables. Así es la ficha de la reserva del CRS (`BookingViewModel`).
- **Migas automáticas** (`3.0-alpha.381`): cada página lleva su «ir al padre» sin declararlo. Quien no lo
  quiere lo quita con `@NoBreadcrumbs`, como el front office, que tiene pocas entradas de menú y cuya
  ficha de reserva ya dice dónde se está.
