# Continuidad de odd-lab

Actualizado el 24 de octubre de 2026. E-01 a E-03 implementadas y validadas; E-04 a E-07 pendientes. Este resumen permite retomar en otra conversación sin volver a decidir la arquitectura.

## Punto de entrada

Proyecto real: `/Users/carlos/dev/01-proyectos/odd-lab`.

La conversación de origen sigue asociada a `/Users/carlos/Documents/Codex/2026-10-23/qu`; esa carpeta no es el proyecto. Sus 24 archivos intermedios se trasladaron a `work/` dentro de odd-lab. Allí se conservan borradores históricos y manifiestos de copia; la documentación vigente está en `outputs/`. Los borradores no deben reemplazar las versiones finales. `work/` permanece excluido de Git.

Antes de actuar en otra sesión, confirmar directorio, instrucciones aplicables, contenido actual y estado Git. Las condiciones del sistema de archivos y permisos dependen de la sesión; el resumen no concede permisos adicionales.

## Objetivo y alcance confirmado

Construir desde cero un sistema propio de agentes y subagentes para desarrollo de software, inspirado en **Organic Driven Development (ODD) de Gentleman Programming**.

Primer escenario: uso individual en repositorios empresariales, preparado conceptualmente para crecer a equipo. El sistema será mantenido y controlado por Carlos. No reutilizar ai-flow ni ai-mastery ni asumir sus contratos o requisitos.

**Gentle-AI y Engram están excluidos de la arquitectura.** Que existan instalados en la máquina no los convierte en dependencias. No desinstalarlos ni cambiar su configuración como parte de este trabajo.

Carlos pidió guardar el plan, preparar el contexto e implementar E-01/E-02; después autorizó continuar con E-03. Las tres están implementadas y validadas, sin comenzar E-04 ni conectar un runtime. La preparación está en [preparación de implementación](preparacion-implementacion.md) y los resultados en [validación E-01](validacion-e01.md), [validación E-02](validacion-e02.md) y [validación E-03](validacion-e03.md).

## Decisiones cerradas

| Aspecto | Decisión |
| --- | --- |
| Lenguaje | Java 25 |
| Framework | Spring Boot 4.1.2 en modo no web |
| CLI | Picocli 4.7.7; biblioteca directa `info.picocli:picocli` |
| Construcción | Un módulo Maven; Wrapper 3.3.4 `only-script`, Maven 3.9.16 con SHA-256 fijado; compilado con Temurin 25.0.2 |
| Organización | Paquetes por capacidad; `domain`, `application`, `infrastructure` cuando haya contenido |
| Tipo de motor | Runtime completo; no construir de inicio un bucle propio de inferencia por API |
| Primera integración propuesta | Codex App Server por stdio desde un adaptador Java |
| Coordinación global | Propia: tareas, dependencias, permisos exigidos, decisiones y evidencia |
| Memoria/registro | Propios; registro SQLite local implementado en E-03; verificación de artefactos y conocimiento avanzado pendientes |
| Forma inicial | Aplicación local para un usuario, ejecución en primer plano |
| Nombre y paquete de trabajo | `odd-lab`, paquete provisional `dev.oddlab` |

Spring se limita a composición e infraestructura. Dominio y casos de uso deben ser independientes de Spring, Picocli y protocolo Codex. No hay que introducir un starter Picocli adicional cuya compatibilidad no esté comprobada.

## Frontera con el runtime

Nuestro sistema coordina encargos y conserva estado; Codex resuelve cada encargo con sus herramientas y bucle interno. El adaptador traduce eventos, límites y solicitudes de aprobación expuestas. No se puede afirmar que toda herramienta de Codex pase individualmente por un ejecutor nuestro.

La ayuda local de App Server lo marcaba experimental. Antes de integrarlo, comprobar versión, esquema y capacidades reales. No se ha iniciado ni probado esa integración. No se presupone que el usuario tenga autorización empresarial para enviar código a un proveedor; el piloto usa datos sintéticos.

Cambiar de motor debe preservar nuestro registro; no se promete migrar una sesión interna del runtime.

## Reglas operativas del diseño

- Explorar antes de editar; usar el trabajo directo cuando baste y justificar delegaciones.
- Primer piloto: un principal y hasta dos subagentes activos; sin descendientes fuera del registro y presupuesto del coordinador.
- Un intento inicial y hasta dos correcciones por tarea dentro del presupuesto global.
- Un solo propietario de escritura por ejecución; verificar exclusión y recuperación.
- Separar espacios de escritores. Un worktree no garantiza aislamiento de permisos.
- Datos estructurados y eventos transaccionales; Markdown como vistas regenerables, sin segunda fuente de estado.
- Evidencias vinculadas a la revisión examinada. Una afirmación del agente no demuestra éxito.
- No declarar pausa o cancelación confirmada si quedan procesos o efectos desconocidos.
- Las simulaciones validan mecánica, no proveedores ni seguridad efectiva del runtime.
- Los permisos y políticas se comprueban con mecanismos efectivos; un prompt no impone aislamiento.
- No ampliar el alcance ni publicar cambios por inferencia del modelo.

