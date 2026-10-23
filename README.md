# odd-lab

Sistema propio de agentes y subagentes para desarrollo de software, inspirado en Organic Driven Development de Gentleman Programming.

**Estado:** E-01 a E-03 implementadas y validadas: construcción, configuración/política, registro transaccional SQLite y comandos `init`, `doctor`, `status`, `inspect` con salida humana o JSON. E-04 a E-07 pendientes. Java 25, Spring Boot 4.1.2 y Picocli 4.7.7. Ejecución de agentes y runtime todavía pendientes.

Para retomar el proyecto en otra conversación, leer primero [continuidad y plan resumido](outputs/continuidad.md). Incluye decisiones, estado comprobado y siguiente entrega: E-04.

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

Sin argumentos, el JAR muestra ayuda. Ayuda y versión devuelven 0; argumentos desconocidos devuelven 2 y diagnóstico por stderr. No requieren repositorio ni crean estado. Están disponibles `init`, `doctor`, `status` e `inspect`; crear y ejecutar peticiones mediante `run` sigue pendiente.

`clean verify` ejecuta 46 pruebas de composición/configuración/SQLite y 45 de integración sobre el JAR empaquetado: **91 pruebas**. Evidencia y límites: [validación E-01](outputs/validacion-e01.md), [validación E-02](outputs/validacion-e02.md) y [validación E-03](outputs/validacion-e03.md).

## Preparar y comprobar un repositorio

Sustituye la ruta del ejemplo por la raíz de un repositorio Git existente:

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar init --repo /ruta/al/repositorio
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar doctor --repo /ruta/al/repositorio --json
```

`init` crea `.odd-lab.yaml`, prepara cuatro directorios operativos y el registro SQLite v1, y añade exclusiones específicas a `.gitignore`, conservando su contenido. Repetirlo conserva identidad y política existentes. `doctor` comprueba la preparación sin modificar archivos ni iniciar IA. La política inicial no habilita proveedores ni red.

En ambos comandos, `--repo` es obligatorio y `--json` devuelve un objeto final, incluidos errores de uso/configuración. Doctor comprueba el registro SQLite en solo lectura; el runtime sigue como `NOT_CONFIGURED`. Un diagnóstico correcto no significa que exista ejecución de agentes. Formato, códigos y límites: [configuración y diagnóstico](outputs/configuracion-y-diagnostico.md).

## Consultar el registro

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar status --repo /ruta/al/repositorio --json
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar inspect --repo /ruta/al/repositorio --run UUID --json
```

Un proyecto nuevo muestra lista vacía. Para un proyecto preparado con E-02, repetir `init` crea la base que falta conservando su identidad. Las consultas no inicializan ni migran datos. La creación de ejecuciones mediante `run` llegará en E-04; las pruebas actuales usan registros sintéticos. Contrato: [registro y consultas](outputs/registro-y-consultas.md).
