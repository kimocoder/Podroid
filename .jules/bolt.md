## 2025-05-15 - ZRLE decoding bottlenecks on mobile ARM
**Learning:** Integer division and modulo operations in tight graphics decoding loops are significantly more expensive on mobile ARM architectures than simpler increments. Additionally, JNI overhead for small buffer zlib inflation can consume up to 20% of decoding time.
**Action:** Always replace coordinate arithmetic (x/w, x%w) with incremental counters in row-major loops and use 16KB+ buffers for native compression/decompression calls.
