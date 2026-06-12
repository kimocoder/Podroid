# Bolt's Performance Journal

## 2025-05-15 - Optimizing ZRLE Decoding for VNC/X11

**Learning:** On mobile ARM architectures (Android), integer division (`/`) and modulo (`%`) are significantly slower than simple arithmetic and incremental counters. Additionally, small buffer sizes (e.g., 4KB or less) cause excessive JNI transitions when using native zlib (`Inflater`). Bulk memory operations like `IntArray.fill` are much more efficient than manual pixel loops.

**Action:**
- Replace coordinate arithmetic (`pos / tw`, `pos % tw`) in graphics loops with incremental row/column counters.
- Use decompression buffers of at least 16KB (fitting a 64x64 raw tile, which is ~12KB).
- Merge input/state logic into the main decoder to eliminate per-rectangle object allocations.
- Reset internal buffer state (`zPos`, `zAvail`) at the start of each decoding unit to ensure isolation while preserving session-long zlib state.
