# Ejecución simulada — E-04

24 de octubre de 2026. Implementación local de `run`, `resume`, evidencia y decisiones. Sin inferencia, proveedores externos, cambios de código ni control de procesos reales. Amplía [registro E-03](registro-y-consultas.md).

## Entrada y significado del resultado

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar run --repo /ruta/al/repositorio --request-file examples/requests/simulation.md --runtime simulated --json
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar inspect --repo /ruta/al/repositorio --run UUID --json
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar resume --repo /ruta/al/repositorio --run UUID
```

Ejecutar desde la raíz de odd-lab o usar rutas absolutas al JAR y petición. El repositorio destino debe estar preparado con `init`. `--repo`, `--request-file` y `--runtime simulated` son obligatorios para `run`. No hay selección implícita de un proveedor. `resume` recibe `--repo` y `--run`, y usa el runtime registrado.

El archivo de petición debe ser UTF-8 no vacío, hasta 1 MiB; BOM, acentos y saltos se conservan literalmente. Puede estar fuera del repositorio si se señala explícitamente. Su contenido no se interpreta como código, shell o ampliación de permisos. Se guarda en SQLite y en una copia con SHA-256 dentro del área de artefactos de la ejecución.

**`COMPLETED` significa que se ha verificado el circuito sintético, no que se haya implementado la petición original.** La CLI devuelve `simulated: true` y un mensaje explícito. La aceptación consiste en un recibo JSON que coincide con petición, revisión e intento, más un informe local verificado. No se simula haber modificado código o ejecutado tests de un producto.

## Escenarios reproducibles

`--scenario` admite los siguientes valores, sin distinguir mayúsculas. Por defecto, `success`.

| Escenario | Comportamiento | Resultado esperado |
| --- | --- | --- |
| `success` | Recibo correcto | `COMPLETED`, código 0 |
| `retry` | Primer recibo con hash incorrecto; siguiente correcto | `COMPLETED` si queda presupuesto; identidades de intento distintas |
| `failure` | Fallo sintético en cada intento | `FAILED`, código 1 al agotar presupuesto |
| `unverified` | El runtime afirma éxito sin artefacto | `FAILED`, código 1; nunca se acepta la afirmación |
| `decision` | Registra una decisión de negocio antes de ejecutar | `WAITING_INPUT`, código 3, sin intento consumido |
| `permission` | Solicitud sintética de `PUBLISH`, fuera del encargo | Denegada y registrada; `FAILED`, código 1 |

La simulación es directa, una tarea y un principal, sin subagentes. La decisión de no delegar queda registrada. Cada intento consume el mismo presupuesto de ejecución: uno inicial más `maxCorrectionsPerTask` de la política capturada, entre uno y tres en total. No se reinicia ese contador al consultar o reanudar. Se registran límites y ordinales; no se inventan tokens ni costes (`NO_DISPONIBLE`).

El puerto del runtime anuncia capacidades; el coordinador rechaza perfiles no simulados o con herramientas, procesos externos o red antes de enviarles contexto. El único adaptador seleccionable desde la CLI es el simulador compilado, que devuelve datos en memoria y no ejecuta herramientas. El coordinador publica los artefactos. Esto no demuestra el sandbox de un proveedor futuro.

## Registro y atomicidad

Se conservan las tablas y migración v1 de E-03, sin reescribir la migración aplicada. Los tipos existentes permiten registrar configuración/definición de tarea, inicios/resultados de intento, verificaciones y decisiones como entradas inmutables versionadas. Los nuevos campos tienen significado de E-04 dentro de `RunEntry.data`; un registro de E-03 sin configuración simulada no se reanuda automáticamente.

La configuración capturada incluye runtime, escenario, identidad de tarea, presupuesto y copia de petición. Cada intento tiene UUID nuevo, ordinal, tarea, revisión y hash de petición; se registra su intención antes de invocar el runtime y su resultado después. El éxito del runtime pasa a `VERIFYING` y no equivale a aceptación.

`appendAndTransition` guarda entrada y cambio de estado con sus dos eventos en una transacción y comprueba versión esperada. Se usa para decisiones pendientes/respuestas y para inicio/resultado de intentos. Una respuesta obsoleta falla antes de ejecutar trabajo. La escritura que pasa `READY` a `RUNNING` también comprueba versión antes de invocar el simulador.

`completeSimulation` es una operación separada: exige `VERIFYING`, requisito/tarea del circuito y evidencia verificada ligada a petición y revisión. El coordinador comprueba los archivos antes de llamarla; el adaptador registra evidencia y finalización en una transacción. Las transiciones genéricas siguen sin permitir afirmar éxito ni detención confirmada.

No existe una transacción única entre SQLite y el sistema de archivos. Los archivos se escriben en temporales, se cierran, se calculan sus hashes y se publican sin sobrescribir artefactos existentes. Solo después se referencian en el registro. Una interrupción puede dejar un temporal o archivo huérfano: no cuenta como evidencia aceptada ni se elimina por inferencia. La recuperación general queda en E-05.

## Decisiones y reanudación

`run --scenario decision` guarda una pregunta, acción, UUID, ámbito, revisión y versión esperada junto con `WAITING_INPUT`. No hay un runtime activo en ese punto. El primer comando termina con código 3; la respuesta se recoge mediante `resume` interactivo.

- Con `--json` o sin consola interactiva, `resume` conserva la decisión y devuelve 3. No pregunta ni autoriza automáticamente, aunque stdin contenga texto.
- En terminal interactivo, admite `s`/`sí` o `n`/`no`. Respuesta vacía, inválida o fin de entrada mantiene el pendiente.
- Una aprobación válida queda vinculada a la decisión y revisión; se guarda junto con `READY`. La denegación queda registrada junto con `FAILED`, código 1. Las entradas originales no se modifican.
- La revisión se comprueba antes de preguntar y después de la respuesta. Si cambió código o política, no se aplica la respuesta antigua.
- No existen `--yes`, `--answer` ni una opción de aceptar todo. Una petición de permisos fuera de este encargo se deniega, incluso si la política reserva esa acción al usuario; el ensayo no la ofrece como ampliación autorizable.

`resume` admite `WAITING_INPUT`, `READY` seguro o `COMPLETED` para volver a comprobar evidencia. En `COMPLETED` no invoca otra vez el runtime y puede regenerar la vista Markdown. `FAILED` no se repite automáticamente.

En estados activos, parcialmente preparados o de cierre desconocido, devuelve error/precondición de reconciliación sin lanzar otro intento. Si el runtime no confirma detención, se registra `RECONCILIATION_REQUIRED`, código 5: nunca `WAITING_INPUT`, `PAUSED`, `CANCELLED` o éxito por suposición. E-04 no resuelve procesos abandonados ni implementa pausa/cancelación.

## Revisión, artefactos y consultas

La revisión es `sha256:` sobre HEAD —o rama sin commits—, rutas y contenidos de archivos versionados/no ignorados, enlaces como texto y marca de ejecutable. El descriptor `.odd-lab.yaml` se incluye siempre, aunque esté ignorado. Datos operativos de `.odd-lab/` quedan fuera. No se sigue un enlace ancestral que sustituya un directorio de archivos versionados; submódulos u otras rutas no regulares se rechazan en esta entrega.

Las consultas Git desactivan fsmonitor, caché de no versionados y bloqueos opcionales; limpian redirecciones `GIT_*` heredadas. Los temporales de captura Git están fuera del repositorio. La huella permite detectar cambios examinados, sin convertir un worktree en sandbox ni garantizar una instantánea atómica ante cambios hostiles simultáneos.

Artefactos: `.odd-lab/artifacts/UUID/request.txt`, recibos `attempt-UUID.json` e informes `verification-UUID.txt`. Cada archivo y salida del simulador tiene límite de 1 MiB. Se rechazan escapes de ruta, enlaces simbólicos en las áreas de escritura y colisiones; un archivo previo no se reemplaza. Las comprobaciones locales no constituyen aislamiento de sistema operativo para un agente externo.

La evidencia aceptada referencia recibo, revisión, hash de petición, tarea, intento e informe de verificación. `status` e `inspect` la comprueban en solo lectura:

| `verificationStatus` | Significado |
| --- | --- |
| `VALID` | Revisión actual, copia de petición, recibo e informe coinciden |
| `STALE` | Cambió la revisión o el descriptor; evidencia histórica no vigente |
| `INVALID` | Artefacto ausente/alterado, referencia insegura o evidencia incoherente |
| `NOT_VERIFIED` | No hay aceptación de simulación registrada |

El estado almacenado se conserva: un `COMPLETED` histórico puede mostrar `STALE` o `INVALID`. El éxito de la consulta sigue siendo código 0; no afirma aceptación vigente. `resume` de una ejecución completada con evidencia inválida devuelve 1, y revisión cambiada devuelve 5, sin repetir el runtime.

`inspect` mantiene los registros de requisito/tarea/decisión/evidencia y eventos, incluyendo entradas `ATTEMPT_START`, `ATTEMPT_RESULT` y `VERIFICATION`. `pendingDecision` aparece tanto en el sobre JSON como en el resultado cuando aplica. `run`/`resume` añaden `simulated`, `verificationStatus`, `viewStatus` y mensaje, sin trazas internas.

La vista `.odd-lab/views/UUID.md` es derivada y lleva versión del registro. `run`/`resume` la regeneran desde SQLite; no se usa para recuperar estado ni se importa como instrucciones. Si no se puede escribir, se informa `viewStatus=UNAVAILABLE` conservando el registro. Las consultas no escriben vistas. La disponibilidad de la simulación se muestra en `doctor`; los proveedores reales siguen sin configurar.

## Errores y alcance pendiente

Se mantienen los códigos de salida existentes: 0 simulación verificada, 1 fallo/denegación/presupuesto agotado, 2 uso o precondición, 3 decisión pendiente segura, 5 reconciliación necesaria. Códigos adicionales: `REQUEST_INVALID`, `RUNTIME_NOT_ALLOWED`, `RUN_NOT_RESUMABLE`, `RUN_CONFIGURATION_INVALID`, `INPUT_REQUIRED`, `DECISION_DENIED`, `DECISION_STALE`, `DECISION_INCONSISTENT`, `PERMISSION_SCOPE_DENIED`, `SIMULATION_FAILED`, `VERIFICATION_FAILED`, `REVISION_CHANGED`, `REQUEST_EVIDENCE_INVALID`, `RESUME_NOT_SAFE`, `RUNTIME_STOP_UNKNOWN`, `VERIFICATION_CHANGED`, `VERIFICATION_INVALID`, `UNSAFE_ARTIFACT_PATH`, `ARTIFACT_EXISTS`, `ARTIFACT_TOO_LARGE`, `ARTIFACT_IO_ERROR`, `REVISION_UNAVAILABLE`, `UNSUPPORTED_REVISION_PATH`. La salida JSON sigue siendo un objeto final por comando.

Persistencia de intención y control optimista no equivalen a propiedad de un coordinador con recuperación de procesos. E-05 conserva bloqueos/propiedad, pausa, cancelación y recuperación tras caída; E-06 conserva runtime real, autenticación y pruebas de aislamiento. La simulación no demuestra proveedores ni rendimiento de agentes. No se marca el piloto CSV como ejecutado.
