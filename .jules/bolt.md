
## 2025-05-15 - Optimizing ZrleDecoder for ARM
**Learning:** On mobile ARM architectures, integer division and modulo are significant bottlenecks in graphics decoding loops. Replacing them with incremental counters and using larger buffers (16KB+) for Zlib decompression (to minimize JNI overhead) yields measurable performance gains in the X11 viewer.
**Action:** Always prefer incremental pointer/index updates over coordinate arithmetic in hot pixel-processing loops. Ensure Zlib buffers are sized to minimize JVM/native transitions (16KB+).
