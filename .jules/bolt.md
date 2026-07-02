## 2025-05-15 - ZRLE Decoder Optimization
**Learning:** On mobile ARM architectures (Android), graphics decoding performance is heavily impacted by JVM/Native transition overhead and inefficient pixel-by-pixel loops. Increasing zlib buffers to 16KB and leveraging `java.util.Arrays.fill` (which maps to native `memset`) provides significant speedups. Additionally, avoiding integer division and modulo in tight loops by using incremental counters prevents CPU-intensive arithmetic bottlenecks.
**Action:** Always look for bulk memory operations (like `Arrays.fill`) and fast-path buffer reads when dealing with stream-based decoding. Ensure zlib-related buffers are at least 16KB to minimize JNI overhead.

## 2025-05-16 - Strength Reduction in Graphics Loops
**Learning:** Replacing integer division (/), modulo (%), and repeated multiplications (*) with incremental additions (strength reduction) provides significant performance gains in graphics loops, especially on ARM architectures where division can take 20-80 cycles while addition takes only 1. Inlining simple checks (like `avail == 0` in `readByte`) in extremely hot paths also reduces function call overhead.
**Action:** Identify and eliminate expensive arithmetic (/, %, *) in tight loops by manually tracking offsets and using incremental counters. Use local checks to guard expensive method calls in high-frequency code paths.
