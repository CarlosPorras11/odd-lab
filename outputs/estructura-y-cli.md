# Estructura Java y contrato inicial de CLI

23 de octubre de 2026 · Diseño e implementación incremental. E-01 a E-05 completadas: construcción, configuración/política, SQLite, consultas y flujo simulado `run`/`resume` con evidencia/decisiones. Control/recuperación con procesos Java sintéticos implementados; runtime real pendiente.

## 1. Decisiones de construcción

Base confirmada: Java 25, Spring Boot 4.1.2 y Picocli 4.7.7. Un solo módulo Maven con Wrapper; la [preparación de implementación](preparacion-implementacion.md) concreta Maven 3.9.16 como objetivo y los criterios de E-01. Wrapper 3.3.4 y primera compilación validados en E-01; SnakeYAML y Jackson se usan en E-02 para YAML/JSON; E-03 utiliza SQLite JDBC 3.53.4.0 y migración SQL v1 propia.

Nombre de trabajo del ejecutable: `odd-lab`. Paquete base provisional: `dev.oddlab`; no representa un dominio empresarial registrado. Artefacto inicial: JAR ejecutable. Un lanzador de terminal se añadirá al empaquetado cuando el JAR funcione.

## 2. Estructura propuesta

```text
odd-lab/
├── pom.xml
├── mvnw
├── mvnw.cmd
├── .mvn/wrapper/
├── README.md
├── outputs/                         # Diseño existente
├── src/main/java/dev/oddlab/
│   ├── bootstrap/                   # Inicio y composición Spring
│   ├── cli/                         # Comandos, formato y códigos de salida
│   ├── work/                        # Peticiones, requisitos, tareas y decisiones
│   ├── orchestration/               # Planificación, colas y ciclo de ejecución
│   ├── policy/                      # Restricciones y autorizaciones
│   ├── context/                     # Construcción de contexto por encargo
│   ├── memory/                      # Persistencia y recuperación de conocimiento
│   ├── runtime/                     # Puerto de runtime; adaptadores simulado y Codex
│   ├── workspace/                   # Espacios aislados y cambios Git
│   └── verification/                # Comprobaciones y evidencia de entrega
├── src/main/resources/
│   └── application.yaml
└── src/test/java/dev/oddlab/         # Pruebas por capacidad
```

Dentro de cada capacidad, crear `domain`, `application` e `infrastructure` cuando exista contenido que lo justifique. No generar docenas de carpetas vacías.

- `domain`: reglas y tipos con dependencias del JDK; sin Spring, Picocli, JDBC ni protocolo Codex.
- `application`: casos de uso y puertos; lógica independiente del framework y del proveedor.
- `infrastructure`: adaptadores de almacenamiento, procesos, Git y comunicación.
- `cli`: traduce argumentos y presenta resultados; no decide transiciones de negocio.
- `bootstrap`: configura Spring en modo no web y conecta implementaciones.

Las colaboraciones entre capacidades usan contratos explícitos, evitando dependencias sobre la infraestructura ajena. No se crea un paquete `common` para acumular tipos sin propietario.

## 3. Comandos iniciales

| Comando propuesto | Responsabilidad |
| --- | --- |
| `odd-lab init --repo <ruta>` | Registrar configuración local para un repositorio Git existente |
| `odd-lab run --repo <ruta> --request-file <archivo> --runtime simulated` | Crear una ejecución y procesar la petición en primer plano |
| `odd-lab status --repo <ruta> [--run <id>]` | Consultar resumen de ejecuciones o una concreta |
| `odd-lab inspect --repo <ruta> --run <id>` | Mostrar requisitos, tareas, decisiones, cambios y evidencia |
| `odd-lab pause --repo <ruta> --run <id>` | Solicitar una pausa y esperar confirmación o informar que queda pendiente |
| `odd-lab resume --repo <ruta> --run <id>` | Reconciliar y continuar una ejecución detenida |
| `odd-lab cancel --repo <ruta> --run <id>` | Solicitar cancelación y comprobar detención |
| `odd-lab doctor --repo <ruta>` | Diagnóstico de solo lectura: configuración, almacenamiento y capacidades disponibles |

La raíz ofrece `--help` y `--version`. `init`, `doctor`, `status`, `inspect`, `run`, `resume`, `pause` y `cancel` ofrecen ayuda, versión y `--json`. `run`/`resume` usan perfiles sintéticos explícitos según [contrato E-04](ejecucion-simulada.md) y [extensión E-05](control-y-recuperacion.md). `--json` selecciona salida estructurada en esos subcomandos. La ruta de repositorio es explícita en la primera versión para evitar actuar sobre uno distinto del esperado.

`run` usa archivo de petición para conservar texto literal y evitar que una petición larga dependa del escape del shell. El archivo se copia al registro de la ejecución con su hash y versión. No se interpreta como código ni autoriza operaciones fuera de su contenido y la política.

`init` no crea repositorios, instala software, habilita proveedores ni realiza commits. Si ya existe configuración, la valida y evita sobrescribirla silenciosamente.

Los comandos consultivos no invocan IA. La inicialización y el diagnóstico se ejecutan con conectores locales; `doctor` no inicia una sesión de inferencia.

## 4. Configuración y datos

Archivos y directorios que prepara `init` en un repositorio de trabajo (E-02 implementada):

```text
<repositorio>/
├── .odd-lab.yaml                    # Identidad y política del proyecto; sin secretos
└── .odd-lab/
    ├── state/                      # Almacenamiento local transaccional
    ├── artifacts/                  # Evidencias y cambios por ejecución
    ├── workspaces/                 # Espacios de escritura aislados
    └── views/                      # Vistas regenerables
```

