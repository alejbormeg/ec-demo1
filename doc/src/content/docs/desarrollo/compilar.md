---
title: Compilar
description: Cómo se compila ec-demo1 — el orden de las librerías compartidas, Maven con -am y por qué clean.
---

Java 21. Cada módulo es su propio proyecto Maven (Spring Boot 3.4 la mayoría, el gateway incluido;
Spring Boot 4.0 las consolas `shell` y `control-shell`, `ia-control-plane`, `users`, `content`,
`ui-commons` y `grpc-interface`, y 4.1 el front office y `ia-agent`); el `pom.xml` raíz solo los agrega, así que
`mvn -DskipTests package` en la raíz lo compila todo.

## El orden

Las librerías compartidas no tienen imagen. Si se compila cada aplicación por separado, se **instalan** primero en
`~/.m2`, en este orden (es lo que hace `deploy/build-images.sh`):

```sh
(cd control-plane/grpc-interface && mvn -B -ntp -DskipTests install)   # los stubs de gRPC de users
(cd contracts && mvn -B -ntp -DskipTests install)                       # el lenguaje publicado
(cd supporting/messaging && mvn -B -ntp -DskipTests install)            # outbox, relay e inbox
(cd supporting/agent-sql && mvn -B -ntp -DskipTests install)            # consultas SQL de los agentes
(cd supporting/demo-reset && mvn -B -ntp -DskipTests install)           # reset de cada servicio
(cd supporting/ui-commons && mvn -B -ntp -DskipTests install)           # lo que comparten las UIs
```

## Un módulo, bien

```sh
mvn -B -ntp -pl integration/pms-integration-service -am test
```

- **Siempre `-am`** en un cambio que cruza módulos: `-pl` solo compila ese módulo y toma los hermanos de
  `~/.m2`, que tiene lo último que se instaló. Un `-pl` en verde con un contrato cambiado no demuestra
  nada.
- **`clean package`** para lo que se va a ejecutar: sin `clean`, el jar de Boot conserva en
  `BOOT-INF/lib` la versión de los contratos que copió la vez anterior, y el procesador de anotaciones de
  Mateu no se vuelve a ejecutar si solo cambió `mateu.version`.

## Las versiones que se comparten

| Propiedad | Valor | Dónde |
| :-------- | :---- | :---- |
| `mateu.version` | `3.0-alpha.422` | En **todos** los módulos con interfaz: una sola versión, porque las consolas pintan las pantallas de los demás pods |
| `eventconductor.version` | `2.25.1` | Los módulos que hablan con el motor (`shared`, `worker-kafka`) |
| `testcontainers.version` | `1.21.4` | En los módulos Boot 3.4 que usan Testcontainers, por Docker 29 |

Subir Mateu: ver [Mateu](/desarrollo/mateu/).

Estos son los valores de los `pom.xml`, no los tags de todas las imágenes del motor: el despliegue fija
por separado orquestador, formularios y reglas. Ver [Versiones y alcance](/referencia/estado-del-repositorio/).

## Los esquemas y las tareas generados

Dos ficheros del repositorio los genera el build y el build falla si no coinciden:

- `contracts/schemas/<topic>/v<N>.schema.json`, del build del dueño del topic;
- `contracts/workers/<servicio>.tasks`, del `ServedTasksTest` de cada worker.

Se regeneran con `mvn test -Dcontracts.write=true` (o `CONTRACTS_WRITE=true`) y se suben con el cambio.
Ver [Contratos](/contratos/librerias-y-esquemas/).
