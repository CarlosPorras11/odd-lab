# Preparación de implementación de ODD

23 de octubre de 2026. Contexto vigente recogido; E-01 a E-05 implementadas y validadas. E-06 y E-07 pendientes. Esta preparación concreta el arranque, sin sustituir los contratos existentes.

## 1. Orden de lectura y autoridad

| Fuente | Contexto que conserva |
| --- | --- |
| [Continuidad](continuidad.md) | Objetivo, decisiones cerradas, estado y siguiente entrega |
| [Arquitectura](sistema-agentes-odd.md) | Capacidades, roles, coordinación y frontera con el runtime |
| [Decisiones](decisiones-entorno-odd.md) | ADR-001 a ADR-007; tecnologías y alternativas descartadas |
| [Contratos](contratos-sistema.md) | Entidades, estados, recuperación y criterios N-01 a N-12 |
| [Estructura y CLI](estructura-y-cli.md) | Paquetes, comandos, salidas y entregas E-01 a E-07 |
| [Piloto](piloto-odd.md) | Fases A/B/C, producto CSV S1–S6 y comparación futura |

Las instrucciones actuales del usuario prevalecen. Ante contradicciones entre documentos, resolver y registrar la decisión antes de codificar el contrato afectado. Los borradores de `work/` son historia, no especificación vigente. No hace falta reabrirlos para comenzar E-01.

## 2. Contexto consolidado

- Producto: CLI local de desarrollo con agentes, inicialmente individual en repositorios empresariales; evolución a equipo posterior.
- Propiedad: metodología, coordinación global, políticas, memoria y evidencia propias. Sin Gentle-AI, Engram ni reutilización de ai-flow/ai-mastery.
- Stack cerrado: Java 25, Spring Boot 4.1.2 no web, `info.picocli:picocli:4.7.7`, un módulo Maven y JAR ejecutable.
- Arquitectura: capacidades primero; dominio y aplicación independientes del framework y runtime. No crear capas vacías.
- Motor: runtime completo; Codex App Server por stdio propuesto para E-06. La aplicación no intercepta necesariamente cada herramienta interna.
- Registro: datos y eventos transaccionales propios, SQLite como primer adaptador propuesto; artefactos verificables y Markdown regenerable.
- Ejecución futura: un propietario escritor, primer plano, principal y hasta dos subagentes activos, un intento inicial y dos correcciones como máximo dentro del presupuesto global.
- Seguridad y aceptación: permisos efectivos, ámbitos explícitos, evidencia ligada a revisión, reconciliación de efectos desconocidos; nunca aceptar solo una afirmación del agente.
- Piloto: núcleo simulado → runtime autorizado con datos sintéticos → comparación CSV. Python corresponde al producto CSV propuesto, no al sistema Java.

## 3. Entorno comprobado

Inventario histórico al preparar E-01, antes de implementar. Estado actual en [validación E-01](validacion-e01.md). Comprobaciones realizadas en `/Users/carlos/dev/01-proyectos/odd-lab`:

| Elemento | Observación |
| --- | --- |
| Git | Rama `main`; último commit observado `0671f7d` |
| Cambios al iniciar | `outputs/continuidad.md` modificado por el usuario; se conserva su párrafo sobre `work/` |
| Aplicación | Sin `pom.xml`, Wrapper ni fuentes Java; ninguna compilación o prueba realizada |
| Instrucciones | Sin `AGENTS.md` aplicable encontrado en la ruta de ancestros; se añade guía local en esta preparación |
| Java por defecto | OpenJDK 26.0.1; no coincide con la base elegida |
| JDK 25 disponible | Temurin 25.0.2+10 en `/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`; ejecutable comprobado |
| Maven global | 3.9.14; `mvn -version` usa otro JDK 25.0.2 de Homebrew |
| Runtime | Sin servidor iniciado ni protocolo probado durante esta preparación |

El inventario es una fotografía, no un requisito de rutas para otros equipos. No se han instalado herramientas ni modificado preferencias globales.

## 4. Construcción preparada