El descriptor puede versionarse por decisión del usuario. Los datos operativos quedan excluidos del control de versiones mediante entradas específicas de `.gitignore`, preservando el contenido previo. Este efecto formará parte de la ayuda de `init`.

Los ámbitos de memoria siguen siendo explícitos, aunque el almacenamiento esté separado físicamente por proyecto. Los registros no guardan credenciales. Configuración y estado tienen versión de formato; una versión desconocida falla con diagnóstico en vez de reinterpretarse.

Los cambios del runtime se realizan en espacios preparados para cada tarea; los datos del coordinador no se incluyen como contexto ni como ámbito de escritura del trabajador. Habrá que probar la separación efectiva en el sandbox.

## 5. Vida de los procesos

Primera versión: ejecución en primer plano. La CLI mantiene el coordinador y los procesos runtime mientras trabaja; no se instala un daemon ni un servidor HTTP.

`status` e `inspect` pueden leer desde otro terminal. `pause` y `cancel` escriben una solicitud de control en el almacenamiento y el propietario activo la procesa. Eso no les concede propiedad sobre la ejecución. Cada solicitud tiene identidad, acción, fecha y confirmación.

Si el propietario no responde o ya no existe, el comando informa del estado pendiente y exige reconciliar antes de reiniciar o declarar detención. No se da por cancelado un runtime solo porque murió la terminal. Los tiempos de espera son configurables y acotados.

`resume` solo adquiere propiedad cuando la ejecución no tiene otro propietario vivo. Antes de abrir otro runtime comprueba procesos y efectos de la sesión anterior. Si una acción no puede reconciliarse, conserva `RECONCILIATION_REQUIRED` y explica qué falta.

Interrupción de terminal: solicitar pausa y detener/reconciliar procesos; registrar cierre abrupto si no se confirma. No prometer pausa instantánea de comandos arbitrarios.

## 6. Decisiones interactivas y automatización

En un terminal interactivo, una ejecución puede mostrar una pregunta concreta de producto o una aprobación reservada al usuario. La respuesta queda ligada a la solicitud y su versión; no existe «aceptar todo» por defecto.

Con `--json` o sin terminal interactivo, no se pregunta. Ante una decisión necesaria, no se autoriza la operación pendiente: el adaptador intenta detener de forma segura el turno, conserva la decisión de negocio y registra `WAITING_INPUT` solo si ya no quedan efectos activos desconocidos. Si no puede demostrarlo, usa `RECONCILIATION_REQUIRED`.

El usuario continúa mediante `resume` en terminal interactivo. La sesión retomada puede necesitar una nueva aprobación del runtime; una solicitud antigua no se reproduce como autorización vigente. El envío automatizado de respuestas queda fuera de la primera entrega.

## 7. Salida y errores

En modo humano: resumen con identificador, estado, resultado y pendiente accionable. En modo JSON: un objeto final en stdout, sin prompts ni mensajes decorativos. El progreso y diagnósticos van por stderr; las evidencias detalladas se consultan con `inspect`.

Contrato JSON inicial: `schemaVersion`, `command`, `runId` si existe, `state` si aplica, `result`, `pendingDecision` y `error` con código estable y mensaje. No se exponen secretos ni trazas internas por defecto.

| Código de salida | Significado |
| --- | --- |
| 0 | Comando completado; para `run`/`resume`, objetivo completado y verificado |
| 1 | Fallo de ejecución, verificación o persistencia |
| 2 | Uso, configuración o precondición inválida |
| 3 | Decisión requerida con ejecución detenida de forma conocida |
| 4 | Ejecución pausada o cancelada con detención confirmada |
| 5 | Reconciliación necesaria o solicitud de control sin confirmación |

`status` puede devolver 0 al consultar correctamente una ejecución fallida: su resultado incluye `state=FAILED`. El éxito de una consulta no implica éxito del trabajo. `pause` y `cancel` devuelven 0 cuando se confirma la operación solicitada; 4 corresponde a la ejecución en primer plano que se ha detenido.

## 8. Primeras entregas de implementación

- [x] E-01 — Maven Wrapper, Java 25, composición Boot y raíz Picocli con ayuda y versión.
- [x] E-02 — Configuración del proyecto, política inicial y diagnóstico local.
- [x] E-03 — Registro transaccional propio, estados y comandos consultivos.
- [x] E-04 — `run` y `resume` con runtime simulado; evidencia y decisiones pendientes.
- [x] E-05 — Solicitudes de control, propiedad de ejecución y recuperación tras caída.
- [ ] E-06 — Adaptador Codex, negociación de capacidades y pruebas reales de aislamiento.
- [ ] E-07 — Verificación integrada y comparación del piloto CSV.

Cada entrega produce una unidad revisable con pruebas del comportamiento relevante. Se comprobarán especialmente uso inválido, exclusión de otro propietario, transiciones, salida JSON y recuperación; una prueba simulada no certifica el runtime real.

Los nombres y contratos anteriores son decisiones de diseño propias. E-01 implementa raíz, ayuda y versión; E-02 implementa `init`, `doctor` y política/configuración v1. E-03 añade registro transaccional, estados y consultas; E-04 añade circuito simulado con evidencia y decisiones; E-05 añade propiedad, control y recuperación del trabajador sintético; E-06/E-07 pendientes. Véanse [contrato E-02](configuracion-y-diagnostico.md), [validación E-01](validacion-e01.md), [validación E-02](validacion-e02.md), [registro E-03](registro-y-consultas.md) [ejecución E-04](ejecucion-simulada.md) y [control E-05](control-y-recuperacion.md).
