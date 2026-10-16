---
title: Estructura del proyecto
description: El repositorio visto por quien lo toca — qué va en cada carpeta, qué módulos son librerías y cuáles imágenes, cómo están organizados los paquetes de un servicio y dónde viven las piezas compartidas.
---

[Arquitectura](/guias/arquitectura/) cuenta qué hace cada carpeta y cómo se hablan los servicios. Esta
página baja un nivel: cómo está hecho un módulo por dentro, de qué depende y qué convenciones se repiten.

## Las carpetas

| Carpeta | Qué va | Por qué ahí |
| :------ | :----- | :---------- |
| `systems/` | Los sistemas de la cadena (CRS, ERP, front office, avisos, historial de clientes y fidelización) y `pms/opera-mock` | Son los extremos: tienen su propio modelo y no saben nada de la integración |
| `integration/` | Los adaptadores y servicios del plano de datos | La ACL: lo único que traduce entre sistemas |
| `control-plane/` | Lo que configura y mide (integraciones, auditoría, comunicación, reglas de registro, usuarios) | Lo que gobierna el flujo, con su propia consola |
| `consoles/` | `gateway`, `shell`, `control-shell` | Solo federan las pantallas de los demás |
| `ai/` | Los agentes, sus catálogos y `api-mcp` | |
| `supporting/` | `messaging`, `agent-sql`, `demo-reset`, `ui-commons` y `content` | Lo transversal que no es de ningún contexto |
| `contracts/` | Una librería por contexto, `schemas/` y `workers/` | El lenguaje publicado; ver [Librerías y esquemas](/contratos/librerias-y-esquemas/) |
| `deploy/` | Manifiestos, chart, observabilidad, `build-images.sh`, `deploy.sh`, `demo/` | |
| `gitops/` | El esquema del catálogo de IA y un ejemplo | Lo que un tercero escribe |
| `e2e/` | Playwright (`tests/`, `demo/`) y la batería local `poc-acl-local` | |
| `doc/` | Este sitio (Astro Starlight), con su propia imagen `ec-demo1-docs` | |
| `docs/` | `poc-acl/`: el plan, las decisiones y las notas de trabajo en bruto | De donde sale lo que aquí se cuenta ordenado |

## El reactor: un agregador, no un padre

El `pom.xml` raíz es `packaging pom` y **nadie lo tiene como padre**: cada módulo hereda de
`spring-boot-starter-parent` (3.4, 4.0 o 4.1, ver [Compilar](/desarrollo/compilar/)) y se compila solo
desde su carpeta. El reactor solo ordena por dependencias cuando se compila todo desde la raíz:

```xml
<!-- pom.xml -->
<modules>
    <!-- systems: the chain's systems -->
    <module>systems/crs/booking</module>
    ...
    <!-- contracts: the language they speak, one library per context, and its schemas -->
    <module>contracts</module>
    <!-- integration: what flows between them -->
    <module>integration/crs-integration-service</module>
    ...
```

Dos clases de módulo:

- **Librerías** (se instalan en `~/.m2`, sin imagen): `contracts/*` (las once librerías de contexto,
  `contracts-testing` y `contracts-schemas`), `supporting/messaging`, `supporting/agent-sql`,
  `supporting/demo-reset`, `supporting/ui-commons` y
  `control-plane/grpc-interface` (solo un `auth.proto`; de él compila únicamente `control-plane/users`).
- **Aplicaciones**: todo lo demás. Cada una trae un `Dockerfile` de solo ejecución que copia el jar ya
  compilado, y su imagen se llama como su carpeta:

```sh
# deploy/build-images.sh
for app in $APPS; do
  build_and_push "$app" "ec-demo1-$(basename "$app")"
```

`systems/pms/opera-mock` está en el reactor y tiene `Dockerfile`, pero no en `APPS`: es el doble de OHIP
de la batería local y nunca se despliega. Las dos consolas se construyen dos veces (Vaadin y, con
`-Predwood`, `…-redwood`).

## Anatomía de un servicio

Todo el código cuelga de `io.mateu.ecdemo1.<servicio>`, con la clase `*Application` en la raíz. A partir
de ahí hay **dos estilos**, y conviven a propósito.

### Por capas: el front office

`systems/front-office` es un sistema con dominio propio (estancias, folios, kárdex, habitaciones), y está
en capas con puertos y adaptadores:

```text
io.mateu.ecdemo1.frontoffice
├── DemoFrontOfficeApplication
├── domain/          agregados y puertos, Java puro
│   ├── stay/        Stay, CheckInChecklist, ForcedCheckIns, StayRepository…
│   ├── folio/       Folio, FolioLine, ChargeKind, FolioRepository
│   ├── guest/  room/  catalog/  automation/  notice/  registration/
├── application/     casos de uso (@Service): CheckInService, CheckOutService, FolioService…
├── infra/           adaptadores
│   ├── persistence/ Spring Data JDBC: *Entity, *Crud, H2*Repository, Jdbc*
│   ├── pms/  mdm/  notices/  registration/   Kafka de entrada y salida, por sistema
│   ├── outbox/  audit/  api/  mcp/  security/  config/  scanner/  crs/
└── ui/              pantallas Mateu: checkin/, checkout/, walkin/, reservas/, common/…
```

