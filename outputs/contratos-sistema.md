# Contratos del sistema propio

23 de octubre de 2026 · Diseño independiente del lenguaje y del proveedor. No es código ni una configuración ejecutable.

## 1. Entidades

| Entidad | Datos principales | Invariante |
| --- | --- | --- |
| Petición | Texto original, actor, ámbito y revisiones de requisitos | Las correcciones no borran el original |
| Ejecución | Petición, repositorio, base Git, versión de política, estado y presupuesto | Tiene un propietario de escritura identificable |
| Tarea | Requisitos cubiertos, dependencias y resultado esperado | No se ejecuta con dependencias pendientes |
| Intento | Tarea, motor, contexto, límites, espacio y tiempos | Un reintento tiene identidad nueva |
| Decisión | Autor, acción, alcance y versión aplicable | Una autorización obsoleta no habilita otra acción |
| Evidencia | Tipo, revisión examinada, ubicación, hash y resultado | El contenido debe poder resolverse y comprobarse |
| Conocimiento | Contenido, ámbito, procedencia, vigencia y estado de aprobación | No cruza repositorios sin autorización |

Los identificadores y formatos se versionarán. La configuración solo contiene referencias a credenciales. El contexto conserva procedencia y nivel de autoridad: texto de archivos, resultados de herramientas y recuerdos no puede ampliar permisos ni sustituir instrucciones del usuario.

## 2. Casos de uso de la aplicación

- Iniciar una petición y explorarla dentro de su alcance.
- Consultar estado y obtener una vista de tareas y evidencias.
- Resolver una decisión pendiente de producto o permisos.
- Ejecutar la siguiente unidad disponible y recoger resultados.
- Solicitar pausa o cancelación y comprobar su efecto.
- Reanudar tras reconciliar estado y efectos observados.
- Revisar una entrega y exportar el registro del trabajo autorizado.

Una CLI Java 25 con Picocli 4.7.7 traducirá entradas a estos casos de uso. Spring Boot 4.1.2 compondrá los componentes en modo no web, manteniendo los contratos de dominio independientes del framework. No se accede al runtime saltándose el coordinador y la configuración de políticas.

## 3. Puerto de ejecución de agentes

**Entrada:** identidad de ejecución y tarea, rol, referencias a requisitos, versión de contexto y política, revisión base, espacio asignado, capacidades y límites.

**Eventos normalizados:** inicio, progreso, solicitudes de aprobación que exponga el runtime, ejecución de herramientas observada, consumo disponible, resultado, fallo y confirmación de detención. Una notificación de ejecución no implica capacidad para interceptarla. Los eventos se relacionan con identificadores de runtime, sesión y turno; se deduplican con identidad estable cuando exista y se registran las limitaciones de reenvío.

**Resultado:** estado terminal del intento, requisitos tratados, artefactos y sus hashes, comprobaciones declaradas, incertidumbres y consumo disponible. Un `SUCCEEDED` significa que el intento devolvió un resultado; la aplicación decide si ese resultado está verificado.

**Operaciones auxiliares:** consultar capacidades, consultar intento y solicitar detención. Si un motor no permite alguna operación, debe declararlo. No se inventan costes ni equivalencia entre tokens de proveedores distintos.

## 4. Aprobaciones del runtime y aislamiento

**Solicitud expuesta por el runtime:** intento, operación, parámetros y recursos disponibles en el protocolo. **Decisión propia:** permitir, denegar o solicitar una decisión reservada al usuario, con motivo y política aplicada. Si la información es insuficiente para autorizar, no se concede la ampliación de permisos.

El adaptador vincula cada respuesta con la solicitud y sesión correctas. El sistema verifica que el sandbox efectivo cubra rutas, enlaces, procesos y red exigidos por la política. Las operaciones que el runtime ejecuta sin aprobación deben quedar contenidas por ese sandbox. No se presupone mediación individual de todas las herramientas.

Una instrucción de rol no sustituye estas restricciones. Los procesos derivados deben quedar incluidos en cancelación y controles. La viabilidad concreta se comprobará en el sistema operativo elegido antes de admitir ejecución empresarial.

