# Decisiones de arquitectura propia

23 de octubre de 2026 · Sustituye las decisiones anteriores basadas en Gentle-AI, Engram y coordinación nativa de Codex.

## ADR-001 — Núcleo de producto propio

**Decisión:** la aplicación será responsable de requisitos, tareas, decisiones, coordinación, políticas, memoria y evidencia. Se propone un monolito modular local para un usuario.

Motivo: Carlos quiere propiedad del sistema y uso inicial individual en repositorios empresariales. Una configuración de un producto ajeno no cubre ese objetivo.

Alternativas: componer Gentle-AI y Engram, descartado por la petición; construir una plataforma distribuida desde el inicio, aplazado hasta necesitar colaboración real.

Consecuencia: habrá que implementar y probar el ciclo operativo propio. La metodología ODD orienta el proceso, sin sustituir esas pruebas.

## ADR-002 — Memoria bajo control de la aplicación

**Decisión:** puerto de persistencia propio, con SQLite local como primer adaptador propuesto y archivos de evidencia fuera de la base.

La aplicación conserva intención, revisiones, tareas, eventos, decisiones y procedencia. La escritura del estado y su evento es transaccional; las vistas Markdown se regeneran. No se depende de Engram ni de la memoria interna del motor.

Se aplazan embeddings y búsqueda semántica. La primera recuperación usa identificadores, ámbitos y búsqueda textual suficiente para el piloto.

Consecuencia: hay que probar migraciones, transacciones, reconstrucción de vistas, recuperación e aislamiento de repositorios. La base local no será un mecanismo de colaboración compartido.

## ADR-003 — Coordinación y herramientas controladas

**Decisión:** nuestro coordinador autoriza encargos y configura restricciones; el runtime completo ejecuta herramientas. Las aprobaciones que exponga se resuelven con nuestras políticas. No se afirma que toda acción interna pase por nuestro ejecutor ni que un prompt imponga permisos.

Cada adaptador anuncia capacidades comprobables. Un runtime que escriba por fuera del ejecutor necesita aislamiento efectivo compatible con la política; si no puede demostrarlo, no se habilita en ese repositorio.

Consecuencia: la primera implementación debe tener una prueba de intento de escritura fuera del ámbito que el ejecutor rechace realmente.

## ADR-004 — Proveedores detrás de contratos

**Decisión del usuario:** usar un runtime completo. Primera integración propuesta: Codex detrás del contrato propio. Se mantienen nuestra memoria, metodología y coordinación global; el runtime conserva su bucle interno y herramientas.

La selección atenderá a política empresarial, control de herramientas, cancelación, aislamiento y consumo observable. No se presupone permiso para enviar código a un servicio externo. Credenciales y aprovisionamiento se resolverán cuando se elija el motor.

Un adaptador simulado permite comprobar mecánica sin inferencia. No cuenta como validación de agentes reales ni de un proveedor.

## ADR-005 — Primer alcance individual

**Decisión:** una instancia local, un usuario y un coordinador escritor por ejecución; dos subagentes concurrentes como máximo. Un proceso debe impedir que otra instancia se atribuya la misma ejecución sin reconciliación.

Las entidades incluyen actor y ámbito para facilitar evolución. La futura colaboración requerirá servicio compartido, autenticación, autorización y control de concurrencia; queda fuera del primer piloto.

## ADR-006 — Java y adaptador de runtime completo

**Decisión del usuario:** Java para la aplicación; runtime completo como tipo de motor. No se adopta un bucle propio basado en llamadas directas a un modelo.