Se concreta **Maven 3.9.16** para el Wrapper: versión estable mantenida de la serie 3.9 según el [historial oficial de Maven](https://maven.apache.org/docs/history.html), consultado en esta preparación. E-01 ha generado Wrapper 3.3.4 `only-script`, fijado SHA-256 de la distribución y comprobado una descarga limpia. El Maven global solo se ha usado para generar el Wrapper.

La documentación de [Spring Boot 4.1.2](https://docs.spring.io/spring-boot/system-requirements.html) admite Java 25 y Maven desde 3.6.3. [Picocli](https://picocli.info/) publica la biblioteca directa 4.7.7. La combinación se ha compilado y ejecutado en E-01; los resultados están en [validación E-01](validacion-e01.md).

Usar el JDK existente para la validación inicial; la actualización de mantenimiento y distribución empresarial siguen pendientes. No fijar rutas personales dentro del POM.

Preparación de la terminal en este Mac, sin cambiar configuración global:

```sh
cd /Users/carlos/dev/01-proyectos/odd-lab
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

Durante E-01: fijar `release=25`, codificación UTF-8 y versiones exactas; componer Spring sin starter web y Picocli sin starter adicional. Propagar el código de Picocli al proceso y cerrar el contexto Spring. La versión mostrada debe proceder de los metadatos de construcción, evitando valores divergentes.

## 5. Entrega E-01 implementada y validada

**Resultado:** esqueleto pequeño y compilable, JAR ejecutable, raíz `odd-lab`, ayuda y versión sin requerir repositorio ni runtime.

Incluye POM, Wrapper para Unix/Windows, composición `bootstrap`, raíz `cli`, recursos mínimos y pruebas del contrato público. No incluye comandos de proyecto, almacenamiento, proveedores, credenciales ni fixtures CSV. `--json` y su contrato se incorporarán con los comandos operativos; E-01 no lo anunciará como disponible.

Comportamiento concretado para este esqueleto: invocación sin argumentos muestra ayuda y termina con 0. Opciones o subcomandos desconocidos terminan con 2. Ayuda y versión escriben en stdout; errores de uso en stderr. Sin banners ni logs de arranque en stdout.

| Criterio | Comprobación exigida | Estado |
| --- | --- | --- |
| E01-A | Wrapper resuelve Maven 3.9.16 y construcción con JDK 25 supera `clean verify` | Superado en macOS |
| E01-B | JAR con `--help`: código 0, uso legible, stderr vacío | Superado en macOS |
| E01-C | JAR con `--version`: código 0, versión coherente con construcción, stderr vacío | Superado en macOS |
| E01-D | JAR sin argumentos: ayuda y código 0 | Superado en macOS |
| E01-E | Opción y subcomando desconocidos: código 2, diagnóstico en stderr | Superado en macOS |
| E01-F | Invocación desde directorio temporal sin Git/configuración: ayuda y versión funcionan y no crean estado | Superado en macOS |
| E01-G | Proceso real termina en tiempo acotado; contexto cerrado y sin servidor HTTP ni procesos runtime | Superado en macOS |

Después de crear el Wrapper se ejecutará `./mvnw --version` y `./mvnw clean verify` con el JDK seleccionado. Se probará también el JAR empaquetado mediante procesos reales, capturando salidas, código y tiempo de terminación. El script Windows se versiona; no se declarará probado en Windows sin ejecutarlo allí.

## 6. Secuencia y criterios del núcleo

| Entrega | Dependencia | Resultado y aceptación principal |
| --- | --- | --- |
| E-01 | Preparación actual | Construcción, ayuda, versión y cierre; matriz anterior |
| E-02 | E-01 | Configuración/política versionada, `init` y `doctor`; preservación de archivos y diagnóstico sin IA |
| E-03 | E-02 | Estado/eventos transaccionales, ámbitos y consultas; N-05 y N-09 en el registro propio |
| E-04 | E-03 | `run`/`resume` simulados, decisiones y evidencia; N-02, N-08, N-10 y N-12 en simulación |
| E-05 | E-04 | Propiedad, control y recuperación; N-03, N-04, N-06 y N-07 con procesos reales |
| E-06 | E-05 | Adaptador Codex; esquema, capacidades, políticas y aislamiento reales; N-01, N-07, N-08 y N-11 |
| E-07 | E-06 | Regresión N-01 a N-12 y comparación CSV S1–S6 sobre revisiones verificadas |

Esta asignación orienta la implementación; no sustituye escenarios ni evidencia exigida por los contratos. N-01 se ejercitará antes con ejecutor local cuando exista; la garantía del proveedor solo se cierra en E-06. N-11 requiere primero límites del coordinador y después concurrencia real aislada.

Configuración y JSON resueltos en E-02. E-03 concreta SQLite y migración v1. E-05 concreta bloqueo OS y detección de procesos sintéticos; pendientes en E-06: versión/esquema de Codex, autenticación y sandbox. Ninguno exige ampliar E-01.

## 7. Estado de salida de esta preparación

- [x] Leer los seis documentos vigentes y recoger decisiones y límites.
- [x] Confirmar Git, ausencia de implementación y JDK 25 disponible.
- [x] Identificar diferencia de JDK y preparar selección explícita por terminal.
- [x] Concretar Maven objetivo y aceptación de E-01; añadir guía local de trabajo.
- [x] Implementar y validar E-01; 13 pruebas superadas y JAR ejecutable comprobado.
- [x] Implementar y validar E-02; contrato en [configuración y diagnóstico](configuracion-y-diagnostico.md).
- [x] Implementar y validar E-03; contrato en [registro y consultas](registro-y-consultas.md).
- [x] Implementar y validar E-04; contrato en [ejecución simulada](ejecucion-simulada.md).
- [x] Implementar y validar E-05; [control y recuperación](control-y-recuperacion.md), con procesos Java sintéticos.
- [ ] Implementar y validar E-06 y E-07, de forma incremental.

Siguiente trabajo: E-06, adaptador real autorizado y validación de capacidades/aislamiento. E-05 valida propiedad, pausa/cancelación y recuperación del trabajador sintético; [evidencia](validacion-e05.md). E-04 acredita mecánica local de simulación, evidencia y decisiones; no acredita proveedores reales ni ejecución en Windows; la validación de procesos locales se añade en E-05.
