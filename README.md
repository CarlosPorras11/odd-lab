# odd-lab

Sistema propio de agentes y subagentes para desarrollo de software, inspirado en Organic Driven Development de Gentleman Programming.

**Estado:** E-01 y E-02 implementadas y validadas: construcción, ayuda/versión, configuración, política local, `init` y `doctor` con salida humana o JSON. E-03 a E-07 pendientes. Java 25, Spring Boot 4.1.2 y Picocli 4.7.7. Memoria, coordinación y runtime todavía pendientes.

Para retomar el proyecto en otra conversación, leer primero [continuidad y plan resumido](outputs/continuidad.md). Incluye decisiones, estado comprobado y siguiente entrega: E-03.

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

Sin argumentos, el JAR muestra ayuda. Ayuda y versión devuelven 0; argumentos desconocidos devuelven 2 y diagnóstico por stderr. No requieren repositorio ni crean estado. Los comandos `init` y `doctor` están disponibles; ejecución de agentes y consultas sobre ejecuciones siguen pendientes.

`clean verify` ejecuta 21 pruebas de composición/configuración y 36 de integración sobre el JAR empaquetado: **57 pruebas**. Evidencia y límites: [validación E-01](outputs/validacion-e01.md) y [validación E-02](outputs/validacion-e02.md).

## Preparar y comprobar un repositorio

Sustituye la ruta del ejemplo por la raíz de un repositorio Git existente:

```sh
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar init --repo /ruta/al/repositorio
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar doctor --repo /ruta/al/repositorio --json
```

`init` crea `.odd-lab.yaml`, prepara cuatro directorios operativos y añade exclusiones específicas a `.gitignore`, conservando su contenido. Repetirlo conserva identidad y política existentes. `doctor` comprueba la preparación sin modificar archivos ni iniciar IA. La política inicial no habilita proveedores ni red.

En ambos comandos, `--repo` es obligatorio y `--json` devuelve un objeto final, incluidos errores de uso/configuración. Un diagnóstico local correcto no significa que exista memoria o un runtime: se muestran como pendientes. Formato, códigos y límites: [configuración y diagnóstico](outputs/configuracion-y-diagnostico.md).
