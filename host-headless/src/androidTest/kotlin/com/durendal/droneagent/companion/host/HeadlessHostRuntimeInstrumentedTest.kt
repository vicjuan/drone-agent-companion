package com.durendal.droneagent.companion.host

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** API 34 runtime smoke; this is emulator evidence and never G520 hardware evidence. */
@RunWith(AndroidJUnit4::class)
class HeadlessHostRuntimeInstrumentedTest {
    @Before
    fun isolateAgentServiceFromPreviousTest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client =
            OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build()
        try {
            if (isAgentServiceRunning(context)) {
                requestSafeStopAndAwaitServiceDestroyed(context, client)
            }
            assertUnavailableFor(client, "$DEVICE_HTTP_ORIGIN/healthz", ISOLATION_STABLE_MILLIS)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun foregroundServiceServesSpaAndRunsStrictMockCommandPath() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val auditFile = HeadlessHostStorage.consoleAuditFile(context)
        val auditStartOffset = auditFile.length()
        val lifecycleEpochFloor = System.currentTimeMillis()
        val instrumentationPid = android.os.Process.myPid()
        val client =
            OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
        var socket: WebSocket? = null
        val serviceMayBeRunning = AtomicBoolean(false)
        try {
            serviceMayBeRunning.set(true)
            HeadlessAgentService.requestStart(context)

            val health = awaitHttp(client, "$DEVICE_HTTP_ORIGIN/healthz", START_TIMEOUT_MILLIS)
            assertEquals(200, health.code)
            assertEquals("{\"status\":\"ok\"}", health.body?.string())
            assertEquals("DENY", health.header("X-Frame-Options"))
            health.close()

            val agentPid = awaitAgentPid(context, excludedPid = null, START_TIMEOUT_MILLIS)
            awaitLifecycleRecords(context, START_TIMEOUT_MILLIS) { records ->
                records.any {
                    it.epochMillis >= lifecycleEpochFloor &&
                        it.pid == agentPid &&
                        it.event == LifecycleEvent.RUNTIME_STARTED.wireName
                }
            }

            val index = awaitHttp(client, "$DEVICE_HTTP_ORIGIN/", START_TIMEOUT_MILLIS)
            assertEquals(200, index.code)
            assertTrue(index.body?.string().orEmpty().contains("Drone Agent Companion"))
            assertTrue(index.header("Content-Security-Policy").orEmpty().contains("frame-ancestors 'none'"))
            index.close()

            assertTrue("instrumentation must be outside the :agent process", agentPid != instrumentationPid)
            val messages = Collections.synchronizedList(mutableListOf<String>())
            val failure = AtomicReference<Throwable?>()
            socket =
                client.newWebSocket(
                    Request.Builder()
                        .url("ws://127.0.0.1:8080/api/console/v1")
                        .header("Origin", FORWARDED_BROWSER_ORIGIN)
                        .build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(CLIENT_HELLO)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            messages += text
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            failure.compareAndSet(null, t)
                        }
                    },
                )

            val hello = awaitMessage(messages, failure, "server_hello")
            assertEquals("1.0", hello.getString("protocolVersion"))
            assertEquals("server_hello", firstType(messages))

            val runtime = awaitMessage(messages, failure, "runtime_state")
            assertEquals("mock", runtime.getJSONObject("payload").getString("adapter"))
            assertEquals(
                "connected",
                runtime.getJSONObject("payload").getString("aircraftConnection"),
            )
            assertEquals(
                "unlocked",
                runtime.getJSONObject("payload").getString("actuationLock"),
            )
            awaitMessage(messages, failure, "telemetry")
            val capabilities = awaitMessage(messages, failure, "capability_snapshot")
            val rows = capabilities.getJSONObject("payload").getJSONArray("rows")
            assertTrue(rows.length() > 0)
            for (indexInRows in 0 until rows.length()) {
                assertEquals("UNKNOWN", rows.getJSONObject(indexInRows).getString("status"))
            }

