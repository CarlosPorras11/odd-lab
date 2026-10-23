# Registro transaccional y consultas — E-03

24 de octubre de 2026. Implementación local sin runtime ni ejecución de peticiones. Amplía [configuración E-02](configuracion-y-diagnostico.md) y [contratos](contratos-sistema.md).

## Uso público

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar init --repo /ruta/al/repositorio
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar status --repo /ruta/al/repositorio
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar status --repo /ruta/al/repositorio --run UUID --json
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar inspect --repo /ruta/al/repositorio --run UUID --json
```

Sustituir ruta y UUID por valores reales. Ambos comandos consultivos requieren `--repo`, ofrecen ayuda/versión y aceptan `--json` después del nombre del comando. `status` lista todas las ejecuciones en orden de fecha de creación descendente, con UUID como desempate estable, o consulta una concreta. `inspect` exige un UUID y muestra el registro detallado.

Un proyecto recién inicializado devuelve lista vacía, código 0 y «Sin ejecuciones registradas». E-03 no incorpora un comando público para crear ejecuciones: `run` y `resume` con simulación pertenecen a E-04. Las pruebas de consultas pobladas crean registros sintéticos mediante el puerto Java, sin añadir comandos ocultos ni datos de muestra al proyecto real.

Consultar correctamente una ejecución `FAILED` devuelve 0. El estado de negocio no se confunde con el éxito de la consulta. UUID inexistente o de otro repositorio devuelve `RUN_NOT_FOUND`, código 2. Un UUID mal formado es error de uso.

## Base de datos y migración

`init` prepara `.odd-lab/state/journal.sqlite`. Al repetirlo sobre un proyecto de E-02, conserva descriptor/política y crea el registro que falta. La configuración YAML sigue en v1; el formato del registro tiene su propia versión.

- SQLite JDBC `org.xerial:sqlite-jdbc:3.53.4.0`, fijado explícitamente. JDBC queda en infraestructura; dominio y casos de uso no dependen de él.
- Migración inicial versionada en `src/main/resources/db/migration/V001__run_journal.sql`. Su contenido SHA-256 queda registrado en la base.
- `PRAGMA user_version=1`, `application_id` propio y metadatos con UUID del repositorio. Se comprueban antes de leer o escribir registros.
- Esquema con tablas `journal_metadata`, `runs`, `run_entries`, `run_events`; claves externas, claves únicas por ejecución/versión y restricciones sobre tipos/estados.
- Creación del esquema y metadatos en una transacción. Una base v0 sin tablas puede inicializarse solo desde `init`; una base ajena, corrupta, de versión desconocida o de otro ámbito se conserva y falla explícitamente.
- Journal SQLite de rollback por defecto, sin habilitar WAL ni usar `immutable=1`. Escritura con `synchronous=FULL`; espera de bloqueos de 5 segundos. No se instala un servicio ni se mantiene un pool de conexiones.

Las consultas y `doctor` abren conexiones `mode=ro`, no migran ni crean una base ausente. La URI SQLite codifica rutas con espacios o `#`. Las escrituras ordinarias usan `mode=rw`, sin recrear silenciosamente un registro desaparecido; solo `init` puede usar creación.

La ruta de estado, la base y sus posibles auxiliares `-journal`, `-wal`, `-shm` rechazan enlaces simbólicos y colisiones de tipos. Se mantienen los límites de E-02 frente a sustituciones hostiles concurrentes. No es un sandbox.

La biblioteca SQLite usa acceso nativo. El JAR incluye `Enable-Native-Access: ALL-UNNAMED` y las JVM de pruebas habilitan ese acceso, conforme al mecanismo de Java para bibliotecas nativas. No se cambia la configuración global del JDK.

## Datos registrados

Cada ejecución conserva UUID propio y del repositorio, actor, petición original literal y su SHA-256, revisión base Git indicada por el llamador, instantánea de política, estado, versión y fechas. E-03 no calcula por sí sola la revisión base ni autoriza una petición: el futuro caso de uso `run` aportará esos datos después de comprobarlos.

El puerto `RunJournal` permite crear el registro `CREATED` en versión 0, consultar, registrar cambios de estado y añadir entradas. Cada mutación incrementa versión y registra un evento con UUID, actor, fecha, tipo, estado anterior/nuevo y detalle en la misma transacción. Si falla la escritura del evento, se revierten también la ejecución, el cambio de estado o la entrada correspondiente.

Las actualizaciones exigen `expectedVersion`. Una versión antigua se rechaza sin cambios; el `UPDATE` también comprueba versión y ámbito. Este control evita aceptar una escritura obsoleta; no equivale a propiedad exclusiva de un coordinador ni resuelve procesos abandonados (E-05).

