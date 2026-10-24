# Validación de E-05

24 de octubre de 2026, Europe/Madrid. Implementación sobre base Git `1f5cf64` y cambios de esta entrega. Sin commit ni push automáticos. E-06 y E-07 pendientes. Contrato: [control y recuperación](control-y-recuperacion.md).

## Construcción final

```sh
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -ntp clean verify
```

**BUILD SUCCESS**, 148 pruebas, 0 fallos, 0 errores y 0 omitidas. Finalizada a las 01:18:54 CEST, duración 2 min 30 s. Temurin 25.0.2+10, Maven Wrapper 3.3.4/Maven 3.9.16, Boot 4.1.2, Picocli 4.7.7 y SQLite JDBC 3.53.4.0. Sin dependencias nuevas.

Las primeras pruebas de observación de descendientes dentro del sandbox fallaron con `Operation not permitted (sysctl failed)`. No se interpretó como detención. La validación final se ejecutó fuera del sandbox, con permiso para observar y detener únicamente los procesos JVM sintéticos del ensayo. No se modificaron preferencias globales ni instalaciones.

| Suite | Pruebas | Alcance |
| --- | --- | --- |
| `OddLabApplicationTest` | 3 | Composición/cierre |
| `YamlProjectConfigurationTest` | 18 | Descriptor y política |
| `SqliteRunJournalTest` | 28 | Regresión y atomicidad de confirmación de detención |
| `LocalExecutionFilesTest` | 9 | Huella, rutas y artefactos |
| `SimulationCoordinatorTest` | 20 | Regresión E-04; recuperación de resultado/informe, control sin propietario y pausa segura |
| `LocalRunControlsTest` | 5 | Exclusión, liberación, ámbito, rutas, buzón corrupto y lectura sin crear estado |
| `LocalProcessRuntimeTest` | 3 | Padre/hijo reales, cancelación y rechazo de PID con inicio distinto |
| `PackagedCliIT` | 10 | Raíz CLI y JAR |
| `ProjectCommandsIT` | 26 | Inicialización/diagnóstico y conservación |
| `JournalCommandsIT` | 9 | Consultas reales y solo lectura |
| `SimulationCommandsIT` | 11 | Regresión de escenarios y evidencia E-04 |
| `ControlCommandsIT` | 6 | JAR real, dos instancias, control, señal, caída y recuperación |

Total: 86 pruebas de núcleo y 62 de integración. Informes regenerables en `target/surefire-reports/` y `target/failsafe-reports/`.

## Evidencia por escenario

| Criterio | Ensayo y resultado |
| --- | --- |
| N-03: resultado persistido | Trabajador padre/hijo real termina. Un trigger aborta el evento COMPLETED después de guardar resultado y publicar informe. El primer JAR termina con fallo; estado permanece VERIFYING. Tras retirar el trigger, otro JAR completa con evidencia VALID y un solo ATTEMPT_START: no repite el trabajador. Se prueba el límite transaccional mediante fallo inducido, no una caída física del disco. |
| N-03: informe huérfano/pausa | Adaptador de pruebas interrumpe tras publicar informe. Se guarda una solicitud PAUSE, se confirma la pausa y se reanuda verificando el mismo recibo/informe, sin otro intento. |
| N-04: propietario muerto | Se mata con cierre forzado el JAR propietario tras registrar padre/hijo. Se comprueba que quedan procesos vivos. `resume` adquiere el bloqueo liberado, detiene los huérfanos y devuelve PAUSED/4, conservando un solo inicio. Solo otra invocación inicia el segundo intento y completa. |
| N-04: efecto desconocido | Intención sin resultado ni identidades suficientes no se repite; conserva RECONCILIATION_REQUIRED/5. |
| N-06: exclusión real | Mientras el JAR mantiene padre/hijo activos, otra instancia `resume` recibe RUN_OWNED/5 y no añade intentos. También se comprueba exclusión entre adaptadores en la misma JVM, liberación y conservación del archivo de bloqueo. |
| N-07: cancelación | Desde otro JAR, `cancel` devuelve 0 con CANCELLED; propietario devuelve 4. Padre e hijo ya no están vivos; exactamente un intento. |
| N-07: pausa | `pause` devuelve 0 y propietario 4/PAUSED, con árbol detenido. `resume` crea ordinal nuevo dentro del presupuesto capturado y termina VALID. |
| Señal | SIGTERM al JAR propietario solicita pausa mediante hooks. El registro queda PAUSED y padre/hijo detenidos; no se añade intento. No se ha probado corte de alimentación. |
| PID reutilizado | Identidad con PID de la JVM de pruebas e inicio distinto no recibe señal ni se declara detenida. La JVM de pruebas sigue viva. |
| Sin propietario | Solicitud guardada devuelve CONTROL_PENDING/5; inspect muestra PENDING. Resume la reconcilia, devuelve CANCELLED/4 y la consulta muestra CONFIRMED; decisión pendiente cerrada, sin invocar runtime. |
| Evidencia alterada | Modificar el informe de detención impide reanudar: reconciliación/5, sin llamada al runtime. |
| Atomicidad de cierre | Trigger rechaza evento CANCELLED. Prueba, estado y versión se revierten; al retirarlo, la misma operación confirma el cierre. Ámbito/acción incompatibles se rechazan. |
| Consultas | Regresión completa de consultas sin escrituras, incluido buzón ausente. JSON conserva un objeto final y stderr vacío en los comandos ensayados. |