El dominio no importa nada fuera de `domain` (ni Spring ni persistencia); `application` usa el dominio y
algunos adaptadores; `infra` y `ui` dependen de los dos. El puerto es una interfaz del dominio y el
adaptador, una clase de paquete en `infra/persistence`:

```java
// systems/front-office/.../domain/folio/FolioRepository.java
/** Repository port of the {@link Folio} aggregate. */
public interface FolioRepository {
  Optional<Folio> findById(String id);
  Optional<Folio> findByStayId(String stayId);
  Folio save(Folio folio);
}

// systems/front-office/.../infra/persistence/H2FolioRepository.java
/** H2 adapter of the {@link FolioRepository} port. */
@Repository
class H2FolioRepository implements FolioRepository {
  private final FolioCrud crud;   // Spring Data JDBC, sobre FolioEntity
  ...
```

Los agregados son `record` que validan en el constructor compacto (`Folio`: «A folio belongs to a
stay») y la tabla es otra clase (`FolioEntity.of(folio)` / `toDomain()`). Solo la persistencia va detrás
de puertos: la salida a otros sistemas (`infra.pms.ReceptionReports`, `infra.outbox.CommandOutbox`,
`infra.mdm.Kardex`) la usa `application` directamente, sin interfaz intermedia. `CheckInService` y
compañía son la frontera; las pantallas de `ui` los llaman a ellos, no a los repositorios.

`systems/crs/booking` y `systems/erp` siguen la misma forma (`application`, `domain`, `infra`, más
`worker` en el CRS).

### Por funcionalidad: los servicios de integración

Un servicio de la ACL no tiene un dominio rico que proteger: traduce, decide causas y responde tareas.
Ahí el paquete es **lo que hace**:

```text
io.mateu.ecdemo1.mapping                      io.mateu.ecdemo1.pmsintegration
├── worker/      MappingTasks, TaskHandlers   ├── worker/       PmsTasks, TaskHandlers, ReceptionHandlers…
├── dictionary/  Dictionary, Gaps, Pending    ├── ohip/         OhipClient, OperaReservations, OperaProfiles…
├── causes/  prepare/  proposals/  commands/  ├── frontoffice/  OperaStays, StayMapper, PmsEvents…
├── store/       entidades JPA y repositorios ├── write/        ReservationPayload, PackageRules
├── queries/     lecturas para las pantallas  ├── connections/  clients/
├── outbox/      Outbox (sobre messaging)     ├── rest/         controladores de consulta
├── rest/  mcp/  ui/pages/  ui/suppliers/     ├── config/       *Properties, TolerantReader
├── clients/  audit/  config/  tracing/       └── tracing/
```

- **`worker/`** es la entrada desde el motor: una `@Configuration` que declara un `TaskRegistration` por
  tarea y un `TaskHandlers` que las atiende (ver [Workers y tareas](/contratos/workers-y-tareas/)).
- **`store/`** (mapping) son entidades JPA; **pms-integration-service no tiene `store/`** porque no tiene
  base de datos: su estado es el de Opera.
- **`ohip/`** es el único paquete que conoce la API de Opera. `OhipClient` es «the one door to OHIP» y
  convierte cada respuesta en un resultado, un fallo transitorio (`PmsTransientException`) o un rechazo
  (`PmsRejectedException`); `worker/` decide qué hacer con cada uno.
- **`mcp/`** y **`ui/`** solo existen donde hay operativa que enseñar: el mapping los tiene, el conector
  no.

El resto de `integration/` y `control-plane/` sigue este estilo (`customer-mdm-service`: `consolidation`,
`resolution`, `salesforce`, `store`…; `integrations-service`: `lifecycle`, `crypto`, `backfill`…).
`notices` y `registration-rules` quedan en medio: `application`, `infra` y `store`.

## Las piezas compartidas

| Pieza | Qué da | Cómo se usa |
| :---- | :----- | :---------- |
| `contracts-<contexto>` | Los records de los mensajes y las llamadas HTTP | Una dependencia por contexto que el servicio habla: el front office toma siete, el conector ocho |
| `contracts-testing` | `TopicSpec`, `Contracts.topic(…)`, `ServedTasks` | Scope `test` |
| `supporting/messaging` | `Outbox`, `OutboxRelay`, `Inbox`, `EngineOutbox`, `MessagingSchema` | Autoconfiguración: tenerlo en el classpath basta |
| `supporting/agent-sql` | `AgentSql`, `SqlGuard`, `AgentViews` | Consultas sobre las vistas `agent.*` de cada servicio; ver [Cómo lee los datos el agente](/ia/datos-del-agente/) |
| `supporting/demo-reset` | `DemoReset`, `DemoResetPlan`, `DemoResetTask` | Cada servicio declara qué vacía y sirve `reset@1` en su topic; ver [La página Demo](/operacion/pagina-demo/) |
| `supporting/ui-commons` | `CatalogueCrud`, `DbPaging`, `UserWidget`, `ApiUsageWidget`, `Html` | Clases que las pantallas Mateu extienden o usan |
| `grpc-interface` | Los stubs de `auth.proto` | Solo `users` |

