package poc

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object Bootstrap {
    const val VERSION = "26.3"
    const val CLIENT_SHA256 = "4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d"
    const val FREETYPE_SHA256 = "fcac5b5a9a8977f1bc76eccea5e71f6c57250ca5ab8605f06906377e91b6e6b7"
    const val VERSION_INDEX_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    const val JAVA_INDEX_URL =
        "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun digest(path: Path, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun fetchJson(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "Minecraft-Resource-Pack-PoC/1")
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()
        val response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("HTTP ${response.statusCode()} fetching $url")
        }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    fun officialFile(
        url: String,
        destination: Path,
        sha1: String? = null,
        size: Long? = null
    ): Path {
        destination.parent?.let { Files.createDirectories(it) }
        if (Files.isRegularFile(destination)) {
            val sizeOk = size == null || Files.size(destination) == size
            val hashOk =
                sha1 == null || digest(destination, "SHA-1").equals(sha1, ignoreCase = true)
            if (sizeOk && hashOk) {
                return destination
            }
        }
        val temporary = destination.resolveSibling("${destination.fileName}.download")
        var lastError: Throwable? = null
        for (attempt in 0 until 3) {
            try {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Minecraft-Resource-Pack-PoC/1")
                    .timeout(Duration.ofSeconds(90))
                    .GET()
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temporary))
                if (response.statusCode() !in 200..299) {
                    throw RuntimeException("HTTP ${response.statusCode()} for $url")
                }
                if (size != null && Files.size(temporary) != size) {
                    throw RuntimeException("Downloaded size mismatch for $url")
                }
                if (sha1 != null && !digest(temporary, "SHA-1").equals(sha1, ignoreCase = true)) {
                    throw RuntimeException("Downloaded checksum mismatch for $url")
                }
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                return destination
            } catch (e: Exception) {
                lastError = e
                Files.deleteIfExists(temporary)
                if (attempt < 2) {
                    Thread.sleep((attempt + 1) * 1000L)
                }
            }
        }
        throw RuntimeException("Could not download $url: $lastError", lastError)
    }

    private fun DataOutputStream.writeNbtString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeShort(encoded.size)
        write(encoded)
    }

    fun trustedServersFile(path: Path) {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.write(byteArrayOf(0x0a, 0x00, 0x00))
            out.writeByte(0x09)
            out.writeNbtString("servers")
            out.writeByte(0x0a)
            out.writeInt(1)
            out.writeByte(0x01)
            out.writeNbtString("hidden")
            out.writeByte(0x01)
            out.writeByte(0x08)
            out.writeNbtString("ip")
            out.writeNbtString("127.0.0.1:25565")
            out.writeByte(0x08)
            out.writeNbtString("name")
            out.writeNbtString("Minecraft Server")
            out.writeByte(0x01)
            out.writeNbtString("acceptTextures")
            out.writeByte(0x01)
            out.write(byteArrayOf(0x00, 0x00))
        }
        Files.write(path, baos.toByteArray())
    }

    fun javaVersionMatches(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        return try {
            val process = ProcessBuilder(path.toString(), "-version")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            val output = process.inputStream.bufferedReader().readText()
            output.contains("version \"25.0.1")
        } catch (_: Exception) {
            false
        }
    }

    fun javaCandidates(): List<Path> {
        val candidates = mutableListOf<Path>()
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let {
            candidates.add(Paths.get(it, "bin", "java.exe"))
        }
        System.getenv("PATH")?.split(System.getProperty("path.separator"))?.forEach { dir ->
            if (dir.isNotBlank()) {
                val exe = Paths.get(dir, "java.exe")
                if (Files.isRegularFile(exe)) candidates.add(exe)
            }
        }
        val userHome = Paths.get(System.getProperty("user.home"))
        val appdata =
            System.getenv("APPDATA")?.let { Paths.get(it) } ?: userHome.resolve("AppData/Roaming")
        candidates.add(appdata.resolve(".minecraft/runtime/java-runtime-epsilon/windows-x64/java-runtime-epsilon/bin/java.exe"))

        val local = System.getenv("LOCALAPPDATA")?.let { Paths.get(it) }
            ?: userHome.resolve("AppData/Local")
        val packages = local.resolve("Packages")
        if (Files.isDirectory(packages)) {
            Files.newDirectoryStream(packages, "Microsoft.4297127D64EC6_*").use { stream ->
                for (pkg in stream) {
                    candidates.add(pkg.resolve("LocalCache/Local/runtime/java-runtime-epsilon/windows-x64/java-runtime-epsilon/bin/java.exe"))
                }
            }
        }
        return candidates
    }

    fun officialJava(root: Path, metadata: Path): Path {
        for (candidate in javaCandidates()) {
            if (javaVersionMatches(candidate)) {
                return candidate.toAbsolutePath().normalize()
            }
        }
        println("Preparing official Mojang Java 25.0.1 runtime...")
        val runtimeIndex = fetchJson(JAVA_INDEX_URL)
        val epsilonList = runtimeIndex
            .getAsJsonObject("windows-x64")
            .getAsJsonArray("java-runtime-epsilon")
        val match = epsilonList.map { it.asJsonObject }
            .firstOrNull { it.getAsJsonObject("version").get("name").asString == "25.0.1" }
            ?: throw RuntimeException("Mojang Java 25.0.1 runtime is unavailable")

        val entry = match.getAsJsonObject("manifest")
        val manifestPath = officialFile(
            entry.get("url").asString,
            metadata.resolve("java-runtime-epsilon-25.0.1.json"),
            entry.get("sha1").asString,
            entry.get("size").asLong
        )
        val manifest = JsonParser.parseString(
            Files.readString(
                manifestPath,
                StandardCharsets.UTF_8
            )
        ).asJsonObject
        val runtimeRoot = root.resolve("runtime/java-runtime-epsilon")
        Files.createDirectories(runtimeRoot)

        val filesObj = manifest.getAsJsonObject("files")
        for ((relative, valueElem) in filesObj.entrySet()) {
            val item = valueElem.asJsonObject
            val target = relative.split("/").fold(runtimeRoot) { acc, part -> acc.resolve(part) }
            when (val type = item.get("type").asString) {
                "directory" -> Files.createDirectories(target)
                "file" -> {
                    val raw = item.getAsJsonObject("downloads").getAsJsonObject("raw")
                    officialFile(
                        raw.get("url").asString,
                        target,
                        raw.get("sha1").asString,
                        raw.get("size").asLong
                    )
                }

                else -> throw RuntimeException("Unsupported Mojang Java runtime entry ($type): $relative")
            }
        }
        val path = runtimeRoot.resolve("bin/java.exe")
        if (!javaVersionMatches(path)) {
            throw RuntimeException("Downloaded Mojang runtime is not Java 25.0.1")
        }
        return path
    }

    fun versionMetadata(metadata: Path): JsonObject {
        val path = metadata.resolve("26.3.json")
        if (!Files.isRegularFile(path)) {
            val userHome = Paths.get(System.getProperty("user.home"))
            val appdata = System.getenv("APPDATA")?.let { Paths.get(it) }
                ?: userHome.resolve("AppData/Roaming")
            val installed = appdata.resolve(".minecraft/versions/$VERSION/$VERSION.json")
            if (Files.isRegularFile(installed)) {
                Files.copy(installed, path, StandardCopyOption.REPLACE_EXISTING)
            } else {
                val index = fetchJson(VERSION_INDEX_URL)
                val entry = index.getAsJsonArray("versions")
                    .map { it.asJsonObject }
                    .firstOrNull { it.get("id").asString == VERSION }
                    ?: throw RuntimeException("Minecraft 26.3 is absent from Mojang version metadata")
                officialFile(entry.get("url").asString, path, entry.get("sha1").asString)
            }
        }
        val value =
            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
        if (value.get("id").asString != VERSION) {
            throw RuntimeException("Unexpected Minecraft version metadata")
        }
        return value
    }

    fun libraryAllowed(library: JsonObject): Boolean {
        val rules = library.getAsJsonArray("rules") ?: return true
        var allowed = false
        for (ruleElem in rules) {
            val rule = ruleElem.asJsonObject
            val osName = rule.getAsJsonObject("os")?.get("name")?.asString ?: "windows"
            if (osName == "windows") {
                allowed = rule.get("action").asString == "allow"
            }
        }
        return allowed
    }

    fun prepareAssets(root: Path, version: JsonObject): Path {
        val indexInfo = version.getAsJsonObject("assetIndex")
        val assetId = indexInfo.get("id").asString
        val assets = root.resolve("assets")
        val indexPath = officialFile(
            indexInfo.get("url").asString,
            assets.resolve("indexes/$assetId.json"),
            indexInfo.get("sha1").asString,
            indexInfo.get("size").asLong
        )
        val index =
            JsonParser.parseString(Files.readString(indexPath, StandardCharsets.UTF_8)).asJsonObject
        val objects = index.getAsJsonObject("objects")

        val userHome = Paths.get(System.getProperty("user.home"))
        val appdata =
            System.getenv("APPDATA")?.let { Paths.get(it) } ?: userHome.resolve("AppData/Roaming")
        val installed = appdata.resolve(".minecraft/assets")
        val installedIndex = installed.resolve("indexes/$assetId.json")

        if (Files.isRegularFile(installedIndex) &&
            digest(installedIndex, "SHA-1").equals(
                indexInfo.get("sha1").asString,
                ignoreCase = true
            )
        ) {
            var complete = true
            for ((_, itemElem) in objects.entrySet()) {
                val item = itemElem.asJsonObject
                val hash = item.get("hash").asString
                val size = item.get("size").asLong
                val value = installed.resolve("objects/${hash.substring(0, 2)}/$hash")
                if (!Files.isRegularFile(value) || Files.size(value) != size) {
                    complete = false
                    break
                }
            }
            if (complete) {
                return installed
            }
        }

        var count = 0
        for ((_, itemElem) in objects.entrySet()) {
            val item = itemElem.asJsonObject
            val checksum = item.get("hash").asString
            val size = item.get("size").asLong
            val prefix = checksum.substring(0, 2)
            val target = assets.resolve("objects/$prefix/$checksum")
            if (!Files.isRegularFile(target) || Files.size(target) != size) {
                val source = installed.resolve("objects/$prefix/$checksum")
                target.parent?.let { Files.createDirectories(it) }
                if (Files.isRegularFile(source) && Files.size(source) == size) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    officialFile(
                        "https://resources.download.minecraft.net/$prefix/$checksum",
                        target,
                        checksum,
                        size
                    )
                }
            }
            count++
            if (count % 1000 == 0) {
                println("Assets ready: $count")
            }
        }
        return assets
    }

    fun prepareDependencies(repositoryRoot: Path): Map<String, String> {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        if (!osName.contains("win") || (!osArch.contains("64"))) {
            throw RuntimeException("This PoC requires 64-bit Windows")
        }
        val repoRoot = repositoryRoot.toAbsolutePath().normalize()
        val root = repoRoot.resolve("dependencies")
        val metadata = root.resolve("metadata")
        Files.createDirectories(metadata)

        println("Preparing official Minecraft 26.3 files...")
        val version = versionMetadata(metadata)
        val downloads = version.getAsJsonObject("downloads")
        val clientInfo = downloads.getAsJsonObject("client")
        val serverInfo = downloads.getAsJsonObject("server")

        val client = officialFile(
            clientInfo.get("url").asString,
            root.resolve("client/minecraft-26.3-client.jar"),
            clientInfo.get("sha1").asString,
            clientInfo.get("size").asLong
        )
        val server = officialFile(
            serverInfo.get("url").asString,
            root.resolve("server/minecraft-26.3-server.jar"),
            serverInfo.get("sha1").asString,
            serverInfo.get("size").asLong
        )
        if (digest(client, "SHA-256") != CLIENT_SHA256) {
            throw RuntimeException("Minecraft client differs from the tested signed 26.3 build")
        }

        val loggingInfo = version.getAsJsonObject("logging")
            .getAsJsonObject("client")
            .getAsJsonObject("file")
        val logging = officialFile(
            loggingInfo.get("url").asString,
            root.resolve("logging/${loggingInfo.get("id").asString}"),
            loggingInfo.get("sha1").asString,
            loggingInfo.get("size").asLong
        )

        println("Preparing libraries and native FreeType...")
        val libraries = mutableListOf<String>()
        val natives = root.resolve("natives")
        Files.createDirectories(natives)

        for (libElem in version.getAsJsonArray("libraries")) {
            val library = libElem.asJsonObject
            val name = library.get("name").asString
            if (!libraryAllowed(library) ||
                name.endsWith(":natives-windows-arm64") ||
                name.endsWith(":natives-windows-x86")
            ) {
                continue
            }
            val artifact = library.getAsJsonObject("downloads").getAsJsonObject("artifact")
            val artifactRelPath = artifact.get("path").asString.split("/")
                .fold(root.resolve("libraries")) { acc, part -> acc.resolve(part) }
            val path = officialFile(
                artifact.get("url").asString,
                artifactRelPath,
                artifact.get("sha1").asString,
                artifact.get("size").asLong
            )
            if (name.endsWith(":natives-windows")) {
                ZipInputStream(Files.newInputStream(path)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.lowercase().endsWith(".dll")) {
                            val fileName = Paths.get(entry.name).fileName.toString()
                            val target = natives.resolve(fileName)
                            Files.newOutputStream(target).use { out ->
                                zis.copyTo(out)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                libraries.add(path.toString())
            }
        }

        val freetype = natives.resolve("freetype.dll")
        if (!Files.isRegularFile(freetype) || digest(freetype, "SHA-256") != FREETYPE_SHA256) {
            throw RuntimeException("FreeType DLL differs from the tested Minecraft 26.3 build")
        }

        val classpathFile = root.resolve("classpath.txt")
        Files.writeString(
            classpathFile,
            (libraries + client.toString()).joinToString(";"),
            StandardCharsets.UTF_8
        )

        val loggingPathFile = root.resolve("logging-path.txt")
        Files.writeString(loggingPathFile, logging.toString(), StandardCharsets.UTF_8)

        println("Preparing assets...")
        val assets = prepareAssets(root, version)
        val java = officialJava(root, metadata)

        val game = root.resolve("game")
        Files.createDirectories(game)
        val options = game.resolve("options.txt")
        Files.writeString(
            options,
            "version:5023 resourcePacks:[] incompatibleResourcePacks:[] fullscreen:false preferredGraphicsBackend:\"opengl\"",
            StandardCharsets.UTF_8
        )
        val trusted = game.resolve("servers.dat")
        trustedServersFile(trusted)

        val manifest = linkedMapOf(
            "client_jar" to client.toString(),
            "server_runtime_jar" to server.toString(),
            "server_launch_mode" to "jar",
            "natives_dir" to natives.toString(),
            "assets_dir" to assets.toString(),
            "asset_index" to version.getAsJsonObject("assetIndex").get("id").asString,
            "classpath_file" to classpathFile.toString(),
            "logging_path_file" to loggingPathFile.toString(),
            "options_file" to options.toString(),
            "trusted_servers_dat" to trusted.toString(),
            "java_path" to java.toString()
        )
        val manifestPath = root.resolve("manifest.json")
        Files.writeString(manifestPath, gson.toJson(manifest), StandardCharsets.UTF_8)
        println("Official dependencies ready")
        return manifest
    }
}
