package ar.edu.unrn.hardline

import java.net.URI
import java.net.URLDecoder

/** Se mantiene el PIN fuera de la URL WebSocket, logs y almacenamiento. */
data class Pairing(val host: String, val port: Int, val pin: String) {
    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/?#@" }) { "Host inválido" }
        require(URI("ws://${if (':' in host) "[$host]" else host}:$port/ws").host != null) { "Host inválido" }
        require(port in 1..65535) { "Puerto inválido" }
        require(pin.matches(Regex("[0-9]{6}"))) { "Ingresá el PIN de seis dígitos" }
    }
    val endpoint: String get() = "ws://${if (':' in host) "[$host]" else host}:$port/ws"
    companion object {
        fun parse(text: String): Pairing {
            val uri = URI(text.trim())
            require(uri.scheme == "bridge" && uri.host == "pair") { "QR de emparejamiento inválido" }
            val params = (uri.rawQuery ?: "").split("&").associate {
                val fields = it.split("=", limit = 2)
                fields[0] to URLDecoder.decode(fields.getOrElse(1) { "" }, "UTF-8")
            }
            require(params["v"] == "1.3" && params["service"] == "hardboiled") { "Sesión incompatible" }
            return Pairing(params["host"] ?: "", params["port"]?.toIntOrNull() ?: 0, params["pin"] ?: "")
        }
    }
}
