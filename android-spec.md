# Especificación Técnica: Aplicación Android de Control Remoto y Telemetría

**Versión:** 1.0  
**Ámbito:** Cliente móvil Android para control externo de presentaciones (`slide-tools`) y videollamadas (`meet-tools`)  
**Compatibilidad:** Android 8.0+ (API 26+)  

---

## 1. Arquitectura y Enfoque del Cliente

La aplicación Android actúa como un controlador externo desacoplado de baja latencia operando sobre la red local (Wi-Fi/LAN). En lugar de codificar interfaces rígidas y estáticas por servicio, adopta una arquitectura orientada a capacidades (*Capability-Driven UI*):
1. Descubre hosts en LAN (mDNS, UDP Beacon o escaneo QR).
2. Establece conexión WebSocket y autentica con PIN obligatorio.
3. Solicita el manifiesto de capacidades del daemon conectado.
4. Renderiza reactivamente controles táctiles, telemetría y notas según las capacidades declaradas activas.

```
┌──────────────────────────────────────────────────────────┐
│                   App Nativa Android                     │
│  - Jetpack Compose (UI Reactiva por Capacidades)         │
│  - OkHttp / Ktor WebSocket Client (LAN Streaming)        │
│  - NsdManager (mDNS) + DatagramSocket (UDP Beacon)       │
│  - CameraX + ML Kit (Escaneo de QR Pairing)              │
└────────────────────────────┬─────────────────────────────┘
                             │ JSON over WebSocket
                             │ ws://<HOST_IP>:<PORT>
                             ▼
┌──────────────────────────────────────────────────────────┐
│                  Daemon Concentrador                     │
│  - slide-tools (ws:8766) / meet-tools (ws:8765)          │
│  - Autenticación por PIN estricta                        │
│  - Despacho de comandos y difusión de telemetría         │
└──────────────────────────────────────────────────────────┘
```

---

## 2. Requisitos del Sistema y de la Aplicación

### 2.1. Requisitos Técnicos de Plataforma
* **Lenguaje:** Kotlin 2.x.
* **UI Toolkit:** Jetpack Compose con Material Design 3.
* **Mínimo SDK:** Android 8.0 (API 26). Target SDK: Android 14+ (API 34).
* **Conectividad:**
  * OkHttp o Ktor Client para sockets WebSocket persistentes con reconexión exponencial.
  * `android.net.nsd.NsdManager` para Network Service Discovery (mDNS / DNS-SD).
  * `java.net.DatagramSocket` para difusión UDP Broadcast en subred local.
* **Cámara / Sensores:**
  * CameraX + Google ML Kit Barcode Scanning para emparejamiento instantáneo por QR.
  * `Vibrator` / `VibratorManager` para confirmación háptica en ejecución de comandos (< 20 ms).
* **Gestión de Energía y Ciclo de Vida:**
  * Opción conmutable de pantalla activa (`FLAG_KEEP_SCREEN_ON`).
  * Foreground Service (`FOREGROUND_SERVICE_CONNECTED_DEVICE`) con notificación persistente para mantener telemetría con pantalla apagada o permitir mapeo de botones físicos de volumen a navegación.

### 2.2. Requisitos Funcionales
* **RF-01 (Descubrimiento Multimodal):** La app debe soportar mDNS, sondeo UDP Broadcast, escaneo de QR y carga manual de IP/Puerto/PIN.
* **RF-02 (Autenticación Obligatoria por PIN):** Ningún comando ni sincronización procede sin proveer el PIN de 4 a 12 caracteres validado por el host.
* **RF-03 (Descubrimiento de Capacidades):** Al conectar, la app consulta y lista dinámicamente las capacidades soportadas por el servicio anfitrión (`CAPABILITIES_LIST`).
* **RF-04 (Telemetría de Baja Latencia):** Renderizado reactivo de estado (diapositiva actual, notas de orador, temporizador, estado de mic/cámara, participantes) con latencia percibida <= 50 ms.
* **RF-05 (Reconexión Automática Transparente):** En caso de corte momentáneo de Wi-Fi, la app reintenta reconexión sin resetear la configuración del host.

