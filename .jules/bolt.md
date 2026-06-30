## 2025-05-15 - ZRLE Decoder Optimization
**Learning:** On mobile ARM architectures (Android), graphics decoding performance is heavily impacted by JVM/Native transition overhead and inefficient pixel-by-pixel loops. Increasing zlib buffers to 16KB and leveraging `java.util.Arrays.fill` (which maps to native `memset`) provides significant speedups. Additionally, avoiding integer division and modulo in tight loops by using incremental counters prevents CPU-intensive arithmetic bottlenecks.
**Action:** Always look for bulk memory operations (like `Arrays.fill`) and fast-path buffer reads when dealing with stream-based decoding. Ensure zlib-related buffers are at least 16KB to minimize JNI overhead.

## 2025-05-16 - Graphics Loop Optimization
**Learning:** Performance profiling on mobile ARM architectures confirms that integer division (/), modulo (%), and repeated multiplications in tight graphics loops (like VNC/ZRLE decoding) are major CPU bottlenecks. Replacing these with incremental counters and pre-calculating row base offsets significantly reduces CPU cycles per pixel.
**Action:** In any high-frequency graphics or stream processing loop, eliminate expensive arithmetic by maintaining state (like current column or row base) across iterations.