`RunEntry` conserva identidad, tipo, versión de formato, título y datos de texto con claves. Tipos iniciales: `REQUIREMENT`, `TASK`, `DECISION`, `EVIDENCE`. Las entradas son inmutables en este puerto; revisiones adicionales deben registrarse con identidad nueva. En E-03 son metadatos duraderos, sin planificador de tareas ni resolución de decisiones.

Una referencia de evidencia exige ubicación, revisión y SHA-256 con formato válido. Registrarla no abre el artefacto ni demuestra que exista, coincida con el hash o cumpla aceptación. La verificación de artefactos y vigencia se integra con E-04 y las entregas posteriores.

## Estados y límites

Se definen los estados del contrato y se validan transiciones internas. El recorrido básico que puede registrar E-03 es `CREATED → EXPLORING → READY → RUNNING → VERIFYING`; se admiten ramas de fallo, espera, solicitud de control y reconciliación según el código del dominio.

El puerto rechaza llegar a `COMPLETED`, `PAUSED` o `CANCELLED` con `STATE_PROOF_REQUIRED`: todavía faltan los casos de uso que acrediten aceptación o detención efectiva. Los estados terminales tampoco se reabren ni reciben nuevas entradas. No existe un comando CLI para cambiar estado en E-03.

`inspect` valida SHA-256 de la petición, tipos de datos y coherencia del historial: primera creación, versiones consecutivas, continuidad de estados y coincidencia del último evento con la ejecución. `status` aplica las mismas comprobaciones al registro consultado. No hay mecanismo criptográfico contra modificación deliberada de la base y sus hashes.

El registro no incluye todavía intentos/runtime, revisiones completas de requisitos, dependencias de tareas, consumo, lecciones, búsqueda semántica ni vistas Markdown regenerables. Esas capacidades no se sustituyen por campos narrativos que se declaren ejecutados. El registro estructurado es la única fuente de estado.

## Salida

Se conserva el sobre JSON v1 de E-02. En consulta concreta se añaden `runId` y `state` al nivel superior y dentro de `result`.

- Lista: `result.runs` con resúmenes: identidad de ejecución/repositorio, estado, versión, actor, base Git y fechas.
- Estado concreto: esos mismos campos en `result`.
- Inspección: resumen más `request`, `requestSha256`, `policy`, `requirements`, `tasks`, `decisions`, `evidence`, `events`.

Petición y política no se vuelcan en los resúmenes; se consultan explícitamente con `inspect`. La salida humana muestra el registro sin banners. Fallos JSON mantienen un único objeto final en stdout y sin trazas internas.

| Código | Salida | Significado |
| --- | --- | --- |
| `STORAGE_NOT_INITIALIZED` | 2 | Falta base/esquema; ejecutar `init` expresamente |
| `STORAGE_VERSION_UNSUPPORTED`, `STORAGE_FOREIGN_DATABASE` | 2 | Formato desconocido o base ajena; sin sobrescritura |
| `STORAGE_SCOPE_MISMATCH` | 2 | La base pertenece a otro UUID de repositorio |
| `UNSAFE_STORAGE_PATH` | 2 | Enlace simbólico o tipo de ruta incompatible |
| `RUN_NOT_FOUND` | 2 | Ejecución ausente en ese ámbito |
| `RUN_VERSION_CONFLICT`, `INVALID_STATE_TRANSITION`, `STATE_PROOF_REQUIRED`, `RUN_TERMINAL`, `INVALID_INITIAL_RUN`, `INVALID_EVENT` | 2 | Precondición de escritura interna inválida |
| `STORAGE_CORRUPT`, `STORAGE_FAILURE`, `STORAGE_BUSY` | 1 | Inconsistencia, fallo operativo o bloqueo; no afirmar éxito |

Se conservan errores de uso/proyecto de E-02. `doctor` presenta un fallo del registro como comprobación `STORAGE: ERROR` con su código original y devuelve `DIAGNOSTIC_FAILED`/2, sin reparar nada.

## Diagnóstico y ámbitos

`doctor` ya comprueba base v1, metadatos de ámbito/migración, `quick_check`, claves externas y lectura coherente de las ejecuciones. `RUNTIME` sigue en `NOT_CONFIGURED`. Una base correcta no demuestra que pueda escribirse después ni que exista inferencia.

Todas las operaciones pasan por el UUID del descriptor y lo cotejan con la base; las consultas SQL también filtran por repositorio. Copiar una base a un descriptor con UUID distinto no permite recuperar sus datos. Copiar descriptor y base conserva deliberadamente la identidad lógica; no se implementa detección de clones ni autorización empresarial. No se ofrece colaboración compartiendo SQLite entre equipos.

Referencias: [SQLite JDBC](https://github.com/xerial/sqlite-jdbc), [transacciones SQLite](https://www.sqlite.org/lang_transaction.html), [URI y modo de solo lectura](https://www.sqlite.org/uri.html), [PRAGMA SQLite](https://www.sqlite.org/pragma.html).
