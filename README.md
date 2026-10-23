# odd-lab

Sistema propio de agentes y subagentes para desarrollo de software, inspirado en Organic Driven Development de Gentleman Programming.

**Estado:** E-01 implementada y validada: Maven Wrapper, composición Spring y CLI con ayuda y versión. E-02 a E-07 pendientes. Java 25, Spring Boot 4.1.2 y Picocli 4.7.7. Memoria, coordinación e integración del runtime todavía pendientes.

Para retomar el proyecto en otra conversación, leer primero [continuidad y plan resumido](outputs/continuidad.md). Incluye decisiones, estado comprobado y siguiente entrega: E-02.

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

Sin argumentos, el JAR muestra ayuda. Ayuda y versión devuelven 0; argumentos desconocidos devuelven 2 y diagnóstico por stderr. No requieren repositorio ni crean estado. Los comandos `init`, `doctor`, `run` y demás comandos operativos, incluida salida `--json`, siguen pendientes.

`clean verify` ejecuta 3 pruebas de composición/cierre de Spring y 10 de integración sobre el JAR empaquetado. Evidencia y límites: [validación E-01](outputs/validacion-e01.md).
