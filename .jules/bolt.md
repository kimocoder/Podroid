# Bolt Performance Journal

## 2025-05-23 - ZRLE Decoder Optimization
**Learning:** In graphics decoding loops on mobile ARM architectures, integer division (`/`) and modulo (`%`) are significant bottlenecks. Replacing them with incremental counters and using native bulk memory operations like `java.util.Arrays.fill` provides a measurable speed boost. Additionally, increasing buffer sizes to 16KB significantly reduces JNI overhead when using `Inflater`.
**Action:** Always look for opportunities to replace per-pixel loops with bulk operations and minimize coordinate math in performance-critical hot paths. Ensure Zlib buffers are sized to at least 16KB to balance memory use and JNI transition costs.
