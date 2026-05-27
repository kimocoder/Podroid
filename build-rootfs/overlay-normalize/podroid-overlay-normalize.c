/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * podroid-overlay-normalize <upper_dir> <work_dir>
 *
 * One-time cleanup of a legacy overlayfs upper that was built with
 * metacopy=on,redirect_dir=on,index=on against an OLD lower squashfs, so it is
 * safe under the new PLAIN overlay against the NEW lower:
 *
 *   - remove <work_dir>/index            (stale index from index=on)
 *   - for each upper entry with <ns>metacopy : unlink it (metadata-only copy-up,
 *       its data lived in the old lower; removing it lets the new lower show)
 *   - for each upper dir with <ns>redirect  : removexattr it (so the dir merges
 *       with the new lower at its natural path)
 *   - everything else (real files, whiteouts, full copy-ups) is left untouched.
 *
 * <ns> is "trusted.overlay." in production; the host test overrides it via
 * PODROID_NORMALIZE_NS to use "user.overlay." (no CAP_SYS_ADMIN needed).
 *
 * Conservative + idempotent: re-running on an already-normalized upper is a
 * no-op (no entries carry the xattrs anymore).
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>

static char metacopy_xattr[128];
static char redirect_xattr[128];

/* Returns 1 if path has the named xattr, 0 otherwise. */
static int has_xattr(const char *path, const char *name) {
    ssize_t r = lgetxattr(path, name, NULL, 0);
    return r >= 0;
}

static void rm_rf(const char *path) {
    struct stat st;
    if (lstat(path, &st) != 0) return;
    if (S_ISDIR(st.st_mode)) {
        DIR *d = opendir(path);
        if (d) {
            struct dirent *e;
            while ((e = readdir(d)) != NULL) {
                if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
                char child[4096];
                snprintf(child, sizeof(child), "%s/%s", path, e->d_name);
                rm_rf(child);
            }
            closedir(d);
        }
        rmdir(path);
    } else {
        unlink(path);
    }
}

static void walk(const char *dir) {
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        char path[4096];
        snprintf(path, sizeof(path), "%s/%s", dir, e->d_name);
        struct stat st;
        if (lstat(path, &st) != 0) continue;

        if (!S_ISDIR(st.st_mode)) {
            /* metadata-only copy-up: its data was in the old lower. Remove so
             * the new lower's version surfaces. Real user files (no metacopy
             * xattr) are left alone. */
            if (has_xattr(path, metacopy_xattr)) {
                if (unlink(path) != 0)
                    fprintf(stderr, "normalize: unlink %s: %s\n", path, strerror(errno));
            }
            continue;
        }

        /* Directory: clear a stale redirect so it merges naturally, then recurse. */
        if (has_xattr(path, redirect_xattr)) lremovexattr(path, redirect_xattr);
        walk(path);
    }
    closedir(d);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: podroid-overlay-normalize <upper_dir> <work_dir>\n");
        return 2;
    }
    const char *ns = getenv("PODROID_NORMALIZE_NS");
    if (!ns || !*ns) ns = "trusted.overlay.";
    snprintf(metacopy_xattr, sizeof(metacopy_xattr), "%smetacopy", ns);
    snprintf(redirect_xattr, sizeof(redirect_xattr), "%sredirect", ns);

    char index_path[4096];
    snprintf(index_path, sizeof(index_path), "%s/index", argv[2]);
    rm_rf(index_path);

    walk(argv[1]);
    return 0;
}