## CLI diseñada

Comandos previstos: `init`, `run`, `status`, `inspect`, `pause`, `resume`, `cancel`, `doctor`, además de `--help`, `--version` y salida `--json`.

Se exige repositorio explícito en las operaciones de proyecto. `run` recibe un archivo de petición. `status` e `inspect` son consultivos y no invocan IA. En modo JSON no se pregunta ni se autoriza automáticamente: una decisión pendiente detiene de forma segura o requiere reconciliación.

La especificación detallada de sintaxis, formato de salida, códigos, datos de `init` y ciclo de procesos está en [estructura y CLI](estructura-y-cli.md). Existen raíz con ayuda/versión y comandos `init`, `doctor`, `status`, `inspect`, con `--repo` obligatorio y salida `--json`. Las consultas son de solo lectura. `run`/`resume` y control de procesos siguen pendientes. Descriptor y política v1 en [configuración y diagnóstico](configuracion-y-diagnostico.md).

## Estado real observado al guardar

- Hay documentación de diseño, continuidad, preparación, contratos E-02/E-03 y validaciones E-01 a E-03.
- Hay archivos de IntelliJ `.idea` y metadatos de macOS; preservarlos salvo petición posterior.
- Existen POM, Wrapper Unix/Windows y capacidades `bootstrap`, `cli`, `policy`, `workspace`, `work` y `memory`, con dominio y aplicación independientes de Spring/Picocli. Adaptadores locales para Git, archivos, YAML y SQLite; sin capas vacías.
- Repositorio Git local inicializado en `main` por petición de Carlos. La documentación se versiona junto al `.gitignore`; consultar el historial al retomar.
- `clean verify` con Wrapper Maven 3.9.16 y Temurin 25.0.2: 46 pruebas de composición/configuración/SQLite y 45 de integración del JAR, todas superadas (91). Resultado: `target/odd-lab-0.1.0-SNAPSHOT.jar`.
- Existe adaptador de registro transaccional, creado por `init`. Las pruebas usan repositorios temporales sin inicializar este proyecto automáticamente. No hay runtime conectado ni memoria semántica.
- Se han descargado Maven y dependencias de construcción en cachés locales. No se han cambiado instalaciones ni preferencias globales; Windows no probado.

Las versiones locales observadas anteriormente fueron Codex CLI `0.163.0-alpha.11.2`, Gentle-AI `3.7.0` y Engram `1.15.0`. Son un inventario histórico, no una dependencia ni confirmación de estado actual.

## Plan de ejecución pendiente

- [x] **E-01:** esqueleto Maven, Java 25, composición Spring Boot y raíz Picocli con ayuda y versión. Validada; véase [evidencia](validacion-e01.md).
- [x] **E-02:** configuración del repositorio, política inicial, `init` y `doctor`; salida humana/JSON. [Evidencia](validacion-e02.md).
- [x] **E-03:** almacenamiento transaccional propio, estados y consultas `status`/`inspect`. [Evidencia](validacion-e03.md).
- [ ] **E-04:** `run` y `resume` con runtime simulado, evidencia y decisiones pendientes.
- [ ] **E-05:** control de procesos, pausa/cancelación, propiedad y recuperación tras caída.
- [ ] **E-06:** adaptador Codex y pruebas reales de protocolo, capacidades e aislamiento.
- [ ] **E-07:** verificación integrada y comparación del piloto CSV.

E-01 mantiene ayuda y versión sin repositorio ni runtime. E-02 añade inicialización idempotente, preservación de descriptor/archivos y diagnóstico de solo lectura. E-03 aporta registro transaccional, estados, consultas y diagnóstico SQLite. Siguiente entrega: E-04, `run` y `resume` con runtime simulado; todavía no implementada.

Maven 3.9.16 y Wrapper 3.3.4 están generados y validados con Temurin 25.0.2. Pendientes técnicos por entrega: actualización/distribución empresarial del JDK 25, futuras migraciones de esquema, autenticación de Codex y detalles efectivos de sandbox. Consultar fuentes oficiales actuales antes de fijar dependencias; conservar las versiones ya elegidas salvo incompatibilidad demostrada y explicada.

## Preparación del arranque

Los seis documentos vigentes se han revisado. [Preparación de implementación](preparacion-implementacion.md) consolida el contexto, registra el entorno y concreta E-01 con criterios verificables. Se añade `AGENTS.md` como guía local. El cambio previo del usuario sobre los borradores trasladados a `work/` se conserva.