Los procesos de prueba solo ejecutan un trabajador Java fijo que espera y mantiene un hijo. No reciben petición, repositorio o comandos del usuario. Esta evidencia valida control y recuperación de esos procesos; no demuestra repetición segura ni reconciliación de efectos de un proveedor arbitrario.

## Artefactos e integridad

JAR final: `target/odd-lab-0.1.0-SNAPSHOT.jar`.

```text
SHA-256 JAR: f122ec79d2645f2d74ce69b7a8c9c0b8340ecde64ab2e2da8da7702a223ac2a6
SHA-256 V001: 51b14d55f6b6959d2a7d66bf5b82e9f2abc13d4b13b480f3fedc6eda25be6c00
```

V001 comparada byte a byte con HEAD: sin cambios. No hay migración nueva. El hash del JAR identifica esta construcción y no promete reproducibilidad binaria. Dominio/aplicación sin imports de Spring, Picocli, JDBC o Jackson. `git diff --check` sin errores.

Las pruebas utilizan repositorios temporales y limpian los procesos que registran. El repositorio real de odd-lab sigue sin `.odd-lab.yaml` ni directorio operativo `.odd-lab/`. No se han enviado datos a un proveedor, iniciado Codex, instalado un daemon ni ejecutado el piloto CSV.

## Comprobación adicional de comandos por el agente

24 de octubre de 2026. Secuencia completa ejecutada sobre un repositorio temporal, con el JAR disponible en ese momento (SHA-256 `ff2624d73b988f0239d7b6aeb88204846257e25677d6c0f2b798188db3f0612e`, distinto del artefacto histórico de la construcción anterior). Se usaron esperas sintéticas de 1/10 segundos para acortar el ensayo.

Superados: init, doctor, run process, status, inspect, resume de completado sin añadir intento, exclusión RUN_OWNED/5, pause/0 y propietario/4, resume hasta COMPLETED/VALID con segundo intento, cancel/0 y propietario/4, SIGKILL con huérfanos vivos, primera recuperación PAUSED/4 sin repetir y segunda reanudación COMPLETED/VALID, SIGTERM con PAUSED y árbol detenido, solicitudes sin propietario CONTROL_PENDING/5 y resolución por resume. JSON no aprobó la decisión pendiente. Al terminar se comprobó mediante `ps` que no quedaba ninguno de los procesos padre/hijo registrados.

Evidencia local: `/private/tmp/odd-e05-manual-check-2zeh7c67/results.json`. No forma parte de los fixtures necesarios para construir.

Se comprobó además el ejemplo zsh con la función `odd` en segundo plano. **Corrección de las instrucciones de conversación:** `jobs -l` muestra el PID del shell envolvente, que puede diferir del coordinador Java (observados 66756 y 66758). Para el ensayo SIGKILL se debe obtener el PID del propietario desde `.odd-lab/state/control/UUID/owner.lock`, comprobando que se trata de la ejecución temporal activa. Esa secuencia corregida fue ejecutada y devolvió PAUSED/4, sin procesos padre/hijo restantes. No hubo cambios de código.

## Límites de aceptación

N-03/N-04/N-06/N-07 ejercitados en macOS arm64 con SQLite, sistema de archivos y procesos JVM reales del perfil sintético. No se declara soporte Windows, sistemas de archivos remotos, aislamiento de procesos arbitrarios ni garantías del runtime futuro. Las identidades incompletas o efectos desconocidos requieren reconciliación, sin ejecución exactamente una vez por suposición.

Los temporales, solicitudes y artefactos huérfanos se conservan; no se importan como instrucciones ni se borran por inferencia. Un informe huérfano solo se reutiliza si coincide con el contenido determinista esperado y su hash. No se han probado fallos físicos de almacenamiento ni manipulación hostil concurrente de rutas/bloqueos.

La validación manual del usuario de esta entrega queda pendiente. Siguiente entrega: E-06, adaptador autorizado del runtime real, protocolo/capacidades, políticas y aislamiento con datos sintéticos.
