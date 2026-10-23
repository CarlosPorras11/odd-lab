# Guía de trabajo de odd-lab

## Contexto y fuentes

- Leer `outputs/continuidad.md` y `outputs/preparacion-implementacion.md` antes de trabajar; después, los contratos de la entrega afectada.
- Diseño vigente en `outputs/`: arquitectura, decisiones, contratos, estructura/CLI y piloto. `work/` contiene borradores históricos excluidos de Git; no reemplazan las versiones finales.
- Confirmar directorio y estado Git. Conservar cambios del usuario y archivos de IDE. No crear commits ni publicar por inferencia.
- Estado actual: E-01 a E-04 implementadas y validadas; E-05 a E-07 pendientes. Evidencia en `outputs/validacion-e01.md`, `outputs/validacion-e02.md`, `outputs/validacion-e03.md` y `outputs/validacion-e04.md`. Consultar el estado actualizado antes de actuar.

## Decisiones de implementación

- Sistema propio inspirado en ODD de Gentleman Programming; no reutilizar ai-flow ni ai-mastery. Gentle-AI y Engram están excluidos; no modificar sus instalaciones.
- Java 25, Spring Boot 4.1.2 no web, Picocli 4.7.7 como biblioteca directa. Un módulo Maven con Wrapper; preparación de construcción en `outputs/preparacion-implementacion.md`.
- Paquete base provisional `dev.oddlab`; capacidades primero. Crear `domain`, `application`, `infrastructure` solo con contenido real.
- Dominio y casos de uso independientes de Spring, Picocli, JDBC y protocolo Codex. Spring compone; CLI traduce; adaptadores implementan puertos.
- Coordinación, políticas, memoria, estado y evidencia propios. Runtime completo detrás de un puerto; Codex App Server por stdio es propuesta de E-06, pendiente de validación.
- E-02 incorpora configuración YAML y salida JSON con SnakeYAML/Jackson gestionados por Boot. Contrato en `outputs/configuracion-y-diagnostico.md`. No conectar proveedores ni introducir inferencia directa. E-03 incorpora SQLite JDBC 3.53.4.0 detrás de `RunJournal`; contrato en `outputs/registro-y-consultas.md`. No modificar migraciones ya aplicadas; añadir versiones nuevas cuando corresponda. Consultas siempre en modo de solo lectura, sin inicialización implícita. E-04 incorpora `run`/`resume` con selección explícita `--runtime simulated`; contrato en `outputs/ejecucion-simulada.md`. No confundir aceptación del circuito sintético con implementación de la petición; no conectar proveedores ni ampliar a E-05 por inferencia.

## Ejecución y evidencia

- Trabajar por entregas E-01 a E-07. Una petición de preparación no equivale a implementar todas las entregas.
- Explorar antes de editar. Trabajo directo por defecto; delegaciones solo con justificación y autorización aplicable. Los límites del producto diseñado no autorizan lanzar subagentes en esta sesión.
- Pruebas sobre comportamiento observable: salida, código de proceso, cierre y efectos. No declarar una entrega completa sin evidencia.
- Simulación valida mecánica; no demuestra seguridad, cancelación o compatibilidad real de un proveedor.
- Un prompt o worktree no impone aislamiento. Nunca declarar detención confirmada con efectos desconocidos.
- Actualizar README, continuidad y criterios de la entrega al terminar, distinguiendo diseño, implementación y verificación. No marcar R-04d completo solo por fijar Maven: incluye validación futura del protocolo.
- Consultar fuentes oficiales al añadir dependencias. Seleccionar JDK 25 explícitamente para construir y ejecutar; no cambiar configuración global de la máquina.
