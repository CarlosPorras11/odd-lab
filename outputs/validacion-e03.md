# Validación de E-03

24 de octubre de 2026, Europe/Madrid. E-03 implementada y validada en macOS arm64, sobre base Git `dcaebd7` y los cambios de esta entrega. Sin commit ni push automáticos. E-04 a E-07 pendientes.

Contrato: [registro transaccional y consultas](registro-y-consultas.md). Los informes E-01/E-02 son históricos; esta validación incluye su regresión y documenta el artefacto actual.

## Construcción

```sh
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -ntp clean verify
```

Resultado final: **BUILD SUCCESS**, 91 pruebas, 0 fallos, 0 errores, 0 omitidas. JDK Temurin 25.0.2+10, Maven Wrapper 3.3.4/Maven 3.9.16, Boot 4.1.2, Picocli 4.7.7 y SQLite JDBC 3.53.4.0.

| Suite | Pruebas | Alcance |
| --- | --- | --- |
| `OddLabApplicationTest` | 3 | Composición/cierre y logs |
| `YamlProjectConfigurationTest` | 18 | Regresión descriptor y política |
| `SqliteRunJournalTest` | 25 | SQLite real, transacciones, versiones, ámbito e integridad |
| `PackagedCliIT` | 10 | Regresión de raíz CLI y artefacto |
| `ProjectCommandsIT` | 26 | Regresión init/doctor, worktrees, JSON y conservación de archivos |
| `JournalCommandsIT` | 9 | JAR real: consultas vacías/pobladas, errores y solo lectura |

## Evidencia por criterio

| Criterio | Comprobación | Resultado |
| --- | --- | --- |
| E03-A | Inicialización v1 e idempotencia | Esquema, metadatos y ámbito creados; repetir init no cambia bytes ni mtime de la base |
| E03-B | Actualización desde E-02 | Consultas y doctor rechazan base ausente sin crearla; init la crea preservando descriptor |
| E03-C | Estado/evento atómicos | Trigger sintético aborta INSERT de evento; creación de ejecución y transición de estado hacen rollback completo |
| E03-D | Entrada/evento atómicos | Fallo del evento revierte entrada y versión; entrada duplicada no añade eventos ni avanza versión |
| E03-E | Control optimista | Versión obsoleta y salto de estado inválido rechazados; ejecución e historial intactos |
| E03-F | Durabilidad sin sesión de runtime | Nueva instancia del adaptador recupera petición literal, hash, política, revisión, entradas y eventos |
| E03-G | Ámbitos de repositorio | UUID ajeno rechazado; consultas SQL acotadas; copia de base a descriptor distinto rechazada sin filtrar contenido |
| E03-H | Formato e integridad | Versiones futuras, base ajena/corrupta, cambio de petición, fechas/políticas inválidas, entrada ausente e historial incompleto detectados sin reparación implícita |
| E03-I | Seguridad de rutas | Base y auxiliares con enlaces simbólicos rechazados; destino externo intacto |
| E03-J | Consultas reales | Lista vacía, status concreto y detalle de los cuatro tipos de entrada; un estado FAILED devuelve consulta correcta/0 |
| E03-K | Solo lectura | Snapshots de archivos/directorios e índice Git, hashes y mtime iguales antes/después de status, inspect y doctor |
| E03-L | Salida y precondiciones | JSON único, runId/state en consulta concreta, UUID inválido/ausente y ejecución desconocida con código 2 |
| E03-M | Límites honestos | COMPLETED/PAUSED/CANCELLED requieren casos de uso con prueba; runtime sigue NOT_CONFIGURED |

Las pruebas de rollback usan una base SQLite real y un trigger que hace fallar el evento después de intentar el cambio correspondiente. No se infiere atomicidad de una simulación de repositorio.

Las consultas pobladas se ensayan con registros sintéticos creados a través de `RunJournal` desde el código de pruebas. No existe un comando público de creación ni un runtime simulado conectado en E-03. Las referencias de evidencia se conservan; su artefacto no se verifica todavía.

## Artefactos

JAR probado: `target/odd-lab-0.1.0-SNAPSHOT.jar`.

```text
SHA-256 JAR: 27633a613e0a904828d5058085986d9df498d3d3cc1aec929d63ae1767d38cb9
SHA-256 migración V001: 51b14d55f6b6959d2a7d66bf5b82e9f2abc13d4b13b480f3fedc6eda25be6c00
```

El primer hash identifica esta construcción, sin prometer reproducibilidad binaria. El segundo se registra en la base para identificar la migración aplicada. Informes regenerables: `target/surefire-reports/` y `target/failsafe-reports/`.

El JAR conserva el manifiesto de acceso nativo necesario para SQLite; los procesos CLI del ensayo no emiten advertencias nativas ni trazas por stderr en modo JSON.

## Límites de aceptación

Cobertura parcial de N-05: datos/entradas/eventos se recuperan con otra instancia del adaptador sin estado de runtime; no se ha probado otro adaptador de almacenamiento o proveedor. Cobertura parcial de N-09: aislamiento lógico por UUID y consultas; no existe todavía recuperación de conocimiento entre ámbitos ni autenticación empresarial. Ninguno se declara íntegramente completado.

Rollback de transacciones no demuestra recuperación de procesos, cancelación, propiedad de ejecución ni ausencia de duplicación de efectos externos. E-05 conserva esos objetivos. Tampoco se han implementado vistas Markdown, búsqueda semántica o validación completa de evidencias. Los criterios restantes del núcleo y el piloto siguen pendientes.

No se ha ejecutado Windows. No se ha iniciado un proveedor, instalado un daemon ni inicializado el repositorio real de odd-lab. SQLite y todos los registros sintéticos de pruebas se han creado en carpetas temporales.

Siguiente entrega: E-04, `run`/`resume` con runtime simulado, evidencia y decisiones pendientes.