            assertTrue(socket.send(LEASE_ACQUIRE))
            val leaseState =
                awaitMessage(messages, failure, "lease_state") { payload ->
                    payload.optString("requestMessageId") == LEASE_REQUEST_ID &&
                        payload.optString("state") == "held"
                }
            val leaseId = leaseState.getJSONObject("payload").getString("leaseId")
            assertTrue(socket.send(takeoffRequest(leaseId)))

            val acknowledgement =
                awaitMessage(messages, failure, "command_ack") { payload ->
                    payload.optString("commandId") == COMMAND_ID
                }
            val acknowledgementPayload = acknowledgement.getJSONObject("payload")
            assertEquals("accepted", acknowledgementPayload.getString("decision"))
            val acknowledgedDigest = acknowledgementPayload.getString("intentDigestSha256")
            val result =
                awaitMessage(messages, failure, "command_result") { payload ->
                    payload.optString("commandId") == COMMAND_ID
                }
            assertEquals("succeeded", result.getJSONObject("payload").getString("status"))

            assertTrue(socket.close(1000, "instrumentation complete"))
            val lifecycleDirectory = HeadlessHostStorage.lifecycleDirectory(context)
            val lockFile = File(lifecycleDirectory, LifecycleEvidenceJournal.LOCK_FILE_NAME)
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { lockChannel ->
                lockChannel.lock().use {
                    val completedBefore =
                        countLifecycleEventWithoutLock(
                            lifecycleDirectory,
                            LifecycleEvent.SAFE_STOP_COMPLETED.wireName,
                        )
                    // The default instrumentation process owns the OS lock while :agent performs
                    // safe stop. Connector shutdown must happen before lifecycle fsync, while the
                    // terminal record must remain blocked until this lock is released.
                    HeadlessAgentService.requestSafeStop(context)
                    // A start arriving behind SAFE_STOP must not survive the lifecycle queue.
                    HeadlessAgentService.requestStart(context)
                    awaitUnavailable(client, "$DEVICE_HTTP_ORIGIN/healthz", STOP_TIMEOUT_MILLIS)
                    assertUnavailableFor(client, "$DEVICE_HTTP_ORIGIN/healthz", 500L)
                    assertEquals(
                        "agent lifecycle evidence bypassed the cross-process lock",
                        completedBefore,
                        countLifecycleEventWithoutLock(
                            lifecycleDirectory,
                            LifecycleEvent.SAFE_STOP_COMPLETED.wireName,
                        ),
                    )
                }
            }

            val lifecycleRecords =
                awaitLifecycleRecords(context, STOP_TIMEOUT_MILLIS) { records ->
                    records.any {
                            it.epochMillis >= lifecycleEpochFloor &&
                                it.pid == agentPid &&
                                it.event == LifecycleEvent.RUNTIME_STARTED.wireName
                        } &&
                        records.any {
                            it.epochMillis >= lifecycleEpochFloor &&
                                it.pid == agentPid &&
                                it.event == LifecycleEvent.SAFE_STOP_COMPLETED.wireName
                        }
                }
            assertTrue(lifecycleRecords.any { it.pid == agentPid })
            assertUnavailableFor(client, "$DEVICE_HTTP_ORIGIN/healthz", 2_000L)
            serviceMayBeRunning.set(false)

