# Bolt's Performance Journal

## 2025-05-15 - Optimizing ZRLE Decoder on Android/ARM
**Learning:** On mobile ARM architectures (as used in Podroid), integer division (`/`) and modulo (`%`) operations are significant bottlenecks when executed millions of times per second in graphics decoding loops. Additionally, small Zlib decompression buffers (e.g., 256 bytes) cause excessive JNI transition overhead between the JVM and the native `Inflater` implementation.

**Action:** Replace coordinate arithmetic in loops with incremental counters and use `Arrays.fill` for bulk memory operations. Ensure decompression buffers are at least 16KB to minimize JNI overhead.
