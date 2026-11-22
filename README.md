Name is ironic given recent events

# minecraft2shell

This private reproduction uses one server-delivered resource pack to launch Windows Calculator from the Mojang-signed Minecraft 26.3 client. The [writeup](WRITEUP.md) explains the native TrueType primitive and the complete chain from font bytecode to the Windows API call.

From 64-bit Python 3 on Windows, run:

```bash
python poc.py
```

The script prepares official Minecraft dependencies on first use, starts a local server and visible stock client, serves `resource-pack.zip`, and sends the fixed F/A font sequence. The terminal shows the pack's numeric memory layout, glyph code points, and each font face as it is sent. Calculator remains open for inspection when the proof succeeds. Generated dependencies and run output stay in ignored local directories.

The PoC configures its own localhost server as trusted for promptless pack loading. The same pack can also be used with normal resource-pack acceptance on an untrusted server.