            awaitCommandAuditEvidence(
                file = auditFile,
                startOffset = auditStartOffset,
                commandId = COMMAND_ID,
                acknowledgedDigest = acknowledgedDigest,
                timeoutMillis = STOP_TIMEOUT_MILLIS,
            )
        } finally {
            socket?.cancel()
            bestEffortSafeStop(context, client, serviceMayBeRunning.get())
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun ordinaryAgentProcessDeathRestartsWithNewPidAndDurableEvidence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lifecycleEpochFloor = System.currentTimeMillis()
        val client =
            OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
        val serviceMayBeRunning = AtomicBoolean(false)
        try {
            serviceMayBeRunning.set(true)
            HeadlessAgentService.requestStart(context)
            awaitHttp(client, "$DEVICE_HTTP_ORIGIN/healthz", START_TIMEOUT_MILLIS).close()
            val oldPid = awaitAgentPid(context, excludedPid = null, START_TIMEOUT_MILLIS)
            awaitLifecycleRecords(context, START_TIMEOUT_MILLIS) { records ->
                records.any {
                    it.epochMillis >= lifecycleEpochFloor &&
                        it.pid == oldPid &&
                        it.event == LifecycleEvent.RUNTIME_STARTED.wireName
                }
            }

            // The instrumentation process has the same application UID but is distinct from the
            // :agent service process, so it can inject an ordinary process death and survive to
            // inspect START_STICKY recovery. This is not force-stop and does not model it.
            android.os.Process.killProcess(oldPid)

            val newPid = awaitAgentPid(context, excludedPid = oldPid, RESTART_TIMEOUT_MILLIS)
            assertTrue("agent service must restart in a different process", newPid != oldPid)
            awaitHttp(client, "$DEVICE_HTTP_ORIGIN/healthz", RESTART_TIMEOUT_MILLIS).close()

            awaitLifecycleRecords(context, RESTART_TIMEOUT_MILLIS) { records ->
                records.any {
                        it.epochMillis >= lifecycleEpochFloor &&
                            it.pid == newPid &&
                            it.event == LifecycleEvent.RUNTIME_STARTED.wireName
                    } &&
                    records.any {
                        it.epochMillis >= lifecycleEpochFloor &&
                            it.pid == newPid &&
                            it.event == LifecycleEvent.STICKY_RESTART_OBSERVED.wireName
                    } &&
                    records.any {
                        it.epochMillis >= lifecycleEpochFloor &&
                            it.pid == newPid &&
                            it.event == LifecycleEvent.PROCESS_RECREATED.wireName
                    }
            }

            HeadlessAgentService.requestSafeStop(context)
            awaitUnavailable(client, "$DEVICE_HTTP_ORIGIN/healthz", STOP_TIMEOUT_MILLIS)
            serviceMayBeRunning.set(false)
        } finally {
            bestEffortSafeStop(context, client, serviceMayBeRunning.get())
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun bestEffortSafeStop(
        context: Context,
        client: OkHttpClient,
        serviceMayBeRunning: Boolean,
    ) {
        if (serviceMayBeRunning || isAgentServiceRunning(context)) {
            runCatching { requestSafeStopAndAwaitServiceDestroyed(context, client) }
        }
        // Cleanup must not mask the original assertion, but it must still wait for the command
        // surface to disappear before this test releases its client and the next test can start.
        runCatching {
            awaitUnavailable(client, "$DEVICE_HTTP_ORIGIN/healthz", STOP_TIMEOUT_MILLIS)
        }
    }

    private fun awaitHttp(client: OkHttpClient, url: String, timeoutMillis: Long): Response {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        var lastFailure: Throwable? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (response.isSuccessful) return response
                response.close()
            } catch (failure: Throwable) {
                lastFailure = failure
            }
            Thread.sleep(POLL_MILLIS)
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val agentPid = currentAgentPid(context)
        val lifecycleTail = readLifecycleDiagnosticTail(context)
        throw AssertionError(
            "HTTP endpoint did not become ready; agentPid=$agentPid lifecycleTail=[$lifecycleTail]",
            lastFailure,
        )
    }

    private fun awaitUnavailable(client: OkHttpClient, url: String, timeoutMillis: Long) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val reachable =
                runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { true }
                }.getOrDefault(false)
            if (!reachable) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("HTTP endpoint remained reachable after safe stop")
    }

    private fun assertUnavailableFor(client: OkHttpClient, url: String, durationMillis: Long) {
        val deadline = android.os.SystemClock.elapsedRealtime() + durationMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val reachable =
                runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use {
                        it.isSuccessful
                    }
                }.getOrDefault(false)
            if (reachable) throw AssertionError("HTTP endpoint restarted after safe stop")
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun awaitMessage(
        messages: List<String>,
        failure: AtomicReference<Throwable?>,
        type: String,
        payloadPredicate: (JSONObject) -> Boolean = { true },
    ): JSONObject {
        val deadline = android.os.SystemClock.elapsedRealtime() + MESSAGE_TIMEOUT_MILLIS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            failure.get()?.let { throw AssertionError("WebSocket failed", it) }
            synchronized(messages) {
                messages.asSequence()
                    .map(::JSONObject)
                    .firstOrNull { message ->
                        message.optString("type") == type &&
                            payloadPredicate(message.getJSONObject("payload"))
                    }
                    ?.let { return it }
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for console message type=$type; messages=$messages")
    }

    private fun firstType(messages: List<String>): String =
        synchronized(messages) {
            assertTrue("expected at least one server message", messages.isNotEmpty())
            JSONObject(messages.first()).getString("type")
        }

    private fun awaitCommandAuditEvidence(
        file: File,
        startOffset: Long,
        commandId: String,
        acknowledgedDigest: String,
        timeoutMillis: Long,
    ) {
        require(startOffset in 0..Int.MAX_VALUE.toLong()) { "evidence offset is out of range" }
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val records =
                runCatching {
                    val bytes = file.readBytes()
                    if (bytes.size < startOffset) {
                        emptyList()
                    } else {
                        val text =
                            bytes.copyOfRange(startOffset.toInt(), bytes.size)
                                .toString(Charsets.UTF_8)
                        if (text.isNotEmpty() && !text.endsWith('\n')) {
                            emptyList()
                        } else {
                            text.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
                        }
                    }
                }.getOrDefault(emptyList())
            val admitted =
                records.filter {
                    it.optString("kind") == "command_admitted" &&
                        it.optString("subjectId") == commandId
                }
            val completed =
                records.filter {
                    it.optString("kind") == "command_completed" &&
                        it.optString("subjectId") == commandId
                }
            if (admitted.size == 1 && completed.size == 1) {
                val admittedRecord = admitted.single()
                val completedRecord = completed.single()
                assertEquals("admission_passed", admittedRecord.getString("outcome"))
                assertEquals("succeeded", completedRecord.getString("outcome"))
                assertEquals(acknowledgedDigest, admittedRecord.getString("intentDigestSha256"))
                assertEquals(acknowledgedDigest, completedRecord.getString("intentDigestSha256"))
                val authorityDecisionId = admittedRecord.getString("authorityDecisionId")
                assertTrue("authority decision id must be non-blank", authorityDecisionId.isNotBlank())
                assertEquals(authorityDecisionId, completedRecord.getString("authorityDecisionId"))
                assertEquals(admittedRecord.getString("sessionId"), completedRecord.getString("sessionId"))
                assertEquals(admittedRecord.getString("leaseId"), completedRecord.getString("leaseId"))
                assertTrue("successful command completion reason must be null", completedRecord.isNull("reason"))
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError(
            "Audit appended to ${file.absolutePath} did not contain one correlated admitted/completed pair",
        )
    }

    private fun awaitLifecycleRecords(
        context: Context,
        timeoutMillis: Long,
        predicate: (List<LifecycleRecord>) -> Boolean,
    ): List<LifecycleRecord> {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val records = readAndValidateLifecycleRecords(context)
            if (records != null && predicate(records)) return records
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Lifecycle evidence did not reach the expected state")
    }

    private fun readAndValidateLifecycleRecords(context: Context): List<LifecycleRecord>? {
        val directory = HeadlessHostStorage.lifecycleDirectory(context)
        val lockFile = File(directory, LifecycleEvidenceJournal.LOCK_FILE_NAME)
        val records = mutableListOf<LifecycleRecord>()
        FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { lockChannel ->
            val fileLock =
                try {
                    lockChannel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (fileLock == null) return null
            fileLock.use {
                val files =
                    listOf(
                        File(directory, LifecycleEvidenceJournal.PREVIOUS_FILE_NAME),
                        File(directory, LifecycleEvidenceJournal.CURRENT_FILE_NAME),
                    )
                if (!files.last().isFile) return null
                files.filter(File::exists).forEach { file ->
                    assertTrue("lifecycle journal must be a regular file", file.isFile)
                    assertTrue(
                        "${file.name} exceeded its per-file bound",
                        file.length() <= LifecycleEvidenceJournal.DEFAULT_MAX_FILE_BYTES,
                    )
                    val text = file.readText(Charsets.UTF_8)
                    assertTrue("${file.name} contains a torn final record", text.endsWith('\n'))
                    text.split('\n').dropLast(1).forEach { line ->
                        assertTrue("${file.name} contains a blank JSONL record", line.isNotEmpty())
                        records += parseLifecycleRecord(line)
                    }
                }
            }
        }
        return records.takeIf { it.isNotEmpty() }
    }

    /** Timeout diagnostics must never block behind the journal's cross-process fsync lock. */
    private fun readLifecycleDiagnosticTail(context: Context): String {
        val directory = HeadlessHostStorage.lifecycleDirectory(context)
        return listOf(
                File(directory, LifecycleEvidenceJournal.PREVIOUS_FILE_NAME),
                File(directory, LifecycleEvidenceJournal.CURRENT_FILE_NAME),
            ).asSequence()
            .filter(File::isFile)
            .flatMap { file ->
                runCatching { file.readLines(Charsets.UTF_8).asSequence() }
                    .getOrDefault(emptySequence())
            }
            .mapNotNull { line -> runCatching { parseLifecycleRecord(line) }.getOrNull() }
            .toList()
            .takeLast(16)
            .joinToString(separator = ",") { record ->
                "${record.event}@${record.epochMillis}#${record.pid}"
            }
    }

    /** Caller owns the journal OS lock, so these files cannot rotate during this read. */
    private fun countLifecycleEventWithoutLock(
        directory: File,
        event: String,
    ): Int =
        listOf(
            File(directory, LifecycleEvidenceJournal.PREVIOUS_FILE_NAME),
            File(directory, LifecycleEvidenceJournal.CURRENT_FILE_NAME),
        ).filter(File::isFile).sumOf { file ->
            file.readLines(Charsets.UTF_8).count { line ->
                line.isNotBlank() && JSONObject(line).optString("event") == event
            }
        }

    private fun parseLifecycleRecord(line: String): LifecycleRecord {
        val json = JSONObject(line)
        val keys = mutableSetOf<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) keys += iterator.next()
        assertEquals(LIFECYCLE_RECORD_KEYS, keys)
        assertEquals(1, json.getInt("schema"))

        val event = json.getString("event")
        val trigger = json.getString("trigger")
        val epochMillis = json.getLong("epoch_ms")
        val pid = json.getInt("pid")
        assertTrue("unsupported lifecycle event", event in LIFECYCLE_EVENTS)
        assertTrue("unsupported lifecycle trigger", trigger in LIFECYCLE_TRIGGERS)
        assertTrue("lifecycle epoch must be positive", epochMillis > 0L)
        assertTrue("lifecycle PID must be positive", pid > 0)
        return LifecycleRecord(event, trigger, epochMillis, pid)
    }

    private fun awaitAgentPid(
        context: Context,
        excludedPid: Int?,
        timeoutMillis: Long,
    ): Int {
        val expectedProcessName = "${context.packageName}:agent"
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val pid =
                activityManager.runningAppProcesses.orEmpty()
                    .firstOrNull { process -> process.processName == expectedProcessName }
                    ?.pid
            if (pid != null && pid > 0 && pid != excludedPid) return pid
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Agent process $expectedProcessName did not reach the expected PID state")
    }

    private fun currentAgentPid(context: Context): Int? {
        val expectedProcessName = "${context.packageName}:agent"
        return context.getSystemService(ActivityManager::class.java)
            .runningAppProcesses.orEmpty()
            .firstOrNull { process -> process.processName == expectedProcessName }
            ?.pid
            ?.takeIf { it > 0 }
    }

    @Suppress("DEPRECATION")
    private fun isAgentServiceRunning(context: Context): Boolean {
        val component = ComponentName(context, HeadlessAgentService::class.java)
        return context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { service -> service.service == component }
    }

    private fun requestSafeStopAndAwaitServiceDestroyed(context: Context, client: OkHttpClient) {
        val destroyedBefore =
            awaitLifecycleRecords(context, ISOLATION_TIMEOUT_MILLIS) { true }
                .count { it.event == LifecycleEvent.SERVICE_DESTROYED.wireName }
        HeadlessAgentService.requestSafeStop(context)
        awaitUnavailable(client, "$DEVICE_HTTP_ORIGIN/healthz", STOP_TIMEOUT_MILLIS)
        awaitLifecycleRecords(context, ISOLATION_TIMEOUT_MILLIS) { records ->
            !isAgentServiceRunning(context) &&
                records.count { it.event == LifecycleEvent.SERVICE_DESTROYED.wireName } >
                    destroyedBefore
        }
    }

    private fun takeoffRequest(leaseId: String): String =
        """
        {
          "protocolVersion":"1.0",
          "messageId":"android-command-message-001",
          "type":"command_request",
          "payload":{
            "commandId":"$COMMAND_ID",
            "leaseId":"$leaseId",
            "action":"takeoff",
            "ttlMs":2000
          }
        }
        """.trimIndent()

    private data class LifecycleRecord(
        val event: String,
        val trigger: String,
        val epochMillis: Long,
        val pid: Int,
    )

    private companion object {
        const val DEVICE_HTTP_ORIGIN = "http://127.0.0.1:8080"
        const val FORWARDED_BROWSER_ORIGIN = "http://127.0.0.1:18080"
        const val START_TIMEOUT_MILLIS = 120_000L
        const val STOP_TIMEOUT_MILLIS = 45_000L
        const val RESTART_TIMEOUT_MILLIS = 120_000L
        const val MESSAGE_TIMEOUT_MILLIS = 60_000L
        const val ISOLATION_TIMEOUT_MILLIS = 90_000L
        const val ISOLATION_STABLE_MILLIS = 2_000L
        const val POLL_MILLIS = 100L
        const val LEASE_REQUEST_ID = "android-lease-message-001"
        const val COMMAND_ID = "android-takeoff-command-001"
        val LIFECYCLE_RECORD_KEYS = setOf("schema", "event", "trigger", "epoch_ms", "pid")
        val LIFECYCLE_EVENTS = LifecycleEvent.entries.mapTo(mutableSetOf(), LifecycleEvent::wireName)
        val LIFECYCLE_TRIGGERS =
            LifecycleTrigger.entries.mapTo(mutableSetOf(), LifecycleTrigger::wireName)
        val CLIENT_HELLO =
            """
            {
              "protocolVersion":"1.0",
              "messageId":"android-client-hello-001",
              "type":"client_hello",
              "payload":{
                "clientName":"android-instrumentation",
                "clientVersion":"0.2.0",
                "supportedProtocolVersions":["1.0"],
                "authentication":null
              }
            }
            """.trimIndent()
        val LEASE_ACQUIRE =
            """
            {
              "protocolVersion":"1.0",
              "messageId":"$LEASE_REQUEST_ID",
              "type":"lease_acquire",
              "payload":{"requestedTtlMs":2000}
            }
            """.trimIndent()
    }
}
