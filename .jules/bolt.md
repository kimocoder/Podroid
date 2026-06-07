# Bolt's Performance Journal - Podroid

## 2025-05-15 - Optimizing ZRLE Decoding for X11/VNC
**Learning:** In high-frequency pixel loops on mobile ARM architectures, integer division and modulo operations are significant bottlenecks. Replacing them with incremental counters and pre-calculating row offsets yields measurable performance gains. Additionally, leveraging `java.util.Arrays.fill()` allows the JVM to use native, SIMD-accelerated memory operations, which is far superior to manual loops for block fills.
**Action:** Always audit tight loops for coordinate arithmetic (`/` and `%`) and replace with incremental pointer logic. Use bulk array operations whenever possible.

## 2025-05-15 - Zlib Buffer Tuning for JNI
**Learning:** JNI transition overhead between the JVM and native libraries (like `java.util.zip.Inflater`) can be minimized by using larger buffers. Sizing buffers at 16KB or larger reduces the number of native calls and improves throughput for decompression tasks.
**Action:** Standardize on 16KB buffers for JNI-backed streaming operations.
