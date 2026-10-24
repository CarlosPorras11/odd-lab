# Propiedad, control y recuperación — E-05

24 de octubre de 2026. Extiende el [circuito simulado E-04](ejecucion-simulada.md) con propiedad exclusiva, solicitudes desde otra terminal y recuperación comprobada con procesos Java locales. No conecta IA ni implementa la petición original.

## Perfiles y comandos

`run --runtime simulated` conserva el simulador en memoria. `run --runtime process` selecciona expresamente un trabajador sintético compilado que inicia un proceso Java y su hijo. Ambos devuelven recibos del circuito; ninguno interpreta la petición como código ni acepta comandos arbitrarios. `process` no es un proveedor ni un sandbox empresarial. Su duración se captura mediante `--process-seconds`, entre 1 y 60 segundos, por defecto 15; `--scenario` conserva los escenarios E-04. En `decision`, la pregunta se resuelve antes de iniciar procesos.

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar run --repo /ruta/al/repositorio --request-file examples/requests/simulation.md --runtime process --process-seconds 30 --json
```

Desde otra terminal, consultar el identificador mientras el primer comando sigue trabajando:

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar status --repo /ruta/al/repositorio --json
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar pause --repo /ruta/al/repositorio --run UUID --wait-seconds 5 --json
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar inspect --repo /ruta/al/repositorio --run UUID --json
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar resume --repo /ruta/al/repositorio --run UUID --json
```

Para probar cancelación, iniciar otra ejecución y usar `cancel` en lugar de `pause`. No ejecutar ambos comandos esperando dos resultados diferentes sobre una ejecución ya cancelada. `pause`/`cancel` admiten `--wait-seconds` entre 0 y 30; por defecto 5.

| Operación | Resultado |
| --- | --- |
| Solicitud confirmada | `pause`/`cancel`: código 0; propietario en primer plano: código 4 |
| Solicitud sin confirmar | Código 5, `CONTROL_PENDING`; petición guardada, sin afirmar detención |
| Otra instancia conserva propiedad | `resume`: código 5, `RUN_OWNED`; ningún intento nuevo |
| Reanudar PAUSED seguro | Resultado guardado: verificarlo; intento interrumpido: siguiente ordinal dentro del mismo presupuesto |
| Recuperar proceso huérfano conocido | Primero detener/comprobar procesos; devolver PAUSED/4 o CANCELLED/4; no iniciar otro intento en esa invocación |
| Efectos o identidades insuficientes | `RECONCILIATION_REQUIRED`/5; sin repetición automática |

Un presupuesto agotado no se reinicia con una pausa. `CANCELLED`, `FAILED` y `COMPLETED` son terminales; en COMPLETED solo se vuelve a comprobar evidencia. Una solicitud sobre el mismo PAUSED/CANCELLED ya confirmado devuelve 0; otro estado terminal devuelve 2.

## Propiedad y solicitudes

El propietario mantiene un bloqueo exclusivo del sistema operativo sobre `.odd-lab/state/control/UUID/owner.lock` durante toda la operación `run`/`resume`, incluidas preguntas interactivas y escrituras de vistas. Un segundo escritor cooperante no lo obtiene. Nunca se borra el archivo para desbloquearlo, ni se roba propiedad por antigüedad, PID o timeout. Al morir el proceso, el sistema operativo libera el bloqueo.

El archivo guarda UUID de repositorio/ejecución, token de adquisición, PID, inicio del proceso y fecha de adquisición. Estos metadatos identifican al propietario observado; no sustituyen el bloqueo ni prueban que siga vivo. No hay lease temporal renovable, daemon ni servicio HTTP.

`pause`/`cancel` no adquieren propiedad ni modifican estados/eventos de negocio. Publican una solicitud JSON independiente por UUID en ese mismo directorio, con ámbito, acción y fecha. Se escribe en temporal, fuerza el archivo y se publica mediante movimiento atómico. No se afirma durabilidad ante pérdida eléctrica del directorio. Temporales incompletos no son solicitudes y se conservan para inspección.

Es un buzón operativo persistente, no una segunda fuente del estado de ejecución. SQLite conserva estados, eventos y confirmaciones `CONTROL_ACK`. Separar el buzón evita que un solicitante concurrente incremente la versión del estado que usa el propietario. No se modifica V001 ni se necesita una migración nueva: los nuevos registros utilizan los tipos de entrada existentes.

El propietario consulta solicitudes en puntos seguros y mientras espera el proceso. CANCEL tiene prioridad sobre PAUSE pendiente; una cancelación posterior a la lectura de una pausa no queda consumida como pausa. Cada confirmación vincula identidad, acción, resolución y fechas. `status --json`/`inspect --json` muestran `controlRequests`: PENDING, PROCESSED o CONFIRMED; PROCESSED no implica detención confirmada. CONFIRMED se deriva de la transición posterior correspondiente, incluso tras reanudar. Las consultas no crean buzón, archivos ni vistas.

