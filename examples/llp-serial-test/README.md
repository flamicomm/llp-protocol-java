# llp-serial-test — ejemplo de integración del perfil R1

Módulo Maven **aparte** (no está en el reactor de `llp-protocol-java`) que
muestra cómo usar la librería `com.flamingo:llp-core` contra un dispositivo real
y cómo probar el perfil R1 sin hardware.

## Contenido

| Clase | Qué hace |
|---|---|
| `R1SelfTest` | Prueba de integración **sin hardware**: simula emisor y receptor en memoria con reloj inyectado y verifica las garantías de R1. |
| `SerialLLPTest` | Demo **con hardware**: habla el perfil R1 de punta a punta contra grbl-llp por USB/serie directo. |
| `SerialTest` | Herramienta de wire crudo: imprime lo que llega por el puerto, sin LLP. |
| `SerialLLPShell` | Shell de wire crudo para explorar tramas a mano. |

`SerialTest` y `SerialLLPShell` **no usan R1 a propósito**: sirven para depurar
el cable y las tramas, no la capa de confiabilidad. Si querés probar comandos
reliables, usá `SerialLLPTest`.

## Compilar

Desde la raíz del repo, primero instalá el core del working tree en `~/.m2` para
que el ejemplo no use una versión publicada distinta:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -q -DskipTests install
mvn -q -f examples/llp-serial-test/pom.xml package
```

El módulo declara `maven.compiler.release=21`. Las dependencias del ejemplo
(`jSerialComm`, `slf4j-simple`) se resuelven del repositorio local si ya están
cacheadas; para forzar modo offline agregá `-o`.

## Modo 1 — self-test sin hardware

```bash
$JAVA_HOME/bin/java \
  -cp examples/llp-serial-test/target/classes:$HOME/.m2/repository/com/flamingo/llp-core/4.0.0/llp-core-4.0.0.jar \
  com.flamingo.llp.test.R1SelfTest
```

Imprime líneas `[PASS]`/`[FAIL]` y termina con `RESULT: PASS` o `RESULT: FAIL`
(código de salida 0/1, igual que `llp_link --self-test`). No abre puertos ni
duerme: el reloj es un contador de milisegundos inyectado.

Escenarios que cubre:

1. **Entrega limpia**: cinco comandos, cinco ACK, cinco entregas, cero
   reintentos.
2. **ACK perdido**: el primer ACK se descarta; el emisor retransmite el frame
   byte-idéntico y el receptor lo deduplica, así que la entrega es única.
3. **Comando perdido**: el primer frame no llega; la retransmisión se entrega
   una sola vez.
4. **HELLO resetea el dedup**: `SEQ 7`, HELLO, `SEQ 7` otra vez produce dos
   entregas.
5. **Wrap 255→0**: ambos comandos se entregan.
6. **Comando agotado**: se pierden todos los ACK de un comando; el emisor agota
   los reintentos, quema ese `SEQ` y el comando siguiente sale con `SEQ+1`. El
   device ve dos `SEQ` distintos y entrega ambos comandos. Sin la quema, el
   device deduplicaría el segundo en silencio y el escenario falla.

Los checks comparan contadores exactos (ACK, entregas, duplicados, reintentos y
frames), así que una regresión en `LLPRelaySender` o `LLPRelayReceiver` los hace
fallar.

## Modo 2 — contra grbl-llp por USB/serie directo

```bash
$JAVA_HOME/bin/java \
  -cp examples/llp-serial-test/target/classes:$HOME/.m2/repository/com/flamingo/llp-core/4.0.0/llp-core-4.0.0.jar:$HOME/.m2/repository/com/fazecast/jSerialComm/2.10.4/jSerialComm-2.10.4.jar \
  com.flamingo.llp.test.SerialLLPTest
```

El puerto está fijo en `/dev/ttyUSB0` a 115200 baudios.

Este modo **no funciona a través del puente ESP**: el ESP es el *emisor* R1,
consume los ACK de CH2 y no los reenvía al PC (que le habla por TCP). La demo
asume un enlace serie/USB directo al MCU que corre grbl-llp como receptor R1.

### Drenaje del boot

Al abrir el puerto, el CH340 activa DTR y resetea el MCU (F-013/F-014/F-018). El
firmware tarda unos segundos en arrancar y mientras tanto imprime un banner de
texto. `SerialLLPTest` drena **4500 ms** leyendo y descartando bytes **sin
pasarlos por el parser** e informa cuántos descartó; si se los alimentara al
parser solo producirían errores de sincronización.

### Flujo R1

1. Envía `HELLO` (canal 3) **dos veces**: si se pierde uno, la sesión sigue.
2. Por cada comando (`$X`, `$I`, `G4 P0`, `$G`):
   - `LLPRelaySender.start(seq, ...)` y escribe el frame `COMMAND` (canal 1).
   - Espera el `ACK` (canal 2). Si vence el timeout, `service()` devuelve `SEND`
     y reescribe el frame byte-idéntico.
   - Los frames de canal 0 se imprimen como texto (`ok`, `error:`, `[MSG...]`).
   - Si se agotan los reintentos, el comando se marca como no entregado.
   - El `SEQ` se quema **siempre**, entregado o no (`RELIABILITY §4`): reusarlo
     con otro `DATA` haría que el device deduplique el comando siguiente en
     silencio y que un ACK tardío cierre la ventana equivocada.
3. Al cerrar, drena unos 400 ms de canal 0 para imprimir el último
   `ok`/`error`, y resume comandos, intentados, ACK, reintentos, no entregados y
   salteados.

El ACK es de **transporte**: no reemplaza el `ok`/`error` de la aplicación, que
llega por el canal 0.

## Relación con la norma

El perfil R1 está definido en `llp-spec/docs/RELIABILITY.md`. La librería expone
las constantes y máquinas de estado en `LLPProfileR1`, `LLPRelaySender` y
`LLPRelayReceiver`; el framing sigue siendo el de `LLP`/`LLPParser`.