## 5. Estado de una ejecución

Recorrido base propuesto:

`CREATED → EXPLORING → READY → RUNNING → VERIFYING → COMPLETED`

`READY` significa que hay trabajo autorizado y requisitos suficientes; no exige una ceremonia de aprobación para todas las peticiones. Una consulta puede terminar tras explorar. La ruta directa puede realizarse con una sola tarea.

Ramas:

- Decisión externa necesaria: `WAITING_INPUT`, con pregunta y punto de continuación.
- Verificación fallida: crear intento de corrección y volver a `RUNNING` dentro del presupuesto.
- Pausa: `PAUSING → PAUSED` después de confirmar detención o reconciliar efectos.
- Cancelación: `CANCELLING → CANCELLED` después de confirmar detención.
- Detención no confirmada: `RECONCILIATION_REQUIRED`; nunca declarar cancelación efectiva sin evidencia.
- Fallo o presupuesto agotado: `FAILED`, con diagnóstico y resultados parciales conservados.

`COMPLETED` exige que el resultado cumpla los criterios obligatorios y sus comprobaciones aplicables. Las comprobaciones opcionales no realizadas se declaran. Un fallo o pendiente obligatorio impide ese estado. Completar no autoriza publicar, integrar en otra rama o modificar sistemas externos.

Las transiciones son responsabilidad del núcleo, con versión esperada del estado para rechazar actualizaciones concurrentes antiguas.

## 6. Consistencia y recuperación

Estado y evento se guardan juntos. Para artefactos: primero escribir archivo temporal, cerrar y calcular hash; después publicarlo en una ubicación estable y referenciarlo transaccionalmente. Al iniciar, reconciliar temporales, archivos huérfanos y referencias ausentes. Una evidencia ausente no cuenta como verificación correcta.

Antes de ejecutar una acción, registrar su intención; al terminar, registrar su resultado. Si el proceso cae entre ambos pasos, el efecto es desconocido hasta comprobarlo. No se promete ejecución exactamente una vez para efectos externos.

La recuperación comprueba propietario vivo, procesos, artefactos y revisión del repositorio. El mecanismo concreto de bloqueo y detección de procesos se decidirá en implementación. Un resumen narrativo no sustituye esta reconciliación.

## 7. Criterios verificables del núcleo

| ID | Escenario | Resultado exigido |
| --- | --- | --- |
| N-01 | Intento de herramienta fuera del ámbito | Denegación efectiva; recurso intacto |
| N-02 | Encargo intenta ampliar permisos | Rechazado y registrado |
| N-03 | Caída tras guardar un resultado | Reanudación sin aplicar el cambio dos veces |
| N-04 | Caída con acción iniciada y resultado desconocido | Reconciliación antes de repetir |
| N-05 | Cambio de adaptador | Tareas, requisitos y evidencia siguen legibles |
| N-06 | Dos instancias reclaman la ejecución | Solo una obtiene propiedad de escritura |
| N-07 | Cancelación con proceso hijo | Detención confirmada o estado pendiente honesto |
| N-08 | Política prohíbe proveedor | Ningún contexto se envía a ese proveedor |
| N-09 | Memoria de otro repositorio | No se recupera fuera del ámbito permitido |
| N-10 | Cambia el código tras la comprobación | Evidencia afectada deja de ser vigente |
| N-11 | Dos tareas concurrentes | Ámbitos separados y presupuesto conjunto respetado |
| N-12 | Resultado narrativo sin artefacto verificable | No se acepta como comportamiento demostrado |

La simulación verifica reglas del núcleo. N-01 y N-07 requieren además pruebas con procesos y archivos reales; N-05 requiere comprobar el contrato de los adaptadores; las garantías de un proveedor solo se validan conectándolo.


## 8. Contrato público de terminal

La sintaxis propuesta, códigos de salida, salida JSON y tratamiento de solicitudes desde otra terminal están definidos en [estructura y CLI](estructura-y-cli.md). El estado de negocio permanece en el núcleo; el código de salida expresa el resultado del comando invocado. Ayuda y versión no requieren repositorio, almacenamiento ni sesión de IA.