---

## 3. Protocolo de Comunicación (JSON over WebSocket v1.3)

Todos los mensajes intercambiados son cadenas UTF-8 formateadas en JSON:

```json
{
  "version": "1.3",
  "sessionId": "string",
  "source": "android",
  "type": "command" | "state" | "event" | "ack" | "error",
  "action": "string",
  "payload": {}
}
```

---

## 4. Método de Descubrimiento de Servicios en Red Local

La aplicación implementa tres mecanismos complementarios ordenados por prioridad:

### 4.1. Mecanismo A: DNS-SD / mDNS (Network Service Discovery)
* **Tipo de Servicio Principal:** `_bridge-remote._tcp.local.`
* **Subtipos Específicos:**
  * `_slide-bridge._sub._bridge-remote._tcp.local.` (Puerto por defecto: `8766`)
  * `_meet-bridge._sub._bridge-remote._tcp.local.` (Puerto por defecto: `8765`)
* **Registros TXT Obligatorios:**
  * `id`: Identificador persistente del daemon (UUID o hash de máquina).
  * `service`: `"slides"` | `"meet"` | `"hybrid"`.
  * `name`: Nombre descriptivo del equipo (ej. `"ThinkPad-Martin"`).
  * `version`: Versión de protocolo (ej. `"1.3"`).
  * `requires_pin`: `"1"` | `"0"`.

### 4.2. Mecanismo B: UDP Broadcast Beacon (Fallback sin mDNS)
* **Puerto UDP Local:** `8760` (escucha en daemon y cliente).
* **Sonda del Cliente Android (Broadcast a `255.255.255.255:8760`):**
  ```json
  {
    "type": "DISCOVERY_PROBE",
    "source": "android",
    "clientVersion": "1.3"
  }
  ```
* **Respuesta Unicast del Daemon a la IP del dispositivo:**
  ```json
  {
    "type": "DISCOVERY_ANNOUNCE",
    "service": "slides",
    "serviceName": "Google Slides Bridge",
    "hostName": "workstation-arch",
    "port": 8766,
    "requiresPin": true,
    "protocolVersion": "1.3"
  }
  ```

### 4.3. Mecanismo C: Escaneo de Código QR
Los daemons y extensiones inyectan un QR en pantalla codificando un URI estandarizado:
```
bridge://pair?v=1.3&host=192.168.1.105&port=8766&service=slides&pin=4821&name=Workstation
```
* **Campos del URI:**
  * `host`: IP LAN del servidor WebSocket.
  * `port`: Puerto TCP de escucha.
  * `service`: Tipo de servicio (`slides` o `meet`).
  * `pin`: Código de emparejamiento.
  * `name`: Nombre descriptivo del host.

---

## 5. Método de Negociación y Listado de Capacidades

Para desacoplar el cliente de la implementación concreta de cada herramienta, el daemon expone un catálogo dinámico de capacidades.

### 5.1. Consulta de Capacidades (`GET_CAPABILITIES`)
Emitido por la app Android tras recibir confirmación de emparejamiento exitoso (`PAIRING_SUCCESS`):

```json
{
  "version": "1.3",
  "sessionId": "default",
  "source": "android",
  "type": "command",
  "action": "GET_CAPABILITIES",
  "payload": {}
}
```

### 5.2. Respuesta del Manifiesto de Capacidades (`CAPABILITIES_LIST`)
El daemon responde con las acciones disponibles, controles de UI asociados y estado actual:

