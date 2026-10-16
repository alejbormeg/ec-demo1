---
title: Problemas conocidos
description: Lo que parece un fallo de la aplicación y no lo es — síntomas, causa y qué hacer, recogidos al construir y operar ec1.
---

Cada uno de estos cuesta una tarde la primera vez porque el síntoma no apunta a la causa.

## Base de datos

**Un valor nuevo de un enum falla solo en ec1** — `violates check constraint`.
Hibernate 6 crea `<tabla>_<columna>_check` con los valores de un `@Enumerated(STRING)` al crear la tabla,
y `ddl-auto: update` nunca la reescribe. En local funciona (la base de datos se crea en cada ejecución) y
en ec1 falla. Pasó con `cause_type_check` en el mapeado, que no tenía `INTEGRATION_INACTIVE`: cada reserva
de un hotel sin integración fallaba `prepare` en bucle. Al añadir un valor a un enum persistido, mirar
las restricciones de la base de datos desplegada
(`select conname, pg_get_constraintdef(oid) from pg_constraint where contype='c'`) y quitar o
rehacer la vieja en el mismo cambio (el mapeado y `communication-service` lo hacen al arrancar, con su
`StaleEnumChecks`). Un campo nuevo
de tipo enum puede guardarse como texto para evitarlo.

**Un campo primitivo nuevo rompe la tabla** — `add column ... not null` sobre una tabla con filas.
PostgreSQL lo rechaza, `ddl-auto: update` lo registra y sigue, y cada consulta de la entidad falla
después. Darle un valor por defecto (`@Column(columnDefinition = "integer not null default 0")`) o usar
el tipo envoltorio.

**Un renombre deja la columna vieja** — `ddl-auto: update` nunca borra una columna: renombrar un campo
JPA añade la nueva y deja la vieja `NOT NULL`, y cada insert falla por una columna que ningún código
nombra. Hay que borrarla a mano.

## Kafka

**Un registro envenenado para una partición entera.** `rpk topic produce` comprime con snappy por defecto
y las imágenes de los servicios (Alpine, musl) no pueden descomprimirlo: el consumidor vuelve una y otra
vez sobre el registro. Producir siempre con `--compression none`. Para desbloquear: escalar el servicio
a 0, `rpk group seek <grupo> --to end --topics <topic>` y volver a escalarlo.

**`BrokerNotAvailable` a partir del segundo test** — el disco está casi lleno y Redpanda rechaza
escrituras por debajo de su umbral de espacio libre (lo dice su log: «no disk space»). En los tests,
Testcontainers monta sus datos en tmpfs; para uno arrancado a mano, igual (`--tmpfs /var/lib/redpanda/data`).

## Compilación

**`mvn -pl <módulo> test` en verde no prueba nada** en un cambio que cruza módulos: resuelve los módulos
hermanos desde `~/.m2`, que tiene lo último que se instaló. Usar `-am` (compila los que necesita desde el
código) o el build completo.

**`package` sin `clean` deja librerías viejas dentro del jar.** Un jar de Boot reempaquetado sin `clean`
conserva en `BOOT-INF/lib` la versión de los contratos que copió la vez anterior, aunque en `~/.m2` haya
una nueva: compila y pasa los tests (que usan la de `~/.m2`) y falla en ejecución con un valor nuevo
(«Unreadable notification, skipped»). Compilar las aplicaciones con `clean package`.

**Testcontainers no habla con Docker 29.** La 1.20 que gestiona Spring Boot 3.4 no conoce la versión de
API que queda en Docker 29: fijar `<testcontainers.version>1.21.4</testcontainers.version>` en el pom del
módulo.

## Mateu

Los comportamientos que pintaban mal sin ningún error se corrigieron en Mateu `3.0-alpha.374`. Aun así,
comprobar cada formulario nuevo en un navegador. Ver [Mateu](/desarrollo/mateu/).

