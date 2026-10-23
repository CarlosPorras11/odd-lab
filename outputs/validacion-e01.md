# Validación de E-01

23 de octubre de 2026, Europe/Madrid. E-01 implementada y verificada en macOS arm64. Base Git `84be7df` más los cambios de esta entrega, sin commit automático. E-02 a E-07 pendientes.

## Construcción y entorno

- JDK de compilación y ejecución: Eclipse Temurin 25.0.2+10.
- Maven 3.9.16 mediante Wrapper Plugin 3.3.4, distribución `only-script` para Unix y Windows.
- Spring Boot 4.1.2, Picocli directo 4.7.7; versiones de plugins y dependencias de Boot heredadas de su parent fijado.
- `release=25`, UTF-8; JAR ejecutable `target/odd-lab-0.1.0-SNAPSHOT.jar`.
- Versión CLI: `odd-lab 0.1.0-SNAPSHOT`, obtenida de `META-INF/build-info.properties`, generado desde el POM.

Comandos ejecutados desde la raíz, con el JDK seleccionado explícitamente:

```sh
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw --version
./mvnw -B -ntp clean verify
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar --help
java -jar target/odd-lab-0.1.0-SNAPSHOT.jar --version
```

Resultado final de `clean verify`: **BUILD SUCCESS**; 13 pruebas, 0 fallos, 0 errores y 0 omitidas.

## Evidencia por criterio

| Criterio | Resultado observado | Evidencia |
| --- | --- | --- |
| E01-A | Maven 3.9.16, JDK 25, `clean verify` correcto | Salida del Wrapper y ciclo Maven; informes Surefire/Failsafe |
| E01-B | `--help` y `-h`: código 0, uso en stdout, stderr vacío | `PackagedCliIT.showsHelpWithoutRepositoryOrState` |
| E01-C | `--version` y `-V`: código 0 y versión exacta del POM; stderr vacío | `PackagedCliIT.showsVersionFromBuildMetadata` |
| E01-D | Sin argumentos: ayuda, código 0 y stderr vacío | `PackagedCliIT.showsHelpWithoutRepositoryOrState` |
| E01-E | Opción/subcomando desconocidos y `--json`: código 2, stdout vacío y diagnóstico en stderr | `PackagedCliIT.rejectsUnsupportedArguments` |
| E01-F | Invocaciones en directorios temporales vacíos, sin Git ni configuración; directorios siguen vacíos | Todas las invocaciones de proceso de `PackagedCliIT` |
| E01-G | Cada proceso termina antes de 20 s; cierre del contexto Spring en ayuda, versión y error | `PackagedCliIT` y `OddLabApplicationTest.closesSpringContextAndReturnsCliExitCode` |

Pruebas de composición: **3**, incluyendo contexto inactivo tras cierre y un log de diagnóstico forzado que llega a stderr dejando stdout limpio. Logging configurado mediante `logback-spring.xml`.

Pruebas de integración: **10**, incluyendo inspección de dependencias empaquetadas sin servidor web y rechazo de `--spring.main.web-application-type=servlet` como argumento CLI desconocido. Los argumentos de usuario no se pasan al parser de configuración Spring.

Informes locales regenerables: `target/surefire-reports/` y `target/failsafe-reports/`. El JAR y los informes están excluidos de Git; se regeneran con el comando anterior.

SHA-256 del JAR comprobado en esta entrega:

```text
807be664b573ffc9a8bc03bd4dc50f878c8e43b6785acce097617fc92192e007
```

Este hash identifica el artefacto probado. Una nueva construcción puede producir otro hash; no se afirma reproducibilidad binaria.

## Integridad del Wrapper

La distribución Maven descargada de Maven Central se contrastó con su SHA-512 publicado. Se calculó y fijó en `.mvn/wrapper/maven-wrapper.properties`:

```text
distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce
```

También se ejecutó `./mvnw --version` con `MAVEN_USER_HOME` en `.tooling/wrapper-validation`, inicialmente vacío: descarga y validación SHA-256 correctas, sin reutilizar la distribución de la caché global. `.tooling/` permanece excluido de Git.

Generación realizada con `org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16 -Dtype=only-script`; scripts oficiales sin modificaciones manuales. Referencias: [Maven Wrapper](https://maven.apache.org/tools/wrapper/), [plugin Spring Boot](https://docs.spring.io/spring-boot/maven-plugin/using.html) y [Picocli](https://picocli.info/).

## Límites y siguiente entrega

No hay memoria, coordinador, comandos operativos, conexión a Codex ni inferencia. Los criterios N-01 a N-12 y el piloto CSV siguen pendientes. No se ha validado Windows, aunque `mvnw.cmd` está incluido. No se han cambiado instalaciones globales ni realizado publicaciones o commits.

Siguiente entrega: E-02, configuración del repositorio, política inicial, `init` y `doctor`.
