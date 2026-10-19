---
title: Entorno local
description: La demo entera en esta máquina (deploy/local, un clúster kind con los mismos manifiestos que ec1), y la batería CRS → Opera contra el doble de OHIP (e2e/poc-acl-local).
---

Hay dos formas de tenerlo en local:

| | **La demo entera** (`deploy/local`) | **La batería** (`e2e/poc-acl-local`) |
| :-- | :-- | :-- |
| Qué es | Un Kubernetes local (kind) con **lo mismo que ec1**: consolas, Keycloak, motor, servicios, observabilidad | Los servicios del camino CRS → Opera como procesos Java, con el orquestador real |
| Opera y Salesforce | Los **reales** (el UAT de OHIP y la org), como ec1 | El **doble de Opera**; Salesforce opcional |
| Para qué | Ensayar o hacer la demo sin ec1, probar un despliegue, los e2e de las consolas y de la demo | Probar el camino de integración con fallos inyectados |

## La demo entera: `deploy/local`

```sh
deploy/local/up.sh      # crea (o converge) el clúster y despliega todo con deploy.sh
deploy/local/down.sh    # borra el clúster; las imágenes y los volúmenes se quedan en disco
```

Es **Kubernetes**, no Docker Compose: [kind](https://kind.sigs.k8s.io/) arranca un nodo dentro de un
contenedor Docker (`ec-demo1-local-control-plane`) y dentro corre un clúster completo —API server,
etcd, kubelet, containerd—. Sobre él, `up.sh` ejecuta el **mismo `deploy.sh`** que ec1 sobre una copia
de `deploy/` con los nombres cambiados. Los pods no se ven en `docker ps`: se ven con `kubectl`.

| Consola | URL |
| :-- | :-- |
| Datos (Vaadin / Redwood) | http://ec1.localhost:8800 · http://rw.localhost:8800 |
| Control (Vaadin / Redwood) | http://console.localhost:8800 · http://rw-console.localhost:8800 |
| Front office | http://front.localhost:8800 |
| Keycloak · Grafana · Documentación | http://auth.localhost:8800 · http://grafana.localhost:8800 · http://doc.localhost:8800 |

El usuario `demo` y su contraseña son los de ec1 (`DEMO_PASSWORD`). La documentación pide el usuario
`riu` con `DOCS_PASSWORD`, de `~/.local/share/ec-demo1-local/credentials.env`.

### Cómo está hecho

- **`*.localhost`**: los navegadores lo resuelven solos a 127.0.0.1 y lo tratan como contexto seguro, así
  que keycloak-js, la PWA del front office y Web Push funcionan con `http`. Dentro del clúster, CoreDNS
  manda todo `*.localhost` al ingress: un pod llega a Keycloak por la **misma URL que el navegador**, y el
  emisor del token coincide.
- **Puerto 8800**, no 80: en esta máquina hay un k3s cuyo Traefik se queda `127.0.0.1:80` y `:443`
  mediante iptables, antes que el mapeo de puertos de Docker (contesta «404 page not found»). El Service
  del ingress escucha en 8800 también dentro del clúster, y los backends reciben el host **sin** el puerto
  (`upstream-vhost`), como en ec1: el gateway enruta por host exacto.
- **Keycloak compilado en las consolas**: `@KeycloakSecured(url = "https://auth.ec1.mateu.io")` acaba en
  la página de arranque al compilar. En local, el nginx del ingress lo reescribe en el HTML
  (`sub_filter`). El arreglo de fondo es que Mateu deje cambiar esa URL en ejecución.
- **Disco**: el containerd del nodo (las imágenes) y los volúmenes se montan desde
  `~/.local/share/ec-demo1-local`, en `/home`; en `/` solo va el contenedor del nodo.
- **Su propio kubeconfig** (`~/.local/share/ec-demo1-local/kubeconfig`), nunca mezclado con el de ec1:
  `kubectl` y `deploy/demo/*.sh` siguen apuntando a ec1 salvo que `KUBECONFIG` diga otra cosa:

  ```sh
  export KUBECONFIG=~/.local/share/ec-demo1-local/kubeconfig
  python3 deploy/demo/ec1.py health
  ```
- **Su propio contexto de Opera** (`ECLOCAL-<MMddHHmm>`, en el ConfigMap `ec-demo-run`), creado antes de
  que nada pueda escribir, y **sin el webhook de Google Chat**: sus alertas no llegan al chat de ec1.

### En un Mac (Apple Silicon)

`up.sh` funciona igual en un Mac con Docker Desktop, con tres preparativos y unas diferencias que hace
él solo:

```sh
brew install kind gnu-sed
# Docker Desktop → Settings → Resources: 24 GB de memoria y unos 160 GB de disco (con 63 se llenó)
```

- **Sin el pin a amd64**: el nodo es arm64. En la copia de `deploy/` se quitan los
  `kubernetes.io/arch: amd64` y las peticiones de recursos se reducen al mínimo (las de ec1 suman unos
  30 GB y un pod que no cabe se queda en Pending; la demo entera usa unos 18).
- **Las imágenes de ec-demo1 se construyen aquí y se cargan en el nodo**, sin pasar por Docker Hub
  (subirlas desde casa costaba 18 minutos por imagen):

  ```sh
  deploy/local/load-images.sh            # todas, con la etiqueta de los manifiestos
  deploy/local/load-images.sh 0.76.0 systems/front-office   # solo un módulo
  kubectl -n ec-demo1 rollout restart deploy/front-office
  ```

  `build-images.sh` sigue siendo el camino para ec1, y publica amd64 y arm64. Las imágenes del motor
  solo están en amd64 en Docker Hub: se construyen en arm64 con el jar de la versión publicada y `up.sh`
  las carga en el nodo (`kind load`) en lugar de descargarlas:

  ```sh
  img=miguelperezcolom/orchestrator-standalone-app:2.27.1   # y forms-, rule-
  c=$(docker create --platform linux/amd64 $img); docker cp $c:/opt/app/app.jar <app>/target/<app>-2.27.1.jar
  docker buildx build --builder desktop-linux --platform linux/arm64 -f <app>/Dockerfile.ci -t $img --load <app>
  ```

  (`<app>` es `apps/orchestrator-standalone-app` de un checkout de eventconductor en su tag.) El servidor
  de embeddings usa su etiqueta arm64 (`cpu-arm64-…`).
- **Sin sondas de liveness**: unas 35 JVM arrancando a la vez en un portátil tardan minutos; la sonda las
  mataba antes de terminar y los reinicios se realimentaban hasta dejar el nodo sin hacer otra cosa
  (carga 130 con 14 núcleos). La de readiness se queda: nada recibe tráfico antes de estar listo.
- **El containerd y los volúmenes van dentro de la VM de Docker**, no en `~/.local/share`: overlayfs no
  funciona sobre una carpeta compartida con macOS. `down.sh` se los lleva.

### Lo que no trae una instalación nueva

- **Las credenciales de los LLM** no las crea ningún despliegue: se dan de alta en la consola de control
  (*IA → LLMs*) y se guardan cifradas con `CP_CRYPTO_KEY`. Sin ellas el agente de mapeado no propone nada
  («Agent 'mapping-agent' names LLM 'alejandro' … which is no credential»). Las de ec1 se copiaron
  cifradas, de base de datos a base de datos, junto con su `CP_CRYPTO_KEY` en el `credentials.env` local.
- **Los datos de la demo**: el onboarding se hace como en ec1 (el paso 1 del e2e de la demo, o a mano).
  Una base de datos nueva arranca con 9 estancias de ejemplo en el front office.

### Los recursos

Con la demo cargada, el nodo usa unos **18 GB de memoria** y menos de un núcleo en reposo. En esta
máquina (32 hilos, 62 GB) deja sitio para desarrollar. Para liberarlo sin perder nada:

```sh
docker stop ec-demo1-local-control-plane    # para el clúster entero
docker start ec-demo1-local-control-plane   # vuelve tal como estaba
```

:::caution[Opera y Salesforce son los de ec1]
El MDM local comparte la org de Salesforce y su cupo diario, y las integraciones escriben en el mismo UAT
de Opera. Por eso la demo vive **en un sitio o en el otro, nunca en los dos a la vez**: ver
[La demo](/operacion/demo/#la-demo-vive-en-un-solo-sitio).
:::

## La batería: `e2e/poc-acl-local`

`e2e/poc-acl-local` levanta el camino entero en una máquina, contra el **orquestador real de
EventConductor** y el **doble de Opera** (`opera-mock`). Nada llega a un tenant real de Opera.

```sh
# desde la raíz: el reactor ordena los contratos y todas las librerías compartidas
mvn -B -ntp clean package -DskipTests

./e2e/poc-acl-local/infra.sh     # Postgres (una base de datos por servicio), Redpanda, mailpit, el orquestador
./e2e/poc-acl-local/apps.sh      # los servicios; logs en e2e/poc-acl-local/logs/
python3 e2e/poc-acl-local/scenario.py
python3 e2e/poc-acl-local/salesforce.py   # opcional: la ida y vuelta del MDM por Salesforce
```

`infra.sh` importa las definiciones de un clon local de ec-definitions junto a este repositorio
(`EC_DEFINITIONS` para otra ruta, `EC_DEFINITIONS_BRANCH`, `master` por defecto). Las bases de datos y
el broker van en tmpfs: cada ejecución empieza de cero.

### Qué recorre `scenario.py`

1. Los interlocutores sembrados esperan a que se mapee su tipo de perfil.
2. Una reserva de un hotel sin integración se retiene —en `INTEGRATION_INACTIVE:PMI01` y en sus códigos
   sin mapear— y nada llega a Opera.
3. Se registra la integración del hotel y se da de alta puerta a puerta, hasta que la activación libera
   lo retenido; la reserva está en Opera con los códigos traducidos, tarifas fijas por noche, el régimen
   como paquete, el perfil del interlocutor y la versión del CRS en un UDF.
4. Una modificación se escribe encima, en orden, sin aplicar dos veces el depósito.
5. Opera contesta 503 un rato: la escritura se reintenta hasta que entra, con un aviso mientras tanto.
6. Opera rechaza (no queda habitación): el proceso espera en una causa `PMS_REJECTED`.
7. Cancelar libera la habitación; resolver la causa deja pasar la reserva rechazada.
8. La cancelación de una reserva que Opera aún no tiene espera a que llegue.
9. Se avisó a las personas: causas, reintentos, rechazos y altas que necesitan a alguien.
10. Los pasajeros son clientes en el MDM.

`salesforce.py`: dos reservas del mismo huésped escrito distinto son dos clientes provisionales; los dos
se hacen contactos y Salesforce los empareja como posibles duplicados; se fusionan; la fusión vuelve como
`ClienteConsolidado__e` y el perfil de Opera del absorbido toma el código del superviviente.

### Salesforce en local: cuidado con el cupo

El MDM local limpia en una org real de Salesforce si `~/.config/ec-demo1/salesforce.env` (o `SF_ENV`)
tiene las credenciales de su app (`SF_DOMAIN`, `SF_CLIENT_ID`, `SF_CLIENT_SECRET`). Sin el fichero sigue
resolviendo identidades y no manda nada a Salesforce.

:::caution[La org es la misma que la de ec1]
El entorno local y ec1 **comparten la org** y su cupo de 15.000 llamadas al día. Un MDM local olvidado
arrancado agotó el cupo entero. No dejar el entorno local corriendo con Salesforce activo, y usar el
intervalos de sondeo de `application.yaml` (24 h para las redes de seguridad de consolidaciones,
cambios y avisos; el camino principal es Pub/Sub). El MDM avisa al arrancar si se configura un sondeo
de menos de 5 min.
:::

### El doble de Opera

`opera-mock` implementa las rutas y formatos de las Property APIs que usa el conector, con estado en
memoria, validación, disponibilidad y **fallos inyectables** (503, rechazos, falta de disponibilidad),
que contra un tenant real no se pueden provocar. Sigue las diferencias del tenant real que ha ido
encontrando el conector. Ya no se despliega en ec1: solo existe para esta batería.