Si no hay propietario, la solicitud queda pendiente. `resume` adquiere propiedad y la reconcilia; el comando solicitante no se convierte en coordinador. Un timeout no deshace la solicitud ni certifica que el proceso se haya detenido. Solicitudes opuestas o llegadas tras un estado terminal pueden permanecer sin confirmación; su historial no se borra.

## Procesos y prueba de detención

El perfil `process` lanza únicamente el trabajador Java fijo del producto. Este inicia un hijo, comunica su identidad y espera una señal GO antes de realizar la espera sintética. El coordinador registra las identidades observadas antes de enviar GO. El contexto de la petición permanece en el coordinador: el trabajador solo recibe modo y duración. No se le transmite ruta de repositorio, petición, credenciales o una orden de shell.

Cada proceso registrado conserva PID y fecha de inicio; se comprueba parentesco en vivo. La supervisión compara ambas identidades antes de enviar señales. Un PID reutilizado, identidad ilegible o imposibilidad de enumerar descendientes impide confirmar detención. Se intenta cierre normal y, dentro de un plazo acotado, forzado; se comprueba padre e hijo y los descendientes observados. La prueba cubre el árbol fijo sintético; no acredita procesos arbitrarios que escapen o servicios remotos.

El resultado STOPPED del runtime no completa el trabajo. El coordinador registra el intento detenido, procesa el control y pasa por PAUSING/CANCELLING. Publica un informe `stop-UUID.txt` con identidades observadas, acción y momento; `confirmStopped` guarda evidencia y PAUSED/CANCELLED en una transacción con versión esperada. Una cancelación cierra también una decisión pendiente. Un fallo del evento final revierte evidencia y estado; no se afirma cierre a partir de una transición genérica.

La interrupción de terminal solicita PAUSE mediante un hook y concede una espera acotada al coordinador. El adaptador también intenta detener sus procesos al cerrar la JVM. SIGKILL no ejecuta hooks: un bloqueo liberado no demuestra que sus hijos hayan muerto. La recuperación posterior es obligatoria.

## Recuperación sin repetir efectos

- VERIFYING con resultado persistido: comprobar revisión/política, copia de petición, recibo y procesos; completar desde ese resultado sin invocar de nuevo el runtime. Un informe huérfano idéntico se verifica por hash y reutiliza; uno distinto no se sobrescribe.
- RUNNING sin resultado, con padre/hijo identificados del trabajador fijo: comprobar y detener procesos; registrar resultado STOPPED y dejar PAUSED o CANCELLED según control. Hace falta otra invocación para continuar una tarea interrumpida, con intento y ordinal nuevos.
- Intención sin identidades suficientes, runtime sin confirmación o efectos desconocidos: conservar RECONCILIATION_REQUIRED. No se deduce ausencia de efectos porque el propietario haya muerto ni por falta de resultado.
- PAUSED: volver a comprobar cierre y hash del informe de detención antes de ejecutar. Si ya existía un resultado correcto previo a la pausa, verificarlo en vez de lanzar otro intento. Si había decisión pendiente, recuperar la pregunta sin autorizar automáticamente.

La respuesta a una decisión sigue ligada a su UUID, ámbito y revisión. Se admite un ciclo de pausa/control confirmado posterior a la versión original de la pregunta; la escritura de la respuesta utiliza la versión actual esperada. Cualquier otro cambio de entradas impide reutilizarla. JSON nunca pregunta ni responde automáticamente.

Antes de ejecutar trabajo nuevo, la política, revisión y petición deben seguir vigentes. La reconciliación de procesos o una cancelación pueden realizarse aunque el código haya cambiado; no aceptan ese código ni envían contexto nuevo a un runtime. Artefactos ausentes/alterados no cuentan como aceptación. Archivos huérfanos y temporales se conservan; no hay borrado ni importación automática de contenido sin referencia.

## Alcance y límites

N-03, N-04, N-06 y N-07 se ejercitan con almacenamiento real, procesos JVM reales y dos instancias CLI, dentro del trabajador sintético. La recuperación de efectos arbitrarios y las garantías del proveedor quedan en E-06. No se promete ejecución exactamente una vez, aislamiento frente a manipulación hostil del sistema de archivos ni soporte de bloqueos en sistemas remotos/NFS.

Validado en macOS arm64. Enumerar descendientes necesita permisos del sistema: el sandbox de esta sesión bloquea `sysctl`; la validación de procesos se ejecuta fuera de él. Un entorno que impida observación/detención obtiene cierre desconocido, no una confirmación ficticia. Windows no ejecutado.

No se añaden dependencias, instalaciones, credenciales ni cambios de configuración global. Prueba y límites completos en [validación E-05](validacion-e05.md). Siguiente entrega: E-06, integración autorizada y comprobada del runtime real.
