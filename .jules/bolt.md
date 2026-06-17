## 2025-05-14 - [ZRLE Decoder Performance Optimization]
**Learning:** On mobile ARM architectures, integer division and modulo are significant bottlenecks in graphics decoding loops. Replacing coordinate arithmetic with incremental counters and leveraging native bulk memory operations (`Arrays.fill`) provides substantial performance gains. Additionally, increasing Zlib buffers to 16KB+ significantly reduces JNI overhead between the JVM and native Inflater.

**Action:** Always prefer incremental counters over division/modulo in tight loops. Use `Arrays.fill` for contiguous pixel spans. Ensure decompression buffers are at least 16KB to optimize JNI transitions.
