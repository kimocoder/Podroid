## 2025-05-15 - Optimizing ZRLE Decoder Performance
**Learning:** In Android-based VNC/X11 decoders, JNI overhead for zlib inflation is significant; increasing buffers to 16KB+ reduces these transitions. Additionally, integer division and modulo in RLE decoding loops are expensive on mobile ARM architectures.
**Action:** Always favor manual row/column counters over division/modulo in hot graphics loops, and use `java.util.Arrays.fill` for contiguous pixel spans to leverage optimized native implementations.
