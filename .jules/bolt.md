## 2026-06-03 - [VNC Decoding Optimization]
**Learning:** In high-throughput decoders like VNC/ZRLE, JNI transition overhead from Inflater and arithmetic operations (division/modulo) inside tight loops for pixel positioning are significant bottlenecks. Replacing them with scanline-based incremental counters and Arrays.fill() native intrinsics yields better results than micro-optimizing the loop body.
**Action:** Prioritize scanline-based iteration and native bulk operations (like Arrays.fill or System.arraycopy) when dealing with 2D array manipulation in performance-critical paths.
