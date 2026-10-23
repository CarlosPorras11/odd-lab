# Piloto ODD: procesamiento de gastos CSV

23 de octubre de 2026 · Especificación y plan experimental; sin implementación.

Objetivo: validar primero el núcleo propio y después evaluar desarrollo directo, delegación adaptativa y paralelismo controlado sobre la misma tarea. Python con biblioteca estándar sigue siendo una propuesta para el pequeño producto CSV; la aplicación de agentes usará Java 25, Spring Boot 4.1.2, Picocli 4.7.7 y un runtime completo, con Codex como primera integración propuesta. Gentle-AI y Engram no participan en ninguna fase.

El escenario confirmado es uso individual en repositorios empresariales, con posible crecimiento a equipo. Véanse [arquitectura propia](sistema-agentes-odd.md), [decisiones](decisiones-entorno-odd.md) y [contratos](contratos-sistema.md).

## Fase A — Núcleo propio sin inferencia real

Implementar posteriormente el registro, las políticas y un ejecutor simulado. Validar N-01 a N-12 de los contratos, indicando qué comprobaciones aún requieren procesos o adaptadores reales. Un ensayo simulado no cuenta como una ejecución con agentes de IA.

Repetir caídas controladas entre intención, efecto y registro de resultado. Comprobar reconciliación, cancelación, exclusión de un segundo coordinador y reconstrucción de vistas Markdown desde el estado propio. La memoria pertenece a la aplicación y cada ensayo usa datos nuevos.

## Fase B — Integración de un motor autorizado

Comprobar el adaptador Java de Codex App Server con capacidades declaradas, política de datos aplicable y restricciones efectivas del runtime. Solo después conectar un motor de IA. Verificar límites, detención, consumos disponibles y que las acciones no eluden políticas. No se envía código empresarial durante el piloto inicial: se usan los datos sintéticos del CSV.

Cambiar a un segundo adaptador simulado demuestra independencia del contrato y del estado, no compatibilidad real con un segundo proveedor. Esa compatibilidad exige una prueba adicional con ese proveedor.

## Fase C — Comparación del producto CSV

Las especificaciones S1–S6 siguientes describen el producto de prueba. La comparación se hace sobre el coordinador y memoria propios, no sobre coordinación nativa de un proveedor.

## Specs

### S1 — Entrada

El producto será una CLI que lea un archivo CSV UTF-8 con cabecera exacta `fecha,categoria,importe`, delimitador coma y campos entrecomillados según las reglas habituales del formato. Admitirá finales de línea LF y CRLF y un BOM UTF-8 inicial.

La fecha debe existir y seguir `AAAA-MM-DD`. La categoría se recorta en los extremos y debe quedar no vacía; distingue mayúsculas. El importe admite signo opcional, parte entera obligatoria y cero, una o dos cifras decimales separadas por punto. No admite miles, exponente, NaN ni infinito. Se permiten importes negativos y cero.

### S2 — Resultado

Salida JSON UTF-8 por stdout con claves `totales` y `rechazos`. Cada total contiene `categoria` e `importe`, expresado como cadena decimal con dos cifras, sin coma flotante. Las categorías se ordenan por puntos de código Unicode, sin depender de la configuración regional.

Cada rechazo contiene `registro` y `motivos`. El número identifica el registro lógico de datos empezando por 1; no es una línea física, porque un campo CSV puede ocupar varias líneas. Los motivos se ordenan por campo: fecha, categoría, importe. Los códigos son `FECHA_INVALIDA`, `CATEGORIA_VACIA`, `IMPORTE_INVALIDO`; un número incorrecto de campos produce solo `COLUMNAS_INVALIDAS`.

Cada registro rechazado se excluye de los totales. Los rechazos conservan el orden de entrada. JSON estable e indentado con dos espacios y salto de línea final. El archivo de entrada no se modifica.

### S3 — Errores y códigos de salida

