# odd-lab

Sistema propio de agentes y subagentes para desarrollo de software, inspirado en Organic Driven Development de Gentleman Programming.

**Estado:** E-01 a E-05 implementadas y validadas: construcción, configuración, SQLite, consultas y `run`/`resume` sintéticos, evidencia, decisiones y control/recuperación con procesos Java reales. E-06 y E-07 pendientes. Java 25, Spring Boot 4.1.2 y Picocli 4.7.7. Sin proveedores reales ni ejecución de código solicitada por agentes.

Para retomar el proyecto en otra conversación, leer primero [continuidad y plan resumido](outputs/continuidad.md). Incluye decisiones, estado comprobado y siguiente entrega: E-06.

Documentación: [arquitectura](outputs/sistema-agentes-odd.md), [decisiones](outputs/decisiones-entorno-odd.md), [contratos](outputs/contratos-sistema.md), [estructura y CLI](outputs/estructura-y-cli.md) y [piloto](outputs/piloto-odd.md).

Arranque: [preparación de implementación](outputs/preparacion-implementacion.md), con entorno comprobado, Maven objetivo, criterios de aceptación y secuencia de entregas. [AGENTS.md](AGENTS.md) conserva las instrucciones de trabajo del repositorio.

## Compilar y ejecutar

Requiere JDK 25. El Wrapper descarga Maven 3.9.16 con checksum fijado; la primera compilación necesita acceso a Maven Central. En este Mac, seleccionar el JDK instalado para esta terminal:

```sh
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw clean verify
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar --help
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar --version
```

En otros equipos, ajustar `JAVA_HOME` al JDK 25 local. Windows dispone de `mvnw.cmd`; no se ha ejecutado en Windows.

Sin argumentos, el JAR muestra ayuda. Ayuda y versión devuelven 0; argumentos desconocidos devuelven 2 y diagnóstico por stderr. No requieren repositorio ni crean estado. Están disponibles `init`, `doctor`, `status`, `inspect`, `run`, `resume`, `pause` y `cancel`. Los perfiles `simulated` y `process` son sintéticos, sin IA.

`clean verify` ejecuta 86 pruebas de núcleo y 62 de integración sobre el JAR empaquetado: **148 pruebas**. Evidencia y límites: [validación E-01](outputs/validacion-e01.md), [validación E-02](outputs/validacion-e02.md), [validación E-03](outputs/validacion-e03.md), [validación E-04](outputs/validacion-e04.md) y [validación E-05](outputs/validacion-e05.md).

## Preparar y comprobar un repositorio

Sustituye la ruta del ejemplo por la raíz de un repositorio Git existente:

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar init --repo /ruta/al/repositorio
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar doctor --repo /ruta/al/repositorio --json
```

`init` crea `.odd-lab.yaml`, prepara cuatro directorios operativos y el registro SQLite v1, y añade exclusiones específicas a `.gitignore`, conservando su contenido. Repetirlo conserva identidad y política existentes. `doctor` comprueba la preparación sin modificar archivos ni iniciar IA. La política inicial no habilita proveedores ni red.

En ambos comandos, `--repo` es obligatorio y `--json` devuelve un objeto final, incluidos errores de uso/configuración. Doctor comprueba el registro SQLite en solo lectura; el simulador aparece disponible y los proveedores reales siguen como `NOT_CONFIGURED`. Un diagnóstico correcto no significa que exista ejecución de agentes. Formato, códigos y límites: [configuración y diagnóstico](outputs/configuracion-y-diagnostico.md).

## Consultar el registro

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar status --repo /ruta/al/repositorio --json
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar inspect --repo /ruta/al/repositorio --run UUID --json
```

Un proyecto nuevo muestra lista vacía. Para un proyecto preparado con E-02, repetir `init` crea la base que falta conservando su identidad. Las consultas no inicializan ni migran datos. `verificationStatus` distingue evidencia vigente, obsoleta o inválida sin alterar el estado histórico. Contrato: [registro y consultas](outputs/registro-y-consultas.md).

## Probar el circuito simulado

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar run --repo /ruta/al/repositorio --request-file examples/requests/simulation.md --runtime simulated --json
```

El resultado `COMPLETED` verifica un recibo sintético ligado a petición/revisión/intento; **no significa que se haya implementado la petición original**. La petición se conserva literalmente y no se ejecuta como órdenes.

Añade `--scenario retry`, `failure`, `unverified`, `decision` o `permission` para probar correcciones, fallos y decisiones. En `decision`, el comando devuelve 3 y conserva `pendingDecision`; continúa con `resume --repo /ruta/al/repositorio --run UUID` desde Terminal, sin `--json`, para responder `s` o `n`. Con JSON nunca pregunta ni autoriza automáticamente.

`resume` de una simulación completada vuelve a comprobar evidencias sin repetir el runtime. Cambios de código/política, artefactos alterados o intentos de cierre desconocido impiden declarar éxito vigente. Alcance, códigos y límites: [ejecución simulada](outputs/ejecucion-simulada.md).

## Controlar procesos y recuperar una ejecución

Para probar E-05, usa `--runtime process --process-seconds 30` al iniciar una petición: lanza únicamente un trabajador Java sintético y su hijo. Desde otra terminal, consulta el UUID con `status` y solicita:

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar pause --repo /ruta/al/repositorio --run UUID --wait-seconds 5 --json
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar resume --repo /ruta/al/repositorio --run UUID --json
```

Para cancelar otra ejecución activa, usa `cancel` en lugar de `pause`. Una solicitud confirmada devuelve 0; el propietario detenido devuelve 4. Sin confirmación devuelve 5 y conserva la solicitud. Si murió el propietario, `resume` reconcilia procesos antes de continuar; nunca roba un bloqueo vivo ni repite un intento desconocido. `inspect` muestra solicitudes, identidades y confirmaciones.

Solo un propietario modifica una ejecución. La comprobación de cierre usa PID y fecha de inicio; los permisos para observar y detener procesos son necesarios. Esto valida procesos locales sintéticos, sin certificar el sandbox de un proveedor. Contrato y pruebas manuales: [control y recuperación E-05](outputs/control-y-recuperacion.md).
