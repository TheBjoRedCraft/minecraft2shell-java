# Minecraft 26.3 resource-pack RCE: TrueType bytecode to Calculator

This is a build-specific proof of code execution in the **Mojang-signed Minecraft Java 26.3 Windows client** with bundled FreeType 2.14.3 x64. A server supplies one resource pack containing crafted TrueType fonts, then sends ordinary actionbar text. Font hinting reaches a native out-of-bounds write, and the font's bytecode turns it into an ASLR-aware control-flow transfer. The benign action launches `calc.exe` and exits the client.

The PoC artifact is [resource-pack.zip](resource-pack.zip), SHA-1 `F2E2859318152E32FC331D2291C2EC40FFB3C372` for the server's `resource-pack-sha1` setting.

## 1. Native primitive: a composite-glyph SHZ sweep

The entry point is TrueType hinting during FreeType glyph loading. The crafted composite uses a 100-real-point grooming glyph to obtain an allocation for **104 `FT_Vector` points**, or `0x340` bytes. Its nested component creates a zone whose `cur` pointer begins at point buffer `P + 0x208`, with 66 apparent points. In FreeType 2.14.3 `src/truetype/ttinterp.c`, `Ins_SHZ` takes its limit from the zone's final contour and calls `Move_Zp2_Point` for each index without checking the physical point allocation. The crafted displacement is `+0x80` on the x axis, sweeping vector x lanes through `P + 0x410`, past the allocation ending at `P + 0x340`.

The useful heap layout has the live 832-byte `TT_ExecContextRec` at `P + 0x350`. The sweep then reaches interpreter fields, including `error` at context offset `+0x20` and the zone `cur` pointer at `+0xC0`. Adding `0x80` to the former produces FreeType's `Invalid_Opcode` error value. FreeType's error dispatch finds the font-defined `IDEF` for opcode `0x36` (SHZ), so execution enters a recovery program supplied by the same font. Adding `0x80` to `zp2.cur` changes the interpreter's later point operations into reads and writes of context memory. This is a same-glyph transition: the font does not need a second pack or a process-address oracle.

This geometry is conditional on allocator placement. The delivered pack includes 144 distinct font resources and exercises them in a fixed order to increase the chance that at least one has the required point/context adjacency. The sender never reads addresses or chooses a face from a scan.

## 2. Bytecode recovery and writable state

The IDEF body first uses `GC` to read the swept interpreter callback and the glyph-instruction pointer as low and high 32-bit values. It then uses `SHPIX` to repair the swept heap header and the context fields needed by the interpreter and allocator. The repair is deliberately limited to indices whose tag writes remain inside the 104-point tag buffer. The final builder also appends an inverse SHZ on the normal, non-IDEF path.

Before staging the payload, the bytecode checks that the context has the expected relative FreeType callback layout, that the presumed instruction pointer is the **currently advancing** IDEF instruction pointer, and that active storage equals the persistent glyph storage after its first `WS`. These checks reduce false context matches. They are not a formal proof that every near layout is harmless; a nonmatching SHZ sweep can still touch native memory before a check runs.

The `F` glyph performs a genuine TrueType `WS`, causing FreeType to allocate persistent `glyfStorage` for 16,384 32-bit cells. The `A` glyph's IDEF program writes the full ROP data there using `WS`, including pointers assembled from live values. The emitted bytecode handles 32-bit carry and borrow when constructing 64-bit addresses. No C-side writes or process-memory patching occur in the delivered run.

## 3. Native stack pivot with API headroom

The font declares `maxp.maxStackElements = 8192`. In this FreeType build, `src/truetype/ttobjs.c` assigns `stackSize = maxStackElements + max(maxStackElements / 2, 128)`, yielding **12,288 `FT_Long` cells = 49,152 bytes** for the TrueType VM stack. The bytecode preserves `exec->stack` and copies a small bootstrap to its base. It pushes 7,164 filler cells, then 94 ROP cells copied from persistent storage, placing the native chain at `exec->stack + 0x7000`. The final VM top is 7,262 cells, below the 12,288-cell allocation. The interior chain address is 16-byte aligned and leaves about 28 KiB below native `RSP` for Windows API frames.

