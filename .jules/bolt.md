## 2025-05-15 - [Optimization of ZrleDecoder]
**Learning:** Integer division and modulo are significant bottlenecks in graphics decoding loops on mobile ARM architectures. Furthermore, small buffer sizes for zlib inflation increase JNI overhead.
**Action:** Replace coordinate arithmetic with incremental counters and use 16KB+ buffers for inflation. Leverage `java.util.Arrays.fill` for native bulk operations.
