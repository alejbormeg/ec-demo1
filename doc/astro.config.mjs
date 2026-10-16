// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// Mermaid, como en la documentación de EventConductor: los bloques ```mermaid se entregan tal cual
// en un <pre class="mermaid"> y el script de la cabecera los dibuja en el navegador.
function rehypePreMermaid() {
	return (tree) => {
		function visit(node) {
			if (!node.children) return;
			for (let i = 0; i < node.children.length; i++) {
				const child = node.children[i];
				if (
					child.tagName === 'pre' &&
					child.children?.[0]?.tagName === 'code' &&
					child.children[0].properties?.className?.includes('language-mermaid')
				) {
					const code = child.children[0].children[0]?.value || '';
					node.children[i] = {
						type: 'element',
						tagName: 'pre',
						properties: { className: ['mermaid'] },
						children: [{ type: 'text', value: code }],
					};
				} else {
					visit(child);
				}
			}
		}
		visit(tree);
	};
}

// https://astro.build/config
export default defineConfig({
	markdown: {
		rehypePlugins: [rehypePreMermaid],
	},
	integrations: [
		starlight({
			title: 'ec-demo1',
			description:
				'La PoC ACL de Riu sobre Mateu y EventConductor: CRS → Opera Cloud → front office, clientes en Salesforce, agentes de IA y el clúster ec1.',
			defaultLocale: 'root',
			locales: {
				root: { label: 'Español', lang: 'es' },
			},
			logo: { src: './src/assets/riu.svg' },
			favicon: '/favicon.svg',
			head: [
				// Protegida con usuario y contraseña en doc.ec1.mateu.io: que no la indexe nadie.
				{ tag: 'meta', attrs: { name: 'robots', content: 'noindex' } },
				{
					tag: 'script',
					attrs: { type: 'module' },
					content: `import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs'; mermaid.initialize({ startOnLoad: true });`,
				},
			],
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/miguelperezcolom/ec-demo1' },
			],
			sidebar: [
				{
					label: 'Primeros pasos',
					items: [
						{ label: 'Introducción', slug: 'guias/introduccion' },
						{ label: 'Arquitectura', slug: 'guias/arquitectura' },
						{ label: 'Consolas, gateway y seguridad', slug: 'guias/consolas-y-seguridad' },
						{ label: 'Los procesos del motor', slug: 'guias/procesos' },
					],
				},
				{
					label: 'Integración',
					items: [
						{ label: 'Del CRS a Opera', slug: 'integracion/crs-a-opera' },
						{ label: 'Alta de un hotel', slug: 'integracion/alta-de-un-hotel' },
						{ label: 'De Opera al front office', slug: 'integracion/pms-a-front-office' },
						{ label: 'Clientes: MDM y Salesforce', slug: 'integracion/clientes' },
						{ label: 'Causas, avisos y bandeja', slug: 'integracion/causas-y-avisos' },
						{ label: 'Avisos de recepción', slug: 'integracion/avisos' },
						{ label: 'Historial de clientes y Riu Class', slug: 'integracion/historial-y-riu-class' },
						{ label: 'El recorrido de una reserva', slug: 'integracion/recorrido' },
					],
				},
				{
					label: 'Contratos',
					items: [
						{ label: 'Librerías y esquemas', slug: 'contratos/librerias-y-esquemas' },
						{ label: 'Workers y tareas', slug: 'contratos/workers-y-tareas' },
					],
				},
				{
					label: 'Front office',
					items: [
						{ label: 'Un sistema propio', slug: 'front-office/sistema-propio' },
						{ label: 'Reglas de registro del kárdex', slug: 'front-office/reglas-de-registro' },
						{ label: 'Reconocer al cliente en el check-in', slug: 'front-office/reconocer-al-cliente' },
						{ label: 'Kárdex, caja y tableta', slug: 'front-office/kardex-caja-tableta' },
					],
				},
				{
					label: 'IA',
					items: [
						{ label: 'El plano de control de IA', slug: 'ia/plano-de-control' },
						{ label: 'GitOps del catálogo', slug: 'ia/gitops' },
						{ label: 'El agente (ia-agent)', slug: 'ia/agente' },
						{ label: 'A2A y guardarraíles', slug: 'ia/a2a-y-guardarrailes' },
						{ label: 'Cómo lee los datos el agente', slug: 'ia/datos-del-agente' },
						{ label: 'APIs como servidores MCP', slug: 'ia/api-mcp' },
					],
				},
				{
					label: 'Operación',
					items: [
						{ label: 'Despliegue', slug: 'operacion/despliegue' },
						{ label: 'Programaciones del motor', slug: 'operacion/programaciones' },
						{ label: 'El clúster', slug: 'operacion/cluster' },
						{ label: 'Observabilidad', slug: 'operacion/observabilidad' },
						{ label: 'Consumo de Salesforce y Opera', slug: 'operacion/consumo-apis-externas' },
						{ label: 'La demo: preparar y resetear', slug: 'operacion/demo' },
						{ label: 'La página Demo: reset y caída de Opera', slug: 'operacion/pagina-demo' },
						{ label: 'Usuarios, Keycloak y correo', slug: 'operacion/usuarios-y-correo' },
						{ label: 'Problemas conocidos', slug: 'operacion/problemas-conocidos' },
					],
				},
				{
					label: 'Diseño técnico',
					items: [
						{ label: 'Estructura del proyecto', slug: 'diseno/estructura' },
						{ label: 'Patrones', slug: 'diseno/patrones' },
						{ label: 'El código, de punta a punta', slug: 'diseno/recorrido-por-el-codigo' },
						{ label: 'Cómo extenderlo', slug: 'diseno/extender' },
					],
				},
				{
					label: 'Desarrollo',
					items: [
						{ label: 'Compilar', slug: 'desarrollo/compilar' },
						{ label: 'Entorno local', slug: 'desarrollo/entorno-local' },
						{ label: 'Pruebas', slug: 'desarrollo/pruebas' },
						{ label: 'Mateu', slug: 'desarrollo/mateu' },
					],
				},
				{
					label: 'Referencia',
					items: [
						{ label: 'Versiones y alcance', slug: 'referencia/estado-del-repositorio' },
						{ label: 'Servicios', slug: 'referencia/servicios' },
						{ label: 'Topics de Kafka', slug: 'referencia/topics' },
						{ label: 'Tareas', slug: 'referencia/tareas' },
						{ label: 'Configuración', slug: 'referencia/configuracion' },
					],
				},
			],
		}),
	],
});
