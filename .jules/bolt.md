## 2025-05-15 - Optimizing ZRLE Decoder Hot Path
**Learning:** Integer division and modulo operations inside hot loops (like pixel-by-pixel RLE decoding) are significant performance bottlenecks on mobile ARM architectures. Additionally, small buffer sizes for Zlib decompression lead to excessive JNI transitions.
**Action:** Always replace coordinate arithmetic (division/modulo) with incremental counters in rendering loops. Use `java.util.Arrays.fill` for bulk pixel operations by breaking runs into per-row segments. Ensure decompression buffers are at least 16KB to minimize JNI overhead.