Atención al JDK: `java` resuelve OpenJDK 26.0.1, mientras `mvn -version` usa Java 25.0.2 de Homebrew. E-01 se ha construido y ejecutado seleccionando Temurin 25.0.2 explícitamente, sin cambiar configuración global. E01-A a E01-G superados en macOS; evidencia y comandos en [validación E-01](validacion-e01.md).

## Contrato y límites de E-02

Descriptor `.odd-lab.yaml` v1 con identidad UUID y política v1, inicialmente sin proveedores ni red. Límites del piloto: máximo 2 subagentes y 2 correcciones; publicación y cambios en sistemas externos reservados al usuario. Configuración YAML estricta, sin campos desconocidos, secretos ni migración automática. YAML/JSON mediante SnakeYAML 2.6 y Jackson 3.1.7 gestionados por Boot.

`init` valida antes de preparar directorios y exclusiones Git; no sustituye descriptor válido ni borra obstáculos. Repetirlo repara layout/exclusiones incompletos. `doctor` no escribe, no repara ni inicia IA. En E-02 informaba de almacenamiento `NOT_IMPLEMENTED`; E-03 lo sustituye por comprobación real del registro. El runtime sigue `NOT_CONFIGURED`. Una preparación sana no acredita ejecución de agentes. La inicialización no es una transacción de varios archivos ni un mecanismo de aislamiento frente a cambios hostiles concurrentes. Contrato detallado en [configuración y diagnóstico](configuracion-y-diagnostico.md).

## Contrato y límites de E-03

Registro en `.odd-lab/state/journal.sqlite`, SQLite JDBC 3.53.4.0 y migración SQL v1 con checksum y UUID de repositorio. `init` lo crea también para proyectos E-02; consultas y doctor no migran ni crean datos. Conservar descriptor y repetir `init` es la ruta de actualización local.

Puerto `RunJournal` independiente de JDBC: creación `CREATED` v0, cambio de estado con versión esperada y entradas inmutables de requisitos/tareas/decisiones/evidencia. Estado, entrada y evento correspondiente se guardan en una transacción; fallos de evento provocan rollback. La inspección valida hash de petición y coherencia de versiones/estados/entradas/eventos. No permite afirmar `COMPLETED`, `PAUSED` ni `CANCELLED` sin los futuros casos de uso de verificación/detención.

`status` lista o consulta estado; `inspect` muestra petición, instantánea de política, entradas y eventos. Consultar un `FAILED` devuelve 0 si la consulta es correcta. Proyecto nuevo: lista vacía; aún no hay comando público para crear ejecuciones. Los registros poblados se verifican con fixtures sintéticos. N-05/N-09 solo tienen cobertura parcial del registro; no se declaran completos ni se acredita memoria semántica. Contrato completo en [registro y consultas](registro-y-consultas.md).

## Piloto previsto

Primero validar núcleo y recuperación con ejecutor simulado; después integrar un runtime aprobado; finalmente comparar un agente, delegación adaptativa y paralelismo controlado sobre un procesador CSV de gastos.

El CSV y Python para ese pequeño producto son propuestas de ejemplo, no el lenguaje del sistema de agentes. Se conservan especificaciones precisas en [piloto](piloto-odd.md). No hay resultados ni se ha demostrado que varios agentes mejoren el rendimiento.

## Documentos de referencia

1. [Arquitectura](sistema-agentes-odd.md): capacidades, responsabilidades y límites.
2. [Decisiones](decisiones-entorno-odd.md): ADR, tecnologías y alcance.
3. [Contratos](contratos-sistema.md): entidades, puertos, estados y criterios N-01 a N-12.
4. [Estructura y CLI](estructura-y-cli.md): paquetes, comandos y entregas E-01 a E-07.
5. [Piloto](piloto-odd.md): casos CSV y evaluación del núcleo y agentes.

Este resumen orienta; los documentos detallan los contratos. Si el usuario cambia una decisión después de esta fecha, actualizar lo afectado y conservar las partes válidas del plan.

## Texto para retomar en otra conversación

> Trabaja en `/Users/carlos/dev/01-proyectos/odd-lab`. Lee `AGENTS.md`, `outputs/continuidad.md`, `outputs/registro-y-consultas.md` y los contratos de la entrega. E-01 a E-03 están implementadas y validadas. Continúa con E-04: `run` y `resume` con runtime simulado, evidencia y decisiones pendientes. Comprueba antes el estado real. Mantén Java 25, Spring Boot 4.1.2, Picocli 4.7.7 y coordinación/memoria propias; sin Gentle-AI ni Engram. No conectes un proveedor real ni amplíes a E-05. Actualiza estado y evidencia al terminar.