**Una release de Mateu no llega a Maven Central** («Bundle has content that does NOT have a .pom file»,
para todos los módulos y para `io/mateu`): la imagen `ubuntu-24.04` de GitHub del 04-10-2026 pasó Maven de
3.9.16 a 3.10.0, y con él `central-publishing-maven-plugin` 0.11.0 deja los `maven-metadata` en el
paquete. El workflow de publicación de Mateu fija Maven 3.9.16 desde la `3.0-alpha.402` (mateu#724). Lo
que dice Central de un despliegue: el workflow *Central deployment status* de Mateu, con el
`deploymentId` del log.

## Spring y Spring AI

**Una herramienta MCP que no aparece**: Spring AI descarta en silencio un `@Tool` que devuelve `Object`
(solo un WARN al arrancar: «returns a functional type»). Devolver un tipo concreto; buscar «functional
type» en el log al arrancar un servicio MCP.

**El front office no lee una respuesta HTTP**: es Spring Boot 4 con Jackson 3, y `RestClient` no puede
leer en un `com.fasterxml…JsonNode` («Type definition error» en ejecución). Leer en `Map` o en un record.

## El clúster

**Todos los pods en `Pending`**: un `nodeSelector` con un tipo de instancia del que la región no tiene
stock. Fijar solo región y arquitectura. Ver [El clúster](/operacion/cluster/).

**El disco de la máquina de build se llena**: `buildx --push` guarda cada imagen también en local. Ver
[Despliegue](/operacion/despliegue/).

**El último nodo no se va al dormir ec1**: el PodDisruptionBudget de `hcloud-csi-controller` rechaza su
desalojo cuando no queda otro nodo («Failed to drain node, 3 pods are waiting to be evicted»).
`deploy/sleep.sh` borra esos pods en vez de desalojarlos. Ver [El clúster](/operacion/cluster/#dormir-y-despertar-ec1).

**Reservas sin código de cliente en Opera, o una pantalla que se queda en «Not found.» un momento**: es
Karpenter moviendo pods para consolidar, que reinicia lo que tiene una réplica. Pasa sobre todo en los
15 minutos siguientes a un cambio de nodos.

**El entorno local contesta «404 page not found» en el puerto 80**: es el Traefik del k3s de esta
máquina, que se queda `127.0.0.1:80` y `:443`. Por eso el entorno local va en el 8800.

**El agente de mapeado no propone nada** («names LLM 'alejandro' … which is no credential»): el LLM está
en el catálogo pero sin credencial. Pasa en una instalación nueva: las credenciales se dan de alta en la
consola de control, no en el despliegue. Ver [Entorno local](/desarrollo/entorno-local/#lo-que-no-trae-una-instalación-nueva).

## El motor

- **Un paso sin `topic` espera para siempre**: nada escucha en `downstream`, el destino por defecto. Todos
  los pasos nombran su topic, y un `USER_TASK` llega al motor de formularios solo con `topic: forms`.
- **Una descripción de definición de más de 255 caracteres** hace que el import la salte con solo un WARN.
- **No hay ciclos** en una definición, y un paso sucesor de un `CHOICE` que el `CHOICE` no elige se
  descarta: cada punto de espera lleva su propia cadena (ver [Los procesos del motor](/guias/procesos/)).

## Reset de la demo

**La página Demo no vacía las mismas tablas del front office que `zero.sh`.** El `DemoResetPlan` no
incluye `arrival_briefing`, `pax_kardex`, `folio_payment` ni `folio_credit`; el script sí. Esas tablas
no tienen clave foránea a `stay`, por lo que no las limpia el `TRUNCATE … CASCADE` del plan.
Ver [Alcance actual del reset](/operacion/pagina-demo/#alcance-actual-frente-a-zerosh). Es un hallazgo
estático del código actual, sin ejecutar un borrado en el clúster.

## Salesforce y Opera

- **El cupo de Salesforce se agota** sin que ec1 haga nada: un MDM local con las mismas credenciales.
  Ver [Consumo de Salesforce y Opera](/operacion/consumo-apis-externas/).
- **Salesforce Base Edition**: no admite Apex, los Flows llegan como borrador, un Flow *before delete*
  no ve el `MasterRecordId` de una fusión, y no admite más objetos personalizados. Ver
  [Clientes](/integracion/clientes/).
- **OHIP**: un email o teléfono de perfil enviado sin su id se añade junto al viejo y no se puede borrar
  por API (actualizar en su sitio, leyendo el perfil con `fetchInstructions=Communication`); buscar un
  perfil por referencia externa que no existe devuelve 204, no 404. Ver
  [Del CRS a Opera](/integracion/crs-a-opera/).

**El enrutado de la empresa a la ventana 2 no se ve en Opera (XMAR).** `PUT
/csh/v1/hotels/XMAR/reservations/{id}/routingInstructions/create` con la empresa como pagador, del día de
llegada al de salida y con los códigos 1100, 1137 y 1138, responde 200 `{}`. Pero `GET …/routingInstructions`
(con `includeWindowRouting=true`) sigue devolviendo la lista vacía (13-10-2026, reserva de prueba 39482155).
El perfil *Company* sí queda en la reserva, y quitarlo funciona. El `routingInstructions` que
`ReservationPayload` manda al crear una reserva de un partner `NO_FRONT` tampoco se aplica: el PUT de `rsv`
lo acepta y lo ignora, y además usa los códigos `ROOM` y `PACKAGE`, que en XMAR no existen
(`OPERAWS-FOF01559`). Falta comprobar en Opera qué configuración necesita el enrutado por ventanas.
