# Validación de E-02

23 de octubre de 2026, Europe/Madrid. E-02 implementada y validada en macOS arm64 sobre base Git `ee07fbd` más los cambios de esta entrega. Sin commit ni push automáticos. E-03 a E-07 pendientes.

Contrato implementado: [configuración y diagnóstico](configuracion-y-diagnostico.md). E-01 continúa cubierta por regresión; su informe histórico conserva el hash de su artefacto anterior.

## Ejecución

```sh
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -ntp clean verify
```

Resultado final: **BUILD SUCCESS**, 57 pruebas, 0 fallos, 0 errores y 0 omitidas.

| Suite | Pruebas | Comprobación |
| --- | --- | --- |
| `OddLabApplicationTest` | 3 | Composición, cierre y salida de logs por stderr |
| `YamlProjectConfigurationTest` | 18 | Identidad/política, versiones, tipos, límites, YAML inválido, etiquetas y campos desconocidos |
| `PackagedCliIT` | 10 | Regresión E-01: ayuda, versión, uso inválido, cierre y ausencia de estado/web |
| `ProjectCommandsIT` | 26 | JAR real sobre repositorios, worktrees y archivos temporales |

Se usaron Temurin 25.0.2+10, Maven 3.9.16 mediante Wrapper 3.3.4, Boot 4.1.2 y Picocli 4.7.7. SnakeYAML 2.6 y Jackson Databind 3.1.7, gestionados por Boot. Ninguna conexión a un proveedor ni inferencia durante estas pruebas.

## Criterios de aceptación

| Criterio | Escenario | Evidencia y resultado |
| --- | --- | --- |
| E02-A | Inicializar Git existente, incluso sin commits | Descriptor con UUID/política, cuatro directorios y exclusiones creados; `initializesIdempotentlyPreservingGitignoreAndExistingFiles` |
| E02-B | Conservar archivos y repetir inicialización | Prefijo original de `.gitignore` con CRLF/sin salto final intacto; archivo propio intacto; segunda ejecución sin cambios de contenido/mtime; mismo test |
| E02-C | Conservar política modificada y comentarios | Proveedor configurado y límite menor preservados, sin reescribir descriptor; `preservesValidUserConfigurationAndComments` |
| E02-D | Doctor de solo lectura | Snapshots con hashes/mtime, incluido `.git`, iguales antes/después; `doctorIsReadOnlyAndDistinguishesPendingCapabilities` |
| E02-E | Detectar y reparar preparación parcial | Doctor informa de directorio ausente/exclusiones anuladas sin repararlos; init los repara conservando descriptor; `doctorReportsMissingDirectoryWithoutRepairingIt` y `repairsOverriddenIgnoreRulesWithoutReplacingDescriptor` |
| E02-F | Configuración inválida o versión desconocida | Error 2 y archivos intactos; tests YAML y `preservesInvalidExistingDescriptorAndDoesNotPrepareDirectories` / `rejectsUnknownVersionOfOtherwiseValidConfiguration` |
| E02-G | Rutas ambiguas o no aptas | Rechazo de carpeta ordinaria, ruta ausente, bare y subdirectorio; sin crear estado; tests de repositorio inválido y raíz explícita |
| E02-H | Enlaces, colisiones y datos ya versionados | Rechazo sin modificar destino externo, archivos ni índice; tests de enlaces/colisiones/operativos versionados |
| E02-I | Worktree y rutas con espacios | Init y doctor correctos con `.git` como archivo; repositorio origen sin descriptor; `supportsGitWorktreeWithGitfile` |
| E02-J | JSON y códigos de salida | Objeto único con schema, command, result y error; argumentos/repo obligatorios devuelven 2; éxito 0; pruebas sobre ambos comandos |
| E02-K | Capacidades pendientes honestas | Doctor muestra almacenamiento `NOT_IMPLEMENTED`, runtime `NOT_CONFIGURED`; no los declara verificados |
| E02-L | Evitar redirección de Git por entorno heredado | `GIT_DIR` ajeno ignorado; solo se inicializa la raíz explícita; `ignoresInheritedGitDirectoryOverrides` |

Las pruebas Git crean repositorios y, para el caso worktree, un commit sintético únicamente dentro de directorios temporales. El producto `init` no hace commits; se comprueba que el repositorio inicializado sigue sin historial. Los datos operativos de los cuatro directorios quedan fuera de `git status`.

La política se prueba como configuración local, no como aislamiento de procesos. No se marcan N-01 a N-12 ni el piloto como completados.

## Artefacto y evidencia regenerable

JAR: `target/odd-lab-0.1.0-SNAPSHOT.jar`. SHA-256 del artefacto final probado:

```text
13be6776c01ac79adc52d46b737c31711e8a088716feac129cd2534dc9ad4c17
```

Informes: `target/surefire-reports/` y `target/failsafe-reports/`. Artefacto e informes excluidos de Git, regenerables con `clean verify`. El hash identifica esta construcción; no se afirma reproducibilidad binaria.

## Límites

No se ha ejecutado Windows. No se ha creado una base de datos ni probado memoria, ejecución de agentes, autenticación, cancelación o sandbox de un proveedor. `doctor` comprueba preparación local; no fuerza una escritura ni acredita permisos futuros de almacenamiento.

`init` no es una transacción de varios archivos ni ofrece bloqueo entre inicializadores. Los controles de enlaces son comprobaciones locales, no garantía contra sustituciones hostiles concurrentes. Se conservan fallos y configuración inválida para revisión, sin inventar una inicialización correcta.

No se ha ejecutado `init` sobre el repositorio real de odd-lab; los ensayos han usado fixtures temporales. Siguiente entrega: E-03, registro transaccional, estados y comandos consultivos.
