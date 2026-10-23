# Continuidad de odd-lab

Actualizado el 23 de octubre de 2026. E-01 implementada y validada; E-02 a E-07 pendientes. Este resumen permite retomar en otra conversación sin volver a decidir la arquitectura.

## Punto de entrada

Proyecto real: `/Users/carlos/dev/01-proyectos/odd-lab`.

La conversación de origen sigue asociada a `/Users/carlos/Documents/Codex/2026-10-23/qu`; esa carpeta no es el proyecto. Sus 24 archivos intermedios se trasladaron a `work/` dentro de odd-lab. Allí se conservan borradores históricos y manifiestos de copia; la documentación vigente está en `outputs/`. Los borradores no deben reemplazar las versiones finales. `work/` permanece excluido de Git.

Antes de actuar en otra sesión, confirmar directorio, instrucciones aplicables, contenido actual y estado Git. Las condiciones del sistema de archivos y permisos dependen de la sesión; el resumen no concede permisos adicionales.

## Objetivo y alcance confirmado

Construir desde cero un sistema propio de agentes y subagentes para desarrollo de software, inspirado en **Organic Driven Development (ODD) de Gentleman Programming**.

Primer escenario: uso individual en repositorios empresariales, preparado conceptualmente para crecer a equipo. El sistema será mantenido y controlado por Carlos. No reutilizar ai-flow ni ai-mastery ni asumir sus contratos o requisitos.

**Gentle-AI y Engram están excluidos de la arquitectura.** Que existan instalados en la máquina no los convierte en dependencias. No desinstalarlos ni cambiar su configuración como parte de este trabajo.

Carlos pidió guardar el plan antes de implementar, preparar el contexto y después comenzar E-01. Esa entrega está implementada y validada, sin ampliar el alcance a E-02 ni conectar un runtime. La preparación está recogida en [preparación de implementación](preparacion-implementacion.md) y los resultados en [validación E-01](validacion-e01.md).

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
| Memoria | Propia; SQLite local como primer adaptador propuesto, más archivos de evidencia |
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

La especificación detallada de sintaxis, formato de salida, códigos, datos de `init` y ciclo de procesos está en [estructura y CLI](estructura-y-cli.md). Existe la raíz con `--help`, `--version` y ayuda sin argumentos; los comandos operativos y `--json` siguen pendientes.

## Estado real observado al guardar

- Hay cinco documentos de diseño, este resumen, preparación de implementación y validación E-01.
- Hay archivos de IntelliJ `.idea` y metadatos de macOS; preservarlos salvo petición posterior.
- Existen `pom.xml`, Wrapper Unix/Windows, dos clases Java de aplicación (`bootstrap` y `cli`), recursos y dos clases de pruebas. No se han creado capas vacías.
- Repositorio Git local inicializado en `main` por petición de Carlos. La documentación se versiona junto al `.gitignore`; consultar el historial al retomar.
- `clean verify` con Wrapper Maven 3.9.16 y Temurin 25.0.2: 3 pruebas de composición y 10 de integración del JAR, todas superadas. Resultado: `target/odd-lab-0.1.0-SNAPSHOT.jar`.
- No hay memoria propia creada ni runtime conectado.
- Se han descargado Maven y dependencias de construcción en cachés locales. No se han cambiado instalaciones ni preferencias globales; Windows no probado.

Las versiones locales observadas anteriormente fueron Codex CLI `0.163.0-alpha.11.2`, Gentle-AI `3.7.0` y Engram `1.15.0`. Son un inventario histórico, no una dependencia ni confirmación de estado actual.

## Plan de ejecución pendiente

- [x] **E-01:** esqueleto Maven, Java 25, composición Spring Boot y raíz Picocli con ayuda y versión. Validada; véase [evidencia](validacion-e01.md).
- [ ] **E-02:** configuración del repositorio, política inicial y diagnóstico local.
- [ ] **E-03:** almacenamiento transaccional propio, estados y comandos consultivos.
- [ ] **E-04:** `run` y `resume` con runtime simulado, evidencia y decisiones pendientes.
- [ ] **E-05:** control de procesos, pausa/cancelación, propiedad y recuperación tras caída.
- [ ] **E-06:** adaptador Codex y pruebas reales de protocolo, capacidades e aislamiento.
- [ ] **E-07:** verificación integrada y comparación del piloto CSV.

E-01 mantiene el alcance pequeño: ayuda y versión sin repositorio ni runtime, errores de uso consistentes y cierre comprobado. Siguiente entrega: E-02, configuración y política inicial, `init` y `doctor`; todavía no implementada.

Maven 3.9.16 y Wrapper 3.3.4 están generados y validados con Temurin 25.0.2. Pendientes técnicos por entrega: actualización/distribución empresarial del JDK 25, bibliotecas de persistencia/JSON, migraciones, autenticación de Codex y detalles efectivos de sandbox. Consultar fuentes oficiales actuales antes de fijar dependencias; conservar las versiones ya elegidas salvo incompatibilidad demostrada y explicada.

## Preparación del arranque

Los seis documentos vigentes se han revisado. [Preparación de implementación](preparacion-implementacion.md) consolida el contexto, registra el entorno y concreta E-01 con criterios verificables. Se añade `AGENTS.md` como guía local. El cambio previo del usuario sobre los borradores trasladados a `work/` se conserva.

Atención al JDK: `java` resuelve OpenJDK 26.0.1, mientras `mvn -version` usa Java 25.0.2 de Homebrew. E-01 se ha construido y ejecutado seleccionando Temurin 25.0.2 explícitamente, sin cambiar configuración global. E01-A a E01-G superados en macOS; evidencia y comandos en [validación E-01](validacion-e01.md).

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

> Trabaja en `/Users/carlos/dev/01-proyectos/odd-lab`. Lee `AGENTS.md`, `outputs/continuidad.md` y los contratos de la entrega. E-01 ya está implementada y validada; no la rehagas. Continúa con E-02: configuración del repositorio, política inicial, `init` y `doctor`. Comprueba antes el estado real. Mantén Java 25, Spring Boot 4.1.2, Picocli 4.7.7, coordinación y memoria propias; sin Gentle-AI ni Engram. No conectes un runtime ni amplíes a E-03. Actualiza estado y evidencia al terminar.
