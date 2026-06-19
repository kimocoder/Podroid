## 2026-06-19 - ZrleDecoder Performance Patterns
**Learning:** On mobile ARM architectures, integer division and modulo are significant bottlenecks in graphics decoding loops. Additionally, the JNI overhead between the JVM and native zlib (Inflater) can be minimized by using larger buffers (16KB+).
**Action:** Replace coordinate arithmetic with incremental counters and native bulk memory operations like java.util.Arrays.fill. Use larger buffers for JNI-intensive tasks.