**Propuesta de integración:** cliente Java del protocolo de Codex App Server por stdio. Según la [documentación oficial](https://learn.chatgpt.com/docs/app-server), el protocolo intercambia mensajes JSON y permite recibir eventos y gestionar solicitudes de aprobación. Evita introducir un componente TypeScript solo para acceder al SDK.

La ayuda de `codex app-server --help` se ha comprobado: anuncia stdio y marca App Server como experimental. No se ha iniciado el servidor ni conectado una sesión. Se fijará y verificará la versión y su esquema antes de implementar el adaptador. La documentación distingue métodos experimentales; no se habilitarán por defecto.

La viabilidad empresarial se comprobará con pruebas de sandbox, aprobaciones, cancelación y recuperación. Si la interfaz requerida no ofrece estabilidad suficiente, se revisará este transporte manteniendo Java y el contrato de runtime completo.

### Base Java confirmada

| Componente | Elección de Carlos | Uso previsto |
| --- | --- | --- |
| Java | 25 | Lenguaje y versión base de ejecución |
| Spring Boot | 4.1.2 | Composición y configuración en modo no web |
| Picocli | 4.7.7 | Argumentos, subcomandos, ayuda y códigos de salida |

Picocli se integra como biblioteca `info.picocli:picocli`. No se presupone compatibilidad de un starter adicional con Spring Boot 4.1.2; la composición se resolverá con las APIs de ambas bibliotecas y se verificará al implementar.

El dominio no depende de Spring ni de Picocli; los comandos invocan casos de uso. No se inicia un servidor HTTP en la primera CLI.

Se adopta Maven Wrapper con un único módulo; la [preparación de implementación](preparacion-implementacion.md) fija Maven 3.9.16 como versión utilizada; Wrapper 3.3.4 generado y comprobado en E-01. Quedan por concretar distribución y actualización de Java 25, construcción reproducible, dependencias del adaptador de persistencia y autenticación del runtime. La sintaxis inicial se concreta en el documento de estructura y CLI. Python sigue referido solo al producto CSV propuesto.

Referencias técnicas consultadas en la decisión: [Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html) y [Picocli](https://picocli.info/). La combinación se ha compilado y probado en E-01; véase [validación](validacion-e01.md).

## Límites de propiedad

| Elemento | Bajo control del proyecto | Dependencia posible |
| --- | --- | --- |
| Metodología y roles | Reglas, versiones y criterios propios | Referencia conceptual ODD |
| Coordinación y políticas | Código y contratos propios | Sistema operativo para imponer aislamiento |
| Memoria | Esquema, acceso, recuperación y exportación | Motor de almacenamiento sustituible |
| Inferencia | Contexto enviado y proveedores permitidos | Modelo local o servicio aprobado |
| Herramientas | Restricciones exigidas, aprobaciones expuestas y evidencia | Runtime, sandbox, Git y ejecutores del entorno |

Propiedad del sistema no significa ausencia de dependencias técnicas. Cada una tendrá función y límite definidos; las condiciones de uso empresarial se comprobarán al seleccionarla.

## Estado

- E-01 implementada y validada: POM, Wrapper, composición Spring no web y raíz Picocli con ayuda y versión. E-02 también implementada: configuración/política YAML y comandos `init`/`doctor` con JSON. E-03 a E-07 pendientes.
- Maven y dependencias descargados en cachés locales; sin cambios de instalaciones ni configuración global.
- No se ha creado memoria transaccional ni conectado un proveedor. E-02 prepara directorios locales, no una base de datos.
- Java 25, Spring Boot 4.1.2, Picocli 4.7.7 y runtime completo elegidos por Carlos. Codex App Server por stdio es la integración propuesta, pendiente de prueba.
- No se ha iniciado el runtime; el código actual cubre esqueleto E-01 y configuración/diagnóstico E-02.


## ADR-007 — Estructura y ciclo inicial de CLI

Se concreta la aplicación como un módulo Maven organizado por capacidades, con dominio y casos de uso independientes de Spring y Picocli. Spring compone; la CLI traduce argumentos; los adaptadores implementan puertos.

La primera versión ejecuta trabajo en primer plano. Consultas y solicitudes de pausa/cancelación desde otros procesos usan el registro local, con un único propietario que decide transiciones. No se introduce un daemon para el primer alcance.

Nombre de trabajo: `odd-lab`. Ayuda, versión, formato JSON, códigos de salida y criterios de cada comando se describen en [estructura y CLI](estructura-y-cli.md). Ayuda, versión, `init`, `doctor` y JSON en esos subcomandos están implementados; ejecución de agentes pendiente. Contrato concreto en [configuración y diagnóstico](configuracion-y-diagnostico.md).
