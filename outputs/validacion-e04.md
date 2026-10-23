# Validación de E-04

24 de octubre de 2026, Europe/Madrid. E-04 implementada y validada en macOS arm64 sobre base Git `7f7a718` y cambios de esta entrega. Sin commit ni push automáticos. E-05 a E-07 pendientes.

Contrato: [ejecución simulada](ejecucion-simulada.md). Los informes anteriores conservan su carácter histórico; esta construcción incluye su regresión.

## Construcción

```sh
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -ntp clean verify
```

Resultado final: **BUILD SUCCESS**, 129 pruebas, 0 fallos, 0 errores y 0 omitidas. Construcción finalizada a las 00:49:40 CEST. Temurin 25.0.2+10, Maven Wrapper 3.3.4/Maven 3.9.16, Boot 4.1.2, Picocli 4.7.7 y SQLite JDBC 3.53.4.0; sin dependencias nuevas.

| Suite | Pruebas | Alcance |
| --- | --- | --- |
| `OddLabApplicationTest` | 3 | Composición y cierre |
| `YamlProjectConfigurationTest` | 18 | Descriptor y política |
| `SqliteRunJournalTest` | 27 | Registro real, rollback, decisiones y aceptación atómicas |
| `LocalExecutionFilesTest` | 9 | Huella, petición literal, rutas, enlaces e integridad de artefactos |
| `SimulationCoordinatorTest` | 16 | Intentos, presupuestos, decisiones, aceptación y reanudación segura |
| `PackagedCliIT` | 10 | Raíz y artefacto ejecutable |
| `ProjectCommandsIT` | 26 | Inicialización, diagnóstico, JSON y conservación de archivos |
| `JournalCommandsIT` | 9 | Consultas y solo lectura |
| `SimulationCommandsIT` | 11 | JAR real, escenarios, decisiones, evidencia y errores |

Total: 73 pruebas de núcleo y 56 de integración. Informes regenerables en `target/surefire-reports/` y `target/failsafe-reports/`.

## Evidencia de comportamiento

| Comprobación | Resultado |
| --- | --- |
| Selección explícita y petición literal | `run` exige `--runtime simulated`; petición UTF-8 acotada y conservada, sin interpretar órdenes |
| Éxito y corrección | Recibo válido termina en COMPLETED; retry usa dos identidades de intento y el presupuesto capturado |
| Fallo y falsa afirmación de éxito | Failure/unverified agotan presupuesto y terminan en FAILED; ningún éxito se acepta sin artefacto verificado |
| Frontera del runtime | Perfil con capacidades externas rechazado antes de recibir contexto; solicitud sintética PUBLISH denegada y registrada |
| Decisión pendiente | WAITING_INPUT/3 sin intentos; JSON no pregunta ni concede aprobación; versión y revisión delimitan la respuesta |
| Aprobación y denegación | Resoluciones inmutables enlazadas a decisión; aprobación continúa, denegación finaliza FAILED |
| Atomicidad | Fallos inducidos del evento revierten decisión/transición o evidencia/finalización en SQLite real |
| Evidencia vigente | Recibo, informe, copia literal y revisión comprobados; cambios de revisión dan STALE y artefacto alterado INVALID |
| Reanudación segura | COMPLETED vuelve a verificar sin repetir runtime; estado activo, cierre desconocido o revisión distinta impiden replay |
| Decisión sin atajos | Un READY inconsistente no permite eludir una decisión pendiente |
| Vistas derivadas | Markdown regenerado desde el registro; nunca usado como fuente de estado |
| Compatibilidad del registro | Migración V001 idéntica byte a byte a HEAD; regresión E-01/E-02/E-03 superada |

## Consola interactiva real

Ensayo adicional con el JAR final en un repositorio temporal preparado con `init`, escenario `decision` y petición de ejemplo. Primera ejecución JSON: WAITING_INPUT/3, sin intentos.

Se ejecutó `resume` en una PTY real, sin `--json`, y se introdujo `s` como respuesta sintética del ensayo. Mostró la pregunta `[s/n]` y terminó con código 0, COMPLETED y verificación VALID. La consulta posterior `inspect --json` confirmó decisión APPROVED enlazada a la pendiente, `pendingDecision: null`, exactamente un ATTEMPT_START y aceptación de simulación registrada. La respuesta de prueba no autoriza operaciones externas ni ejecución de la petición original.

Identificador de ejecución del ensayo: `3db70365-ec6c-48ca-8fd6-7b8fe6d5ef59`. Carpeta temporal: `/private/tmp/odd-lab-console-e04-xgn_bh2f`; es evidencia local de esta sesión y no un fixture persistente requerido para construir.

## Artefactos

Validación manual adicional: Carlos confirma haber probado todos los comandos de la secuencia manual de E-04 (preparación, éxito, consultas/reanudación, escenarios, decisión y detección de cambios) y solicita commit y push. Confirmación del usuario; no se adjuntaron salidas adicionales de su Terminal.

JAR probado: `target/odd-lab-0.1.0-SNAPSHOT.jar`.

```text
SHA-256 JAR: db43569a4810761de42b8cf17d4c7d42569e757337d4a81626b5bf4318733216
SHA-256 migración V001: 51b14d55f6b6959d2a7d66bf5b82e9f2abc13d4b13b480f3fedc6eda25be6c00
```

El hash del JAR identifica esta construcción; no promete reproducibilidad binaria. `git diff --check` sin errores. El repositorio real de odd-lab sigue sin `.odd-lab.yaml` ni directorio operativo `.odd-lab/`; los ensayos usan repositorios temporales.

## Límites

La aceptación verifica un circuito sintético, no la implementación de la petición original. N-02, N-08, N-10 y N-12 se ejercitan dentro de ese alcance; no se declaran garantías de un proveedor real ni aceptación integral de esos criterios.

No se ha probado Windows ni conectado un proveedor, credenciales o servidor Codex. No se ha ejecutado el piloto CSV. Propiedad del coordinador, pausa/cancelación, recuperación de procesos y reconciliación general permanecen en E-05; el adaptador real y su aislamiento, en E-06. El registro de intención y las transacciones actuales no demuestran ausencia de efectos externos duplicados tras una caída.