#### Ejemplo: Perfil de Presentaciones (`slide-tools`)
```json
{
  "version": "1.3",
  "sessionId": "default",
  "source": "daemon",
  "type": "event",
  "action": "CAPABILITIES_LIST",
  "payload": {
    "service": "slides",
    "displayName": "Google Slides Remote",
    "activeSession": true,
    "sessionTitle": "Arquitectura Distribuida 2026",
    "capabilities": [
      {
        "id": "nav_next",
        "action": "NEXT_SLIDE",
        "label": "Siguiente",
        "category": "navigation",
        "controlType": "button",
        "icon": "arrow_forward",
        "enabled": true
      },
      {
        "id": "nav_prev",
        "action": "PREV_SLIDE",
        "label": "Anterior",
        "category": "navigation",
        "controlType": "button",
        "icon": "arrow_back",
        "enabled": true
      },
      {
        "id": "goto_slide",
        "action": "GO_TO_SLIDE",
        "label": "Ir a diapositiva",
        "category": "navigation",
        "controlType": "number_stepper",
        "params": { "min": 1, "max": 45 },
        "enabled": true
      },
      {
        "id": "tool_laser",
        "action": "TOGGLE_LASER",
        "label": "Puntero Láser",
        "category": "tools",
        "controlType": "toggle",
        "icon": "laser_pointer",
        "state": { "active": false },
        "enabled": true
      },
      {
        "id": "tool_blackout",
        "action": "TOGGLE_BLACKOUT",
        "label": "Pantalla Negra",
        "category": "tools",
        "controlType": "toggle",
        "icon": "visibility_off",
        "state": { "active": false },
        "enabled": true
      },
      {
        "id": "telemetry_notes",
        "action": "SPEAKER_NOTES_SYNC",
        "label": "Notas de Orador",
        "category": "telemetry",
        "controlType": "text_stream",
        "enabled": true
      },
      {
        "id": "telemetry_timer",
        "action": "TIMER_SYNC",
        "label": "Cronómetro",
        "category": "telemetry",
        "controlType": "timer_stream",
        "enabled": true
      }
    ]
  }
}
```

#### Ejemplo: Perfil de Videollamada (`meet-tools`)
```json
{
  "version": "1.3",
  "sessionId": "default",
  "source": "daemon",
  "type": "event",
  "action": "CAPABILITIES_LIST",
  "payload": {
    "service": "meet",
    "displayName": "Google Meet Remote",
    "activeSession": true,
    "sessionTitle": "Reunión de Cátedra",
    "capabilities": [
      {
        "id": "media_mic",
        "action": "TOGGLE_MIC",
        "label": "Micrófono",
        "category": "media",
        "controlType": "toggle",
        "icon": "mic",
        "state": { "muted": false },
        "enabled": true
      },
      {
        "id": "media_cam",
        "action": "TOGGLE_CAM",
        "label": "Cámara",
        "category": "media",
        "controlType": "toggle",
        "icon": "videocam",
        "state": { "muted": true },
        "enabled": true
      },
      {
        "id": "media_hand",
        "action": "TOGGLE_HAND",
        "label": "Mano Levantada",
        "category": "media",
        "controlType": "toggle",
        "icon": "front_hand",
        "state": { "raised": false },
        "enabled": true
      },
      {
        "id": "mod_mute_all",
        "action": "MUTE_ALL",
        "label": "Silenciar a Todos",
        "category": "moderation",
        "controlType": "button",
        "icon": "volume_off",
        "requiresHost": true,
        "enabled": true
      },
      {
        "id": "mod_admit_all",
        "action": "ADMIT_ALL",
        "label": "Admitir a Todos",
        "category": "moderation",
        "controlType": "button",
        "icon": "group_add",
        "requiresHost": true,
        "enabled": false
      },
      {
        "id": "call_leave",
        "action": "LEAVE_CALL",
        "label": "Salir de la Llamada",
        "category": "session",
        "controlType": "danger_button",
        "icon": "call_end",
        "enabled": true
      }
    ]
  }
}
```

