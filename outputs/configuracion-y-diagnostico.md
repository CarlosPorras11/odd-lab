# Configuración y diagnóstico — E-02

Contrato implementado el 23 de octubre de 2026. Amplía [estructura y CLI](estructura-y-cli.md) sin implementar almacenamiento transaccional ni runtime.

## Comandos

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar init --repo /ruta/al/repositorio
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar doctor --repo /ruta/al/repositorio --json
```

Ambos requieren `--repo` explícito y admiten `--help` y `--json`. La ruta debe existir y ser la raíz de un repositorio Git de trabajo, aunque todavía no tenga commits. Se admiten worktrees con `.git` como archivo. Se rechazan repositorios bare y subdirectorios; no se descubre ni inicializa implícitamente otro repositorio. Las rutas se resuelven a su ubicación real. Se requiere Git local.

`--json` pertenece a `init` y `doctor`, después del nombre del comando; la raíz conserva ayuda y versión. No se pregunta ni se autoriza inferencia.

## Descriptor v1

`init` crea `.odd-lab.yaml` solo si no existe. Ejemplo de estructura —el UUID es ilustrativo, cada inicialización nueva genera uno propio—:

```yaml
schemaVersion: 1
repositoryId: 46c9be93-efb0-4b24-9750-6e4952bbd175
name: mi-proyecto
policy:
  schemaVersion: 1
  allowedProviders: []
  networkAllowed: false
  maxActiveSubagents: 2
  maxCorrectionsPerTask: 2
  reservedActions:
    - PUBLISH
    - MODIFY_EXTERNAL_SYSTEMS