| Situación | Código | stdout | stderr |
| --- | --- | --- | --- |
| Procesado sin rechazos | 0 | JSON | Vacío |
| Procesado con rechazos | 1 | JSON | Vacío |
| Archivo ausente, ilegible, vacío sin cabecera, UTF-8 inválido, cabecera distinta o CSV estructuralmente malformado | 2 | Vacío | Código de error y salto de línea |

Los códigos de stderr serán respectivamente `ARCHIVO_NO_ENCONTRADO`, `LECTURA_FALLIDA`, `CABECERA_INVALIDA`, `CODIFICACION_INVALIDA` y `CSV_INVALIDO`. Archivo vacío y cabecera distinta comparten `CABECERA_INVALIDA`. Los errores de uso de CLI devuelven 2 y `USO_INVALIDO`. Un fallo fatal no devuelve resultados parciales.

Los registros vacíos intermedios cuentan como registros con columnas incorrectas; el salto de línea final por sí solo no añade un registro. Un archivo que solo contiene la cabecera devuelve listas vacías y código 0.

### S4 — Ejemplo central

Entrada:

```csv
fecha,categoria,importe
2026-10-01,Comida,12.30
2026-10-02,Transporte,5.00
2026-10-03,Comida,-2.10
2026-10-04,Comida,0.10
2026-10-05,Comida,0.20
2026-10-06, ,4.00
```

Resultado: código 1; totales `Comida = "10.50"` y `Transporte = "5.00"`; rechazo del registro 6 con `CATEGORIA_VACIA`; stderr vacío y hash del archivo de entrada intacto.

### S5 — Conjunto común de aceptación

Antes de las ejecuciones se fijarán casos y resultados para: ejemplo S4; decimal exacto 0.10 + 0.20; negativos y cero; categorías repetidas; categoría entrecomillada con coma; CRLF y BOM; solo cabecera; archivo vacío; cabecera incorrecta; fecha inexistente; categoría vacía; importes prohibidos; varios motivos en un registro; campos de más o de menos; registro vacío; UTF-8 inválido; comillas sin cerrar; archivo inexistente y error de uso.

Las comprobaciones de errores fatales también verificarán stdout vacío. Las de éxito y rechazo verificarán orden, código de salida, stderr y conservación de la entrada. No se escribirán todavía tests ni fixtures ejecutables.

### S6 — Encargos independientes

Primero se fija un contrato interno: lectura devuelve registros válidos normalizados y rechazos; agregación recibe registros válidos; presentación recibe totales y rechazos. Los errores fatales se propagan al punto de entrada.

En modalidad paralela: trabajador A implementa lectura y validación; trabajador B implementa agregación y presentación contra el contrato. El principal prepara o integra el punto de entrada y verifica el conjunto. Las zonas de escritura se asignan antes de delegar.

## Tasks

Estas son tareas de ejecución futura; no se marcan completas por haber redactado el diseño.

| ID | Specs | Trabajo | Evidencia exigida |
| --- | --- | --- | --- |
| T1 | S1–S6 | Preparar repositorio, comando público y contrato interno | Revisión base e interfaces documentadas |
| T2 | S1–S5 | Fijar casos de aceptación comunes | Entradas y salidas esperadas versionadas |
| T3 | S1–S6 | Construir una ejecución por modalidad | Cambios, decisiones de ruta y comprobaciones |
| T4 | S1–S5 | Verificar cada entrega sobre su revisión final | Resultado por caso y hash de entrada |
| T5 | S6 | Comprobar recuperación en ensayo aparte | Reanudación sin perder requisitos ni duplicar cambios |
| T6 | S1–S6 | Comparar resultados | Informe con medidas disponibles y límites |

## Diseño experimental

Modalidades: A, un agente sin subagentes; B, principal que delega solo cuando puede justificarlo; C, dos trabajadores para S6, como variante deliberada de paralelismo.

Primero realizar una ejecución exploratoria por modalidad y corregir defectos del procedimiento. Después congelar criterios y realizar tres repeticiones medidas por modalidad. Los cambios del procedimiento no se mezclarán silenciosamente con las mediciones anteriores.

