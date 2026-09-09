### Requisitos y Planteamiento Técnico: C1 (Web Companion / Browser Deck)

Para la implementación de **C1** en una próxima conversación, se plantean los siguientes requisitos arquitectónicos y dependencias recomendadas:

1.  **Servidor HTTP & WebSocket Embebido en Android**:

    -   **Librería sugerida**: Ktor Server (módulo ktor-server-cio, ktor-server-websockets) o NanoHTTPD.

    -   **Función**: Alojar un servidor local ligero que corra en el AutomationService (en segundo plano).

    -   **Puerto**: Configurable (por defecto 8080).

2.  **Single Page Application (SPA) Embebida**:

    -   Una interfaz web moderna en HTML5/CSS/JavaScript (Canvas o CSS Grid reactivo) servida desde assets/web_deck/.

    -   Soporte para pantalla táctil en navegadores móviles (iOS Safari, Android Chrome) o escritorio (OBS Browser Source / navegador secundario).

    -   Estilo visual sincronizado con el tema oscuro y celdas de SoundDeck.

3.  **Protocolo de Comunicación Bidireccional (WebSocket)**:

    -   **Eventos App -> Web**:

        -   Envío del manifiesto activo y la cuadrícula actual (page_sync).

        -   Emisión de estados dinámicos (niveles de audio, mute de OBS, porcentaje de carga HTTP).

    -   **Eventos Web -> App**:

        -   pad_tap: Pulsación simulada enviada desde el navegador para ejecutar las acciones en el dispositivo principal.

        -   page_select: Cambio de página remota.

        -   emergency_stop: Parada total de audio remota.

4.  **Seguridad y Descubrimiento en Red Local**:

    -   Código PIN o token de autorización opcional para evitar accesos no autorizados en redes compartidas.

    -   Publicación de servicio mDNS para el servidor web (_sounddeck-web._tcp) para acceso fácil mediante http://sounddeck.local:8080.
