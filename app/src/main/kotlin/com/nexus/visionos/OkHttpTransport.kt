package com.nexus.visionos

import com.nexus.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** No logging interceptor on purpose: headers carry API keys. */
fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    // Redirects are off so custom key headers can never be forwarded to another host.
    .followRedirects(false)
    .build()

/** Runs a blocking call on IO and cancels the socket when the coroutine is cancelled. */
suspend fun <T> OkHttpClient.execute(request: Request, block: suspend (Response) -> T): T =
    withContext(Dispatchers.IO) {
        val call = newCall(request)
        coroutineScope {
            val canceller = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { block(it) }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive() // a user cancel is not a network error
                throw e
            } finally {
                canceller.cancel()
            }
        }
    }

private fun request(url: String, headers: Map<String, String>): Request.Builder =
    Request.Builder().url(url).also { b -> headers.forEach { (k, v) -> b.header(k, v) } }

class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpReply =
        client.execute(request(url.toHttpUrl().toString(), headers).build()) {
            HttpReply(it.code, it.body?.string().orEmpty())
        }

    override suspend fun postStream(
        url: String, headers: Map<String, String>, json: String,
        onChunk: suspend (ByteArray, Int) -> Unit
    ): HttpReply = client.execute(
        request(url, headers).post(json.toRequestBody("application/json".toMediaType())).build()
    ) { response ->
        if (!response.isSuccessful) return@execute HttpReply(response.code, response.body?.string().orEmpty())
        val input = response.body?.byteStream() ?: return@execute HttpReply(response.code, "")
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            currentCoroutineContext().ensureActive()
            onChunk(buffer, n)
        }
        HttpReply(response.code, "")
    }
}

class OkHttpSockets(private val client: OkHttpClient) : SocketFactory {
    override suspend fun open(url: String, headers: Map<String, String>): SocketSession {
        val messages = Channel<String>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        val socket = client.newWebSocket(
            request(url.replaceFirst("wss://", "https://"), headers).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, text: String) { messages.trySend(text) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    messages.close()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val error: Throwable = if (response != null && response.code != 101) {
                        ElevenLabsErrors.fromHttp(response.code, runCatching { response.body?.string() }.getOrNull().orEmpty())
                    } else IOException("WebSocket failure", t)
                    opened.completeExceptionally(error)
                    messages.close(error)
                }
            })
        try {
            withTimeoutOrNull(12_000) { opened.await(); true }
                ?: throw VoiceException(ErrorKind.NETWORK, "Timed out connecting to Scribe")
        } catch (e: Throwable) {
            socket.cancel()
            throw e
        }
        return object : SocketSession {
            override val incoming = messages.receiveAsFlow()
            // Refuses to queue unbounded audio on a stalled connection.
            override fun send(text: String) = socket.queueSize() < 1_000_000 && socket.send(text)
            override fun close() { if (!socket.close(1000, null)) socket.cancel() }
        }
    }
}