### 5.3. Actualización Dinámica (`CAPABILITIES_UPDATED`)
Si el contexto de ejecución cambia en el host (ej. el presentador inicia la vista de orador o el usuario adquiere rol de anfitrión en Meet), el daemon envía el evento `CAPABILITIES_UPDATED` sin requerir reconexión:
```json
{
  "version": "1.3",
  "sessionId": "default",
  "source": "daemon",
  "type": "event",
  "action": "CAPABILITIES_UPDATED",
  "payload": {
    "capabilities": [
      { "id": "mod_admit_all", "enabled": true }
    ]
  }
}
```

---

## 6. Ciclo de Vida y Máquina de Estados del Cliente Android

```
       ┌────────────────────────┐
       │      1. IDLE / OFF     │
       └───────────┬────────────┘
                   │ Iniciar búsqueda / Scan QR
                   ▼
       ┌────────────────────────┐
       │     2. DISCOVERY       │◄─────────────┐
       │ (mDNS / UDP / QR / IP) │              │
       └───────────┬────────────┘              │
                   │ Servicio detectado        │ Fallo auth / timeout
                   ▼                           │
       ┌────────────────────────┐              │
       │     3. CONNECTING      │──────────────┤
       │   (WebSocket Handshake)│              │
       └───────────┬────────────┘              │
                   │ WS Abierto                │
                   ▼                           │
       ┌────────────────────────┐              │
       │       4. PAIRING       │──────────────┘
       │  (Envío PAIR_REQUEST)  │── PIN Erróneo ──► Modal re-ingreso PIN
       └───────────┬────────────┘
                   │ PAIRING_SUCCESS
                   ▼
       ┌────────────────────────┐
       │ 5. FETCH_CAPABILITIES  │
       │ (GET_CAPABILITIES msg) │
       └───────────┬────────────┘
                   │ CAPABILITIES_LIST recibido
                   ▼
       ┌────────────────────────┐
       │   6. ACTIVE SESSION    │◄──── Telemetría STATE_SYNC (bidireccional)
       │ (UI Dinámica Reactiva) │────► Envío de Comandos + Haptic Ack
       └───────────┬────────────┘
                   │ Desconexión de red Wi-Fi
                   ▼
       ┌────────────────────────┐
       │    7. RECONNECTING     │── Reintento exponencial (1s, 2s, 4s...)
       └────────────────────────┘
```

---

## 7. Esquema de Payload de Emparejamiento

### Envío de Emparejamiento (`PAIR_REQUEST`)
```json
{
  "version": "1.3",
  "sessionId": "default",
  "source": "android",
  "type": "command",
  "action": "PAIR_REQUEST",
  "payload": {
    "pin": "4821",
    "deviceType": "android",
    "clientName": "Pixel 8 Pro (Martin)"
  }
}
```

### Respuesta Exitosa (`PAIRING_SUCCESS`)
```json
{
  "version": "1.3",
  "sessionId": "default",
  "source": "daemon",
  "type": "event",
  "action": "PAIRING_SUCCESS",
  "payload": {
    "clientId": "c_48102",
    "service": "slides",
    "status": "ok"
  }
}
```

---

## 8. Mapeo de UI Adaptativa en Jetpack Compose

El renderizador de la aplicación mapea cada elemento de `capabilities` a un componente Compose:

| `controlType` | Componente Compose | Comportamiento / Interacción |
| :--- | :--- | :--- |
| `button` | `ElevatedButton` / `IconButton` | Emite `action` con payload `{}` al pulsar. Feedback táctil inmediato. |
| `toggle` | `FilledTonalIconToggleButton` | Refleja estado booleano; conmuta emitiendo la acción configurada. |
| `number_stepper` | `NumberPickerRow` | Permite salto numérico (`GO_TO_SLIDE`) con botones +/- y slider. |
| `danger_button` | `Button(colors = errorColors)` | Confirmación por diálogo modal antes del despacho (`LEAVE_CALL`). |
| `text_stream` | `SelectionContainer { Text() }` | Renderiza notas de orador con ajuste de tipografía para lectura a distancia. |
| `timer_stream` | `DigitalClockDisplay` | Renderiza `formattedTime` y coloriza advertencia si sobrepasa tiempo límite. |
