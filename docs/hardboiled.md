# Contrato del módulo hardboiled

Descubrimiento: `_bridge-remote._tcp.local.` con TXT `service=hardboiled`,
`version=1.3`, `requires_pin=1`, `id` y `name`. Se usa el tipo base más filtro TXT
para soportar API 26 sin depender de subtipos DNS-SD. QR:
`bridge://pair?v=1.3&host=IP&port=8767&service=hardboiled&pin=123456`.

Transporte: `ws://IP:PUERTO/ws`; envoltura JSON 1.3 (`sessionId=hardboiled`,
`source=android`, `type=command`, `action`, `payload`). El PIN viaja sólo dentro de
`PAIR_REQUEST`; el servidor retorna `PAIRING_SUCCESS` con `token` y `service`.
Al reconectar se usa ese token en lugar del PIN. Si vence, se vuelve a intentar
el PIN de la sesión en memoria. Un PIN inválido detiene los reintentos automáticos.

Después se envía `GET_CAPABILITIES`; `CAPABILITIES_LIST` contiene
`payload.service=hardboiled` y `payload.capabilities`:

| controlType | Parámetros | Envío |
| --- | --- | --- |
| accelerometer | device, channels=[0,1,2], vref_mv, min_hz=20, max_hz=60 | ADC_SAMPLE: device, millivolts=[X,Y,Z] |
| button_bank | count | BUTTON_PRESS: pin |
| shake | device | IRQ_RAISE / IRQ_LOWER: device |

Se ignoran capacidades deshabilitadas. Un manifiesto sin acelerómetro es
incompatible. El servidor valida tipos, dimensiones y rango de cada muestra;
las aplica en el hilo del motor mediante `CmdSetAdcInputs`, antes de las lecturas
MMIO del firmware. No se escribe memoria ni registros del motor desde el socket.
La sacudida genera un pulso de 100 ms, limitado a una vez por segundo. La conexión
que termina solicita bajar la IRQ para evitar que quede activa.

## Verificación reproducible

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
bash tests/smoke_spec.sh
```

Los tests JVM cubren QR/IPv6, rechazo de sesiones incompatibles y mapeo/saturación.
La suite `hardboiled/tests/test_remote.py` prueba el servidor WebSocket con
PIN/token, bloqueo, origen, payloads inválidos y aplicación de muestras hasta la
conversión ADC. Para probar una tablet por USB sin depender de Wi-Fi:

```sh
adb reverse tcp:8767 tcp:8767
```

Conectá la app a `127.0.0.1:8767` con el PIN de la instancia de hardboiled; mové el
dispositivo y verificá los canales en la TUI. Al terminar:

```sh
adb reverse --remove tcp:8767
```

QR óptico y descubrimiento multicast deben comprobarse además en la Wi-Fi del aula.
