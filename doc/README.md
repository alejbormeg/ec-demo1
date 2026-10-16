# ec-demo1 — documentation site

The project's documentation, in Spanish, as an Astro + Starlight site — the same stack and layout as
EventConductor's and Mateu's (`doc/` in each).

```sh
npm ci
npm run dev       # http://localhost:4321
npm run build     # → dist/
npm run preview   # inspect the generated site locally
```

Run these commands from `doc/`, using the Node version selected by `Dockerfile` (Node 24). `npm ci`
uses the committed `package-lock.json`; changing documentation does not require updating dependencies.

Pages are Markdown under `src/content/docs/`; the sidebar is in `astro.config.mjs`. A ```` ```mermaid ````
block is drawn in the browser (the rehype plugin in `astro.config.mjs` and the script in the head).

The current source baseline, declared versions and evidence limits are recorded in
[`Versiones y alcance`](src/content/docs/referencia/estado-del-repositorio.md). When refreshing content,
check the code and manifests first: the root README and older demo notes can lag behind them. In
particular:

- Maven properties come from each module's `pom.xml`; engine image tags come from
  `deploy/values/eventconductor.yaml`.
- Service images, ports and environment variables come from `deploy/manifests/`; gateway routes and
  access rules come from `consoles/gateway/src/main/`.
- Worker task inventories are `contracts/workers/*.tasks`; event shapes and examples are in
  `contracts/schemas/`.
- The active workflow definitions and AI catalogue belong to separate repositories. The examples in
  `gitops/example/` do not establish what an environment currently runs.

Before committing, run `npm run build`, check that every sidebar slug resolves, follow internal links
and anchors, and preview changed pages. A successful site build does not verify a live deployment;
keep historical test results dated and state when a behavior was checked only in source.

The ec1 manifest serves it at https://doc.ec1.mateu.io, behind a username and password: `Dockerfile` builds it
with Node and serves `dist/` with an unprivileged nginx (`nginx.conf`); `deploy/build-images.sh` pushes
`miguelperezcolom/ec-demo1-docs` and `deploy/manifests/81-docs.yaml` deploys it.

`DOCS_TAG` is separate from the application `TAG` in `deploy/build-images.sh` and defaults to `0.1.0`.
The current manifest uses `0.4.17`: publishing an update requires choosing a new documentation tag
and changing that manifest explicitly. Editing these pages does not deploy them.
