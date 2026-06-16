## 2026-06-16 - Optimize ZRLE Decoder
**Learning:** In the ZRLE decoder, per-rectangle object allocation of `ZInput` and a small 256-byte decompression buffer were creating significant overhead. On mobile ARM, integer division/modulo in the RLE loop was a hidden bottleneck.
**Action:** Merge `ZInput` state into the main decoder, increase the decompression buffer to 16KB, use incremental counters for RLE, and leverage `java.util.Arrays.fill` for bulk pixel operations.