At the end of the IDEF body, `SCFS` changes the interpreter callback to FreeType RVA `0x162506` and the context slot at `+0x28` to the in-module `pop rsp; ret` gadget at RVA `0x1A0AE`. The callback transfers native execution through that gadget to the bootstrap at the **unchanged** VM-stack base; the bootstrap pivots to the chain at `stack + 0x7000`. The final write changes the slot that is normally `stackSize`, and no later bytecode opcode needs to push onto the VM stack.

The interior placement gives the Windows APIs space to create native stack frames beneath the initial pivot address. The declared VM stack size, filler count, and final top are chosen together so that the ROP chain stays within the allocation.

## 4. Runtime addresses and API chain

The delivered font contains **RVAs, not absolute process addresses**. `GC` reads the live relocated FreeType interpreter pointer; bytecode arithmetic derives other FreeType gadgets and IAT cells from it, handling low-word carry or borrow. It also reads the live `glyfStorage` and original VM-stack pointers from the context to form heap-relative and stack-relative qwords. In particular, the ROP output slot is rebased from the storage layout to `stack + 0x7080`.

The ROP chain calls `GetModuleHandleExW` through FreeType's IAT cell at RVA `0x1B3238` to obtain `kernel32.dll`, then `GetProcAddress` through IAT RVA `0x1B3118` to resolve `WinExec`. It passes `calc.exe` to `WinExec`, and then calls `ExitProcess` through IAT RVA `0x1B3230`. The chain includes Windows x64 alignment, return addresses, and shadow-space gaps. It uses existing module code and imports; there is no injected native shellcode or executable heap. The exact gadget and IAT RVAs are tied to the tested FreeType binary.

## 5. One pack and ordinary server delivery

The [144-face pack](resource-pack.zip) has `pack.mcmeta` plus 144 JSON font definitions and 144 same-length TTF variants. Each JSON definition has a `space` provider before the `ttf` provider. The space provider gives `A`, `F`, and `G` advance `64.0`; `@` is unmasked. The unmasked `@` retains and grooms the TTF during font reload. The large advance makes the space glyph unsuitable for the actionbar's actual glyph selection, so the later server-sent `F` and `A` reach the TTF provider and trigger native glyph loads. Distinct font names preserve separate faces while keeping glyph data and allocation sizes equal.

The server advertises the ZIP through ordinary `server.properties`: `resource-pack`, `resource-pack-sha1`, `resource-pack-id`, and `require-resource-pack=true`. It sends actionbars using the ordinary `title ... actionbar` command: **F to faces 000–143**, waits about 15 seconds, then **A to faces 000–143** until the client exits. The fixed sender uses RCON only to issue those server commands; it has no address input, scan, or adaptive face choice. The test world was peaceful with monster spawning disabled.

The promptless setup uses an already trusted server in `servers.dat`. A client that has not trusted the server may show the normal pack-acceptance prompt. The PoC does not bypass that consent step.

## Reproduction

On Windows x64 with 64-bit Python 3, run this from the release directory:

```bash
python poc.py
```

`poc.py` prepares the official Minecraft 26.3 dependencies, starts a server and the Mojang-signed client, serves the included ZIP through standard `server.properties`, waits for the pack to load, and sends the fixed F-all/A-all actionbar sequence. It prints `CALCULATOR_PROCESS` and `POC_SUCCESS` when a new Calculator process appears, reports whether a Calculator window is visible, writes a result under `runs/`, and leaves Calculator open for inspection. The client exits after the ROP chain calls `ExitProcess`.

The runner configures the local server as trusted for the promptless path. For a separate, untrusted server, the user must accept the normal resource-pack prompt before the font is processed. The exploit's address derivation is independent of ASLR for this exact native build; the context offsets and gadget RVAs are not claimed to apply to other Minecraft or FreeType builds.