```

- Identidad UUID y nombre inicial derivado del directorio. No se guarda una ruta absoluta ni se configura un remoto.
- Todos los campos son obligatorios. No se admiten campos desconocidos, claves duplicadas, tipos incorrectos ni versiones desconocidas. No hay migración automática en E-02.
- Descriptor UTF-8, máximo 64 KiB, profundidad YAML acotada y constructor seguro, sin construcción de objetos arbitrarios. Los errores no reproducen su contenido.
- `allowedProviders`: identificadores únicos con patrón `[a-z][a-z0-9-]{0,63}`; inicialmente ninguno. `networkAllowed`: booleano, inicialmente falso.
- Límites enteros de 0 a 2 subagentes activos y de 0 a 2 correcciones por tarea. Las dos acciones reservadas son obligatorias y requieren decisión del usuario en la futura ejecución.
- Nombre no vacío, de hasta 200 caracteres y sin caracteres de control. El descriptor puede versionarse por decisión del usuario; no contiene credenciales.

Editar la política registra una configuración local; no conecta proveedores, no acredita autorización empresarial ni impone por sí mismo un sandbox. La composición con políticas de organización y el cumplimiento durante una ejecución se implementarán antes de habilitar un runtime.

## Efectos de `init`

1. Comprobar raíz Git, descriptor existente y tipos de las rutas previstas antes de escribir.
2. Rechazar enlaces simbólicos en descriptor, `.gitignore`, `.odd-lab` y sus cuatro directorios operativos; rechazar archivos en lugar de directorios. No borrar ni sustituir estos obstáculos.
3. Rechazar datos operativos ya presentes en el índice Git; no desversionarlos automáticamente.
4. Crear los directorios que falten: `.odd-lab/state`, `artifacts`, `workspaces`, `views`. E-02 solo prepara directorios, sin base de datos.
5. Añadir al `.gitignore` las reglas ancladas `/.odd-lab/state/`, `/.odd-lab/artifacts/`, `/.odd-lab/workspaces/`, `/.odd-lab/views/`. Conservar todos los bytes previos y, donde esté disponible, permisos POSIX del archivo. No excluir el descriptor.
6. Publicar el descriptor nuevo mediante creación exclusiva. Si ya existe y es válido, conservar contenido, identidad, comentarios y política; reparar únicamente directorios o exclusiones que falten.

Repetir `init` en un proyecto preparado no modifica sus archivos. `created` indica si se creó el descriptor, no si hubo reparaciones del layout. Las exclusiones se comprueban con Git y se detectan reglas posteriores que las anulen. No se crean commits, ramas, repositorios ni sesiones de IA.

La preparación de varios archivos no es una transacción. Ante fallo de E/S se informa de error; `init` puede reparar directorios y exclusiones con un descriptor válido. Un descriptor inválido se preserva para revisión manual. No se garantiza inicialización concurrente ni protección frente a cambios hostiles de enlaces entre comprobación y escritura; E-02 no demuestra el aislamiento exigido a los trabajadores futuros.

## Comportamiento de `doctor`

Solo lectura, incluidos fallos: no inicializa ni repara, no escribe temporales en el repositorio y no invoca un runtime. Git se ejecuta con tiempo de espera y bloqueos opcionales desactivados; se eliminan variables `GIT_*` heredadas que pudieran redirigir las consultas.

Comprueba raíz Git, descriptor, identidad/política, presencia de directorios, exclusiones efectivas y datos operativos versionados. Si faltan directorios o exclusiones, devuelve comprobaciones `ERROR` y código 2. Descriptor ausente o inválido detiene el diagnóstico con error concreto; no se declara sano ese proyecto.

Capacidades aún ausentes se indican explícitamente:

- `STORAGE: NOT_IMPLEMENTED`: todavía no existe registro transaccional (E-03).
- `RUNTIME: NOT_CONFIGURED`: sin motor conectado; no se ha probado autenticación, capacidades o sandbox (E-06).

`healthy=true` significa que la preparación local de E-02 es correcta; no que el sistema de agentes esté disponible. No se fuerza una escritura para comprobar permisos del almacenamiento ni se abre una base de datos.

## Salida y errores

Modo humano: resultados en stdout; errores y diagnósticos fallidos en stderr. Modo JSON: un objeto final en stdout con `schemaVersion: 1`, `command`, `result`, `pendingDecision: null` y `error`. `error` es `null` al completar; en fallo contiene `code` y `message`. No hay `runId` ni `state` porque aún no existe una ejecución. En errores de uso se mantiene el objeto JSON cuando se ha solicitado `--json` en el subcomando. La ayuda explícita sigue mostrando el texto de uso.

`init.result` contiene `repository`, `configuration` y `created`. `doctor.result` contiene `repository`, `configuration`, `healthy` y `checks`; se conserva en un diagnóstico fallido para poder consultar sus comprobaciones. Fallos anteriores a ese resultado devuelven `result: null`.

| Código estable | Salida | Significado |
| --- | --- | --- |
| `USAGE_INVALID` | 2 | Falta argumento o argumento no admitido |
| `INVALID_REPOSITORY`, `REPOSITORY_ROOT_REQUIRED` | 2 | Ruta inválida, bare o distinta de la raíz |
| `CONFIG_NOT_FOUND`, `CONFIG_INVALID`, `CONFIG_VERSION_UNSUPPORTED` | 2 | Descriptor ausente, inválido o versión desconocida |
| `UNSAFE_PROJECT_PATH`, `OPERATIONAL_DATA_TRACKED` | 2 | Conflicto de rutas o datos operativos versionados |
| `CONFIG_CHANGED`, `IGNORE_RULES_INEFFECTIVE` | 2 | Cambio durante preparación o exclusiones anuladas |
| `DIAGNOSTIC_FAILED` | 2 | Comprobaciones locales de doctor fallidas |
| `GIT_UNAVAILABLE` | 2 | No se pudo ejecutar Git |
| `PROJECT_IO_ERROR`, `GIT_FAILED`, `GIT_TIMEOUT`, `GIT_INTERRUPTED`, `INTERNAL_ERROR` | 1 | Fallo operativo, sin afirmar finalización |

El éxito de ambos comandos devuelve 0. No se imprimen trazas internas por defecto.

## Dependencias y fronteras

Dominio en `policy/domain` y `workspace/domain`; casos de uso y puerto en `workspace/application`; Git, archivos y YAML en `workspace/infrastructure`. Spring compone; Picocli presenta y traduce errores.

Se declaran directamente SnakeYAML y Jackson Databind 3, con versiones gestionadas por el parent Spring Boot 4.1.2: 2.6 y 3.1.7 respectivamente. JSON se concreta en E-02 por el contrato público de estos comandos; persistencia y migraciones siguen en E-03. Referencias: [SnakeYAML](https://github.com/snakeyaml/snakeyaml), [Jackson Databind](https://github.com/FasterXML/jackson-databind), [git rev-parse](https://git-scm.com/docs/git-rev-parse) y [Picocli](https://picocli.info/).
