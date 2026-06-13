## 2025-05-22 - Optimized ZRLE decoding
**Learning:** Performance profiling on mobile ARM architectures reveals that integer division and modulo operations in hot graphics decoding loops are significant bottlenecks. Additionally, small buffer sizes (e.g., 256 bytes) cause excessive JNI transitions when using native Inflater on the JVM.
**Action:** Replace coordinate arithmetic (division/modulo) with incremental counters. Use 16KB+ buffers for decompression and leverage `java.util.Arrays.fill` for bulk memory operations. Optimize pixel reading with bulk buffer access fast-paths.
