---
title: Despliegue
description: Cómo se construyen las imágenes, cómo se despliega ec1 desde cero y cómo se despliega un cambio.
---

```sh
./deploy/build-images.sh TAG     # las imágenes de este repositorio → Docker Hub
./deploy/deploy.sh               # todo lo demás, idempotente
```

Hace falta `kubectl` apuntando al clúster de CloudFleet, `helm`, `docker` y `gh`. El acceso al
clúster se pide; no se lo concede uno mismo.

## `build-images.sh`

Instala primero lo que no es una aplicación pero de lo que compilan las demás —`grpc-interface`, los
contratos, `messaging`, `agent-sql`, `demo-reset` y `ui-commons`— y después, por cada módulo de la lista `APPS`, hace un
`clean package` y construye la imagen con `docker buildx build --platform linux/amd64 --push`. Las
imágenes se llaman `miguelperezcolom/ec-demo1-<carpeta>`; las consolas se construyen dos veces (Vaadin
y `-Predwood`, `-redwood` en el nombre). Al final construye la del sitio de documentación (`doc/`, sin
paso de Maven) con su propio tag, `DOCS_TAG`.

La lista actual tiene **23 aplicaciones Java**; las dos consolas se construyen con dos renderers, por
lo que salen **25 imágenes Java y una de documentación**. `opera-mock` queda fuera de `APPS`.

- **El tag se pasa siempre.** Es uno solo para todas las imágenes de esa ejecución, y sin él el script
  no construye nada. Como cada manifiesto fija el suyo, una ronda normal no usa este script entero (ver
  [Desplegar un cambio](#desplegar-un-cambio)).

- **`DOCS_TAG` es independiente de `TAG`.** El script usa `0.1.0` si no se indica; el manifiesto actual
  fija `0.4.17`. Para publicar una versión nueva del sitio hay que pasar `DOCS_TAG` explícitamente y
  actualizar `81-docs.yaml` con ese mismo tag.

- **`clean` no es paranoia.** El procesador de anotaciones de Mateu escribe el controlador de la página
  de arranque en `target/generated-sources`, y Maven no lo vuelve a ejecutar si solo cambió
  `mateu.version`: sin `clean`, la imagen lleva el bundle nuevo detrás de un arranque viejo. Y el
  segundo build de una consola reutilizaría el `target/` del primero: una imagen Redwood hecha sobre
  restos de Vaadin es una imagen Vaadin con otro nombre.
- **Solo `linux/amd64`**, como el `nodeSelector` de todos los despliegues: el pool de Karpenter puede
  dar nodos arm64, y una imagen de una sola arquitectura en un nodo arm64 es un pod que no arranca.
- El motor (orquestador, formularios, reglas) corre de imágenes publicadas, igual que Keycloak,
  PostgreSQL y Redpanda.

## `deploy.sh`

Despliega todo desde un clúster vacío, en seis pasos, y es **idempotente**: volver a ejecutarlo
converge el clúster.

1. Requisitos del clúster: el controlador de ingress y el emisor de certificados.
2. Namespaces y secretos.
3. El motor (PostgreSQL, Redpanda, orquestador, formularios y reglas), desde el chart vendorizado
   (`deploy/chart/eventconductor`, ver `VENDORED.md`).
4. Keycloak, consolas, gateway, ingress, servicios, plano de control.
5. Observabilidad (Prometheus, Grafana, Loki, Tempo, Alloy), dashboards y reglas de alertas.
6. Esperar a que todo esté listo.

**Las contraseñas se generan la primera vez** en `deploy/.secrets/credentials.env`, que git ignora, y
una segunda ejecución no las cambia (añade solo las que falten). Lo que se compra o se pide no se
genera, y cada secreto se crea solo si su valor está:

| Secreto | De dónde |
| :-- | :-- |
| `ec-opera` | `OPERA_*` en `credentials.env` (sin él, integrations-service no arranca) |
| `ec-salesforce` | `SF_DOMAIN`, `SF_CLIENT_ID`, `SF_CLIENT_SECRET` |
| `ec-anthropic` | `ANTHROPIC_API_KEY` |
| `ec-agent-reader` | `AGENT_DB_USERNAME=agent_reader` y `AGENT_DB_PASSWORD`, generada una vez; lectura SQL de los agentes del CRS y del front office |
| `postfix-relay` | `POSTFIX_RELAY_PASSWORD` (vacío si no está: el correo se queda en cola) |
| `ec-webpush` | `~/.config/ec-demo1/webpush.env` (las claves VAPID; unas nuevas dejan huérfanos los navegadores suscritos) |
| `ec-googlechat` | `GOOGLE_CHAT_WEBHOOK`, **solo si el secreto no existe**: el de ec1 lleva un segundo espacio añadido a mano |

**Las credenciales de los LLM** no son de `deploy.sh`: se dan de alta en la consola de control (*IA →
LLMs*) y se guardan en `cp-postgres`, cifradas con `CP_CRYPTO_KEY`.

:::note[Desde cero, de verdad]
Hasta el 07-10-2026, `deploy.sh` no reconstruía ec1 entero: no aplicaba las dos consolas Redwood
(`31-shell-redwood`, `73-control-shell-redwood`), `74-api-mcp` ni `01-node-sysctl`, y `ec-webpush` y
`ec-googlechat` se habían creado a mano. Se descubrió desplegando en un clúster vacío
([Entorno local](/desarrollo/entorno-local/)), que es la forma de comprobarlo: todo manifiesto nuevo va
también en `deploy.sh`.
:::

Tampoco crea el DNS: `ec1` y `*.ec1` tienen que apuntar al
balanceador del ingress antes de que Let's Encrypt emita nada; el comodín cubre cualquier host nuevo, y
cada uno recibe su certificado por HTTP-01 la primera vez que se resuelve.

:::danger[Lo que no se regenera]
`CP_CRYPTO_KEY` e `INTEGRATIONS_CRYPTO_KEY` cifran las credenciales de los LLM y los secretos de
conexión con Opera. Regenerarlas deja todo lo guardado sin poder descifrarse.
:::

## Desplegar un cambio

Lo habitual no es `deploy.sh` entero sino una ronda de módulos:

1. Compilar y subir **solo los módulos que cambiaron**, con un **tag nuevo** por módulo (cada
   manifiesto fija su tag, p. ej. `ec-demo1-pms-integration-service:0.39.3`, y `imagePullPolicy:
   IfNotPresent`: reutilizar un tag no despliega nada).
2. Subir los tags en `deploy/manifests/*.yaml`.
3. `kubectl apply -f` de esos manifiestos y esperar el `rollout status`.
4. Un commit `deploy: …` con las versiones, por PR.

Si cambió una librería compartida (`contracts/`, `messaging`, `agent-sql`, `demo-reset`, `ui-commons`)
o la versión de Mateu, los que dependen de ella también cambian:
se reconstruyen todos los afectados, con `clean`.

:::caution[El disco se llena]
`docker buildx build --push` con el builder por defecto **guarda también la imagen en local**: cada
ronda suma ~0,5 GB por imagen. Después de subir, se borran las copias locales de
`miguelperezcolom/ec-demo1-*` que no use ningún contenedor y se hace `docker builder prune -af`. Mirar
`df -h /` antes de una ronda grande.
:::

## Las bases de datos al actualizar

Los servicios usan `ddl-auto: update`, que **añade** pero nunca rehace: no borra una columna renombrada
ni reescribe la restricción de un enum. Un campo nuevo que en local funciona puede fallar en ec1, cuya
base de datos es más vieja. Ver [Problemas conocidos](/operacion/problemas-conocidos/).