`messaging` crea sus tablas (`outbox_message`, `inbox_entry`) sin Hibernate y de forma aditiva, y su
autoconfiguración vive **fuera de `io.mateu` a propósito**:

```java
// supporting/messaging/src/main/java/ecdemo1/messaging/autoconfigure/MessagingAutoConfiguration.java
/* <p>Outside {@code io.mateu} on purpose: the configuration Mateu's annotation processor generates in
 * every service with a UI component-scans all of {@code io.mateu}, with none of Boot's filters — an
 * auto-configuration there would be taken as one of the service's own configurations, and its
 * conditions evaluated before the service's beans are known. */
```

Cada servicio envuelve el outbox compartido con el suyo, que habla en sus términos:
`mapping/outbox/Outbox` tiene `appendToEngine`, `appendNotification`… con
`@Transactional(propagation = MANDATORY)`, para que nadie escriba un mensaje fuera de la transacción de
la decisión que lo produce.

## Configuración

- **`application.yaml`** en casi todos; el front office usa `application.properties`. Cada valor que
  cambia por entorno es `${VARIABLE:defecto-local}`, con el defecto apuntando a `localhost` y al puerto
  del servicio (ver [Configuración](/referencia/configuracion/)).
- Lo propio del servicio va bajo un prefijo y se lee con un **`@ConfigurationProperties` record**, con
  los defectos en el constructor compacto y un `@param` por componente:

```java
// integration/pms-integration-service/.../config/PmsIntegrationProperties.java
@ConfigurationProperties("pms-integration")
public record PmsIntegrationProperties(String crsIntegrationUrl, String mappingUrl, String integrationsUrl,
                                       String customerMdmUrl, java.util.List<String> noShowCancellationCodes,
                                       Duration alertAfter) {
    public PmsIntegrationProperties {
        if (alertAfter == null) alertAfter = Duration.ofMinutes(10);
        ...
```

- **`TolerantReader`** (en `config/`): una copia del `ObjectMapper` que ignora campos desconocidos para
  leer lo que mandan otros. Es un `@Component` y no un bean `ObjectMapper` «because declaring one would
  switch Spring Boot's off».

### Esquema: JPA o `schema.sql`

| Servicio | Persistencia | Esquema |
| :------- | :----------- | :------ |
| La mayoría (mapping, MDM, integraciones…) | JPA / Hibernate | `ddl-auto: ${DDL_AUTO:update}`, sin migraciones |
| Front office | Spring Data JDBC | `schema.sql` idempotente (`create table if not exists`), `spring.sql.init.mode=always` |
| `messaging` | `JdbcTemplate` | `MessagingSchema`: crea lo que falta, nunca borra ni renombra |
| pms-integration-service | — | Sin base de datos |

El precio de `ddl-auto: update` está escrito en el código: `mapping/store/StaleEnumChecks` borra al
arrancar la restricción de enum que Hibernate creó y nunca reescribe (ver
[Problemas conocidos](/operacion/problemas-conocidos/)).

## Pruebas

`src/test/java` repite los paquetes de `main`, así que una prueba vive junto a lo que prueba
(`mapping/worker/TaskHandlersTest`, `pmsintegration/ohip/OperaUsageTest`). Tres tipos se repiten:

- **Unitarias** sin Spring sobre la clase (`StayMapperTest`, `ReservationPayloadTest`).
- **De servicio** con `@SpringBootTest` y Testcontainers (Postgres y Redpanda), como
  `mapping/MappingTest`; el front office arranca contra H2 en memoria con su propio
  `src/test/resources/application.properties`.
- **De contrato**: `*ContractsTest` (esquemas de topics) y `worker/ServedTasksTest`, que escribe
  `contracts/workers/<servicio>.tasks`. Ver [Pruebas](/desarrollo/pruebas/).

## Convenciones

- **Una carpeta, un módulo, una imagen**, con el nombre de la carpeta.
- **Records** para casi todo lo que es dato: agregados del dominio del front office, mensajes de
  `contracts`, propiedades de configuración, entradas y salidas de las tareas.
- **Los comentarios cuentan por qué**, en inglés, y suelen ser la mejor documentación del diseño: el
  Javadoc de una clase dice qué papel tiene y qué decisión la explica (`OhipClient`, `Inbox`,
  `TolerantReader`, `StaleEnumChecks`).
- **El código en inglés**, salvo las pantallas del front office, que se llaman como las ve recepción:
  `ui/reservas/ReservasListing`, `ui/automatizaciones/`, `ui/walkin/PrecioWalkIn`.
- **Visibilidad de paquete** para los adaptadores (`class H2FolioRepository`, `interface FolioCrud`):
  desde fuera solo se ve el puerto.
- **Un `TracingConfig`** en cada servicio con Kafka (en `tracing/`, o `infra/config/` en el front office), para que la traza siga de un mensaje al
  siguiente.
