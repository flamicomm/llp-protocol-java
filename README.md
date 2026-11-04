# Protocolo LLP — Implementación en Java

[![Maven Package](https://github.com/flamicomm/llp-protocol-java/actions/workflows/maven-publish.yml/badge.svg)](https://github.com/flamicomm/llp-protocol-java/actions/workflows/maven-publish.yml)
[![Maven Verify](https://github.com/flamicomm/llp-protocol-java/actions/workflows/maven-verify.yml/badge.svg)](https://github.com/flamicomm/llp-protocol-java/actions/workflows/maven-verify.yml)
[![Licencia: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-blue)](https://www.oracle.com/java/)
[![Wire 4.0.0](https://img.shields.io/badge/Wire-4.0.0-blue)](https://github.com/flamicomm/llp-spec/blob/main/docs/SPEC-v4.md)
[![codecov](https://codecov.io/github/flamicomm/llp-protocol-java/graph/badge.svg?token=BNZ9MLIS2Z)](https://codecov.io/github/flamicomm/llp-protocol-java)

Implementación en **Java 21** de **LLP (Lightweight Link Protocol)** wire format
**4.0.0**: framing sobre enlaces de bytes con errores (UART, RS-485, RF, TCP,
Bluetooth), con CRC16-CCITT, *byte stuffing*, canal de enrutamiento y timeout
entre bytes.

La especificación normativa, los vectores de conformidad y la implementación de
referencia viven en **[llp-spec](https://github.com/flamicomm/llp-spec)**. Esta
librería es la implementación Java y **no** define el wire.

---

## 🚀 Características

* **Ninguna trama corrupta llega a la aplicación:** el payload se entrega solo
  después de validar el CRC.
* **Reensamblado rápido:** tras el ruido, la próxima trama aparece en pocas
  decenas de bytes.
* **Canal por trama:** un byte de enrutamiento, sin metadatos ni capas.
* **Tiempo inyectado:** el parser recibe `nowMs`; el timeout es determinista y
  wrap-safe a 16 bits (máximo 32767 ms).
* **Cero asignación por byte** en el camino caliente y **cero dependencias de
  runtime**.
* **Buffers del llamador:** el parser escribe en tu `byte[]` y el encoder
  escribe en un `Sink`, así que ninguno necesita un buffer del tamaño de la trama.
* **Agnóstico al transporte:** UART, TCP, RS-485, radio, lo que entregue bytes.

---

## 📋 Requisitos

* **Java:** 21 o superior
* **Maven:** 3.6 o superior

---

## 📦 Instalación

```xml
<dependency>
    <groupId>com.flamingo</groupId>
    <artifactId>llp-core</artifactId>
    <version>4.0.0</version>
</dependency>
```

### Autenticación en GitHub Packages

La librería se publica en GitHub Packages y requiere un token con `read:packages`.

**1.** Creá el token en GitHub → Settings → Developer settings → Personal access tokens.

**2.** Configurá `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>github</id>
        <username>TU_USUARIO</username>
        <password>TU_TOKEN</password>
    </server>
</servers>
```

**3.** Agregá el repositorio:

```xml
<repositories>
    <repository>
        <id>github</id>
        <name>GitHub Packages</name>
        <url>https://maven.pkg.github.com/flamicomm/llp-protocol-java</url>
    </repository>
</repositories>
```

---

## 🏃 Inicio rápido

### Enviar una trama

```java
import com.flamingo.comm.llp.LLP;

byte[] payload = "G1 X10\n".getBytes(StandardCharsets.US_ASCII);
byte[] frame = LLP.encode(1, payload);      // canal 1
// frame = aa 55 07 01 47 31 20 58 31 30 0a e4 27
serial.write(frame);
```

### Recibir tramas (streaming)

```java
import com.flamingo.comm.llp.*;

LLPIncrementalParser receiver = LLP.incrementalParser()
        .capacity(LLP.MAX_CAPACITY)   // 255
        .timeoutMs(2000)
        .build();

int b;
while ((b = in.read()) != -1) {
    receiver.feed((byte) b);

    for (LLPFrame frame : receiver.pollFrames()) {
        System.out.println("canal " + frame.channel()
                + " payload " + new String(frame.payloadBytes(), StandardCharsets.UTF_8));
    }
    for (LLPError error : receiver.pollErrors()) {
        System.err.println(error.wireName() + ": " + error.description());
    }
}
```

### Parser byte a byte (espejo de la API C)

```java
LLPParser parser = LLP.parser().capacity(255).timeoutMs(2000).build();

for (byte b : chunk) {
    switch (parser.feed(b, nowMs)) {
        case FeedResult.Frame f -> handle(parser.channel(), parser.payload());
        case FeedResult.Error e -> log(e.value());
        case FeedResult.None n  -> { }
    }
}
```

### Encoder sin buffer de trama

```java
LLPEncoder encoder = new LLPEncoder(channel, payloadLength, port::write);
for (byte b : generatedPayload()) {
    encoder.payloadByte(b);        // stuffing + CRC en el camino
}
encoder.end();
```

---

## 📦 Estructura de la trama

```
 offset  0      1      2      3        4 .. 3+LEN      4+LEN  5+LEN
        +------+------+------+------+----------------+------+------+
        | 0xAA | 0x55 | LEN  | CHAN |    PAYLOAD     | CRC_L| CRC_H|
        +------+------+------+------+----------------+------+------+
          magia (nunca stuffed)     LEN bytes           little-endian
```

| Campo | Tamaño | Descripción |
| --- | --- | --- |
| Magic | 2 | `0xAA 0x55`, nunca stuffed |
| LEN | 1 | Longitud del payload, `0..255` |
| CHANNEL | 1 | Etiqueta de enrutamiento, `0..255` |
| PAYLOAD | `LEN` | Bytes opacos de la aplicación |
| CRC16 | 2 | CRC16-CCITT-FALSE sobre `magic + LEN + CHANNEL + PAYLOAD`, LE |

Todo byte desde `LEN` en adelante se *stuffea*: `0xAA` se envía como `0xAA 0x00`.
`0xAA 0x55` dentro de una trama es un **resync**; `0xAA` seguido de otra cosa es
un **error de sincronización**. `LEN == 0` es válido.

| | Bytes |
| --- | --- |
| Trama mínima (payload vacío, sin stuffing) | 6 |
| Trama con `n` bytes sin `0xAA` | `6 + n` |
| Peor caso | `10 + 2n` (`LLP.frameSize(n)`) |

## 📡 Canales

`CHANNEL` es opaco para el protocolo. Una convención que funciona:

| Canal | Uso sugerido |
| ---: | --- |
| `0` | stream de comandos de texto por defecto |
| `1` | comandos binarios |
| `2` | telemetría |
| `3` | logs |
| `4..239` | definido por la aplicación |
| `240..255` | reservado |

El ruteo por canal es responsabilidad de la aplicación: el core entrega
`(channel, payload)` y vos decidís a quién se lo das. Hay un ejemplo de
dispatcher de canales en [`examples/llp-serial-test`](examples/llp-serial-test).

## ⚠️ Errores

| Enum | Código | Causa | Recuperación |
| --- | ---: | --- | --- |
| `LLPError.CRC` | 1 | CRC inválido en una trama completa | trama descartada |
| `LLPError.LEN` | 2 | `LEN` mayor que la capacidad del parser | rechazada al leer `LEN` |
| `LLPError.TIMEOUT` | 3 | gap entre bytes mayor que `timeoutMs` | el byte se reexamina como magia |
| `LLPError.SYNC` | 4 | escape inválido o magia nueva dentro de una trama | `AA 55` arranca la nueva trama |

`LLPError.wireName()` devuelve el nombre que usan los vectores
(`LLP_ERR_CRC`, …), así los logs se pueden cruzar con la especificación.

Los errores son **eventos**, no estados fatales: después de uno, el parser está
listo para la próxima trama. No se lanzan excepciones por errores de wire;
`LLPException` es solo para uso incorrecto de la API (payload > 255, canal fuera
de rango, configuración inválida).

---

## 🏛️ Arquitectura

```
com.flamingo.comm.llp/
├── LLP.java                    # fachada: parser(), incrementalParser(), encode()
├── LLPParser.java              # parser byte a byte (espejo de llp4_parser_t)
├── LLPEncoder.java             # encoder en streaming + encode() de conveniencia
├── FeedResult.java             # Frame | Error | None (sellado)
├── LLPFrame.java               # record (channel, payload read-only, crc, receivedAt)
├── LLPError.java               # los cuatro códigos de wire
├── LLPException.java           # uso incorrecto de la API
├── LLPIncrementalParser.java   # capa pull-based con clock inyectable
└── util/
    ├── CRC16CCITT.java         # CRC16-CCITT con tabla de 256
    └── Statistics.java         # contadores por parser
```

No hay paquete `spi` ni registro de capas: el payload es opaco y la aplicación
decide qué hacer con él.

### Garantías del parser

1. **Ningún efecto antes del CRC.** Ningún byte del payload es observable antes
   de que `READ_CRC_H` acepte la trama.
2. **O(1) por byte.** Sin bucles que dependan del contenido.
3. **Memoria acotada.** Un parser nunca guarda más de `capacity` bytes.
4. **Rechazo temprano de longitud.** `LEN` se valida antes de almacenar payload.
5. **Reentrancy.** Un parser por enlace, alimentado desde un solo contexto.
6. **Tiempo inyectado.** El parser nunca lee un reloj.

---

## ✅ Conformidad

Los tests ejecutan el corpus oficial de
[llp-spec/vectors](https://github.com/flamicomm/llp-spec/tree/main/vectors):
**161 casos** en 7 archivos, copiados a `src/test/resources/llp-vectors/` con su
`MANIFEST.sha256`.

| Suite | Casos |
| --- | ---: |
| `EncodeVectorTest` | 60 |
| `DecodeVectorTest` | 88 |
| `StreamVectorTest` | 13 |
| `VectorManifestTest` | 1 (sha256 de los 7 archivos) |

Actualizar la copia del corpus:

```bash
tools/sync-vectors.sh ../llp-spec
```

El script copia y verifica el manifiesto; `VectorManifestTest` vuelve a
verificarlo en cada build, así que una copia parcial o editada a mano falla.

---

## 🧪 Pruebas

```bash
mvn test          # 229 ejecuciones
mvn verify        # + javadoc, jar de fuentes y cobertura JaCoCo
```

La suite cubre el parser (invariantes, cuatro errores, timeout, wrap de 16 bits),
el encoder (los tres sitios de stuffing, límites, streaming), el receptor
(chunks, clock, polling) y los 161 vectores de conformidad. Ningún test duerme:
el tiempo se simula con `dt_ms`.

---

## 🔄 Migración desde 3.x

v4 **no es compatible a nivel de wire** con 3.x y la API pública cambió. La 3.x
queda en la rama `main` con tag `v3.1.0`.

| 3.x | 4.0.0 |
| --- | --- |
| `LLP.frameBuilder().addLayer(...)` + `.build(payload)` | `LLP.encode(channel, payload)` |
| `LLP.incrementalParser().maxPayloadBytes(4096)` | `.capacity(255)` |
| `parser.feed(b)` (reloj interno) | `parser.feed(b, nowMs)` o clock inyectable |
| `LLPFrame.chain()` + `FinalNode` | `frame.channel()` + `frame.payloadBytes()` |
| `LLPRawFrame` + `LLPFrame` | un solo `LLPFrame` |
| `TransportErrorCode` (8 valores) | `LLPError` (4 valores de wire) |
| `LLPTransportDeframer` / `LLPTransportFramer` | `LLPParser` / `LLPEncoder` |
| Capas, SPI, `NodeChain`, `NodeVisitor` | eliminados: el payload es opaco |
| slf4j | sin dependencias |

El payload ya no incluye el marcador `FinalNode` (`0x00`): en v4 lo que enviás
es exactamente lo que llega.

---

## 📊 Notas de rendimiento

* Sin asignación por byte en el camino caliente: `FeedResult.NONE` y
  `FeedResult.FRAME` son singletons; solo los errores crean un objeto.
* `LLPParser.payload()` es una vista read-only sobre el buffer, sin copia.
* `LLPFrame` copia el payload una sola vez, al entregar (necesario para que sea
  inmutable).
* `LLPEncoder` no reserva una trama: escribe al `Sink` a medida que produce.
* `LLP.frameSize(n)` reserva el peor caso (`10 + 2n`) si necesitás el buffer.

---

## 🤝 Contribuir

1. Hacé un fork y creá una rama (`feature/nueva-caracteristica`).
2. Los cambios de wire van primero a
   [llp-spec](https://github.com/flamicomm/llp-spec) (spec + vectores) y después
   acá.
3. Código, comentarios, Javadoc y nombres en **inglés**.
4. `mvn verify` en verde.

---

## 📜 Licencia

MIT — ver [LICENSE](LICENSE).

LLP Specification v4.0.0 — Copyright © 2026 Flamingo Communications

---

**Versión:** 4.0.0
**Wire format:** 4.0.0
**Especificación:** [llp-spec](https://github.com/flamicomm/llp-spec/blob/main/docs/SPEC-v4.md)
**Objetivo Java:** 21+
