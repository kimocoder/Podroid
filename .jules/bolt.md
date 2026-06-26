## 2026-06-26 - ZRLE Decoder Performance Patterns
**Learning:** Pixel-by-pixel rendering with coordinate arithmetic (division/modulo) is a major bottleneck on mobile ARM architectures. Internal decompression buffers should be sized at 16KB+ to minimize JNI transition overhead when using native Inflater.
**Action:** Use incremental row/column counters and java.util.Arrays.fill for bulk operations in graphics decoding loops. Optimize pixel read methods to check buffer availability once rather than for every byte.
