## 2025-05-15 - Optimize ZRLE decoder performance
**Learning:** In graphics decoding loops (like ZRLE), integer division (`/`) and modulo (`%`) for coordinate calculation are expensive on mobile ARM CPUs. Replacing them with incremental counters and leveraging native bulk memory operations via `java.util.Arrays.fill` provides significant speedup.
**Action:** Always prefer incremental counters and bulk array operations over pixel-by-pixel loops with coordinate arithmetic in performance-critical rendering paths.