Orden medido propuesto: A-B-C, B-C-A y C-A-B. Cada ejecución parte de la misma revisión inicial con sesiones y memoria específicas. Ninguna lee código generado por otra. Los criterios comunes son conocidos; no se simula una evaluación ciega.

Registrar tiempo de extremo a extremo, criterios superados al primer intento y al final, reparaciones, intervenciones humanas, conflictos de integración, número de delegaciones y consumo informado. Incluir tiempo y consumo del principal. Un dato no expuesto por el entorno se marca `NO_DISPONIBLE`.

Comparar medianas y rango observado. Tres repeticiones ofrecen una señal inicial, no significación estadística ni garantía para otros proyectos. La delegación adaptativa puede elegir trabajar directamente: esa elección también es un resultado válido.

El ensayo de recuperación se ejecuta aparte para no distorsionar tiempos: interrumpir tras una unidad comprobada, abrir una sesión limpia y recuperar documento, memoria, código y evidencias. Comprobar detección de vista Markdown antigua y su regeneración. Si el almacenamiento autoritativo no está disponible, detener nuevas operaciones con efectos y conservar evidencias recuperables; no inventar estado para continuar.

## Criterios de salida del piloto

- Todos los casos obligatorios superados por cada entrega declarada válida.
- Diferencias entre modalidades explicadas con datos disponibles.
- Ninguna escritura fuera del ámbito asignado ni modificación del archivo de entrada.
- Criterios del núcleo N-01 a N-12 informados con su evidencia y límites.
- Recuperación demostrada; si queda pendiente, el piloto de continuidad no se declara superado.
- Ausencia comprobada de dependencias operativas de Gentle-AI y Engram.
- Recomendación de conservar, ajustar o retirar delegación según resultados observados.

No hay resultados medidos ni se ha iniciado una ejecución.

## Log

L1 — Petición original: «Quiero crear un sistema de agentes de IA que me permita explotar la capacidad de agentes y subagentes, la idea es probar la metodología ODD».

L2 — Restricción: «no, crea uno de 0».

L3 — Alcance elegido: «Primero definir metodología y arquitectura, sin programar todavía».

L4 — Referencia corregida: «Con ODD me refiero al término que utiliza el youtuber Gentleman Programming.»

L5 — Continuación: «Perfecto, comienza con los siguientes pasos.» Se concretan decisiones y piloto dentro del alcance documental vigente.

L6 — Las reglas de CSV y la elección de Python son propuestas reversibles del diseño, no requisitos expresados por Carlos ni reglas de ODD.


L7 — Cambio de alcance arquitectónico: «Vale, respecto a Gentle AI y Engram, me gustaría sacarlo de la ecuación, ya que mi idea es diseñar algo similar a lo que tiene Gentleman Programming, pero de forma propia, ya que como va a ser a nivel empresarial, no quiero que sea un sistema externo a mí. ¿Me explico?»

L8 — Autorización del rediseño: «Vale, empecemos con el rediseño.»

L9 — Escenario elegido: «Uso individual en repositorios de empresa, con posibilidad de crecer a equipo».

L10 — El diseño sustituye coordinación y memoria externas por un núcleo propio; los requisitos CSV se conservan como ejemplo secundario. Se añade validación del núcleo antes de comparar rendimiento de agentes.


L11 — Elección de Carlos: «1. java
2. Runtime completo, como Codex». Se fija Java y la categoría de runtime completo; se propone Codex como primera integración. Se ajusta la frontera: las herramientas internas las ejecuta el runtime.


L12 — Base elegida por Carlos: «Hagamos lo con Java 25 Springboot 4.1.2 y cli piccoli 4.7.7». Se registra el nombre técnico de la biblioteca como Picocli. La elección fija el stack y mantiene el alcance documental; no se ha iniciado implementación.


L13 — Continuación: «Vale, pasemos al siguiente.» Se concreta la estructura Maven por capacidades y el contrato público de CLI en [estructura y CLI](estructura-y-cli.md), manteniendo el alcance de diseño.
