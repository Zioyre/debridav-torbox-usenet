# VPS Deployment Guide

How to deploy the DebriDav TorBox stack to a fresh VPS for sharing.

## Quick Start

1. Install Docker, rclone, fuse3
2. Build Docker image from source (GHCR only has tagged releases)
3. Mount rclone WebDAV on host, bind-mount into containers
4. Start stack with `docker compose up -d`
5. Configure *arr apps via REST API

Full step-by-step: see the `torbox-stack` skill's `references/vps-deployment.md`.

## Critical Pitfalls

### 1. rclone VFS cache MUST be on disk, never `/tmp`

On Ubuntu VPS, `/tmp` is often a tmpfs (RAM disk, ~3.8GB). When the rclone VFS cache fills it:
- Docker exec fails: `no space left on device`
- FUSE mount goes stale: `Transport endpoint is not connected`
- Files only in the local cache (not yet flushed to TorBox) are **permanently lost**
- Sonarr/Radarr lose track of imported files

**Fix:** Use `--cache-dir /var/cache/rclone-vfs` with `--vfs-cache-max-size 20G` and `--vfs-cache-max-age 24h`.

### 2. Use `--vfs-cache-mode writes`, not `off` or `full`

| Mode | Media playback | Imports | Risk |
|------|---------------|---------|------|
| `off` | ❌ ffprobe fails | ❌ | Never use |
| `writes` | ✅ buffers writes locally | ✅ | Recommended |
| `full` | ✅ | ❌ Files vanish from listings | Avoid |

`writes` mode buffers writes in the local VFS cache (avoids the WebDAV PUT bug on large files) and provides enough read caching for ffprobe/ffmpeg.

### 3. Docker restart vs stop+start after FUSE recovery

After recovering a stale FUSE mount, you MUST use `docker stop && docker start` — NOT `docker restart`. The `restart` command fails with:
```
error while creating mount source path '/home/ubuntu/debridav-mounted': 
mkdir /home/ubuntu/debridav-mounted: file exists
```

**Fix:**
```bash
for c in sonarr-debridav radarr-debridav jellyfin-debridav; do
    sudo docker stop $c && sudo docker start $c
done
```

### 4. Stale FUSE mount recovery

When the mount shows "Transport endpoint is not connected":
```bash
sudo fusermount -uz /home/ubuntu/debridav-mounted  # force unmount
mount | grep debridav  # should return nothing
sudo systemctl restart rclone-debridav
ls /home/ubuntu/debridav-mounted/  # verify
# Then stop+start all containers (see #3)
```

### 5. Remote path mappings must match actual download paths

DebriDav reports file paths inside its own container (e.g., `/data/data/debridav-downloads/`). Sonarr/Radarr need remote path mappings to translate these. If the mapping is wrong, imports fail silently.

**Add both variants:**
```python
mappings = [
    {"host": "debridav", "remotePath": "/data/debridav-downloads/", "localPath": "/data/downloads/"},
    {"host": "debridav", "remotePath": "/data/data/debridav-downloads/", "localPath": "/data/downloads/"},
]
```

### 6. Sonarr file naming must use SXXEXX format

Sonarr's parser does NOT recognize:
- `"Breaking Bad S02 01.mkv"` (space instead of E)
- `"Ep 01 - Title.mkv"` (text-based episode numbers)

Files MUST use `SXXEXX` patterns. Shell fixes:
```bash
# Space format: "S02 01" → "S02E01"
for f in *"S02 "*.mkv; do
    ep=$(echo "$f" | grep -oP 'S\d+ \K\d+')
    mv "$f" "$(echo "$f" | sed "s/S02 $ep/S02E$ep/")"
done

# Ep format: "Ep 01 - Title.mkv" → "Show.S04E01.Title.mkv"
for f in Ep*.mkv; do
    ep=$(echo "$f" | grep -oP 'Ep \K\d+')
    title=$(echo "$f" | sed 's/^Ep [0-9][0-9] - //' | sed 's/\.mkv$//')
    mv "$f" "Breaking.Bad.S04E${ep}.${title}.mkv"
done
```

Then trigger: `POST /api/v3/command {"name":"RescanSeries","seriesId":<id>}`

### 7. Stuck queue items — bulk delete

When many queue items are stuck as `completed`/`warning`, individual DELETE returns 404. Use:
```
DELETE /api/v3/queue/bulk?removeFromClient=true&blocklist=false&redownload=false
Body: {"ids": [id1, id2, ...]}
```

⚠️ `removeFromClient=true` also removes torrents from the download client.

### 8. FUSE copy fragility

- `shutil.copy2` fails with `[Errno 5] Input/output error` on rclone FUSE (xattr copy fails). Use `shutil.copy` or raw `cp`.
- `os.walk()` hangs on large FUSE directory trees. Use `os.listdir()` with explicit depth.
- FUSE copies involve download+upload through the VPS — a 3GB file takes ~5 minutes. Use `mv` (WebDAV MOVE) when source and destination are on the same mount for instant renames.

### 9. WebDAV PUT fails on large files

DebriDav's Milton WebDAV throws HTTP 500/501 on uploads over a few KB. Server-side MOVE (rename) works fine. The `--vfs-cache-mode writes` buffers FUSE writes locally to work around this.

### 10. TorBox API key handling

- `createtorrent` requires `multipart/form-data`, not JSON
- `checkcached` returns snapshot only — tells you what's hot NOW, not what was cached before
- Re-submitting a magnet always triggers a fresh download (no dedup barrier)
- Cloudflare blocks default Python User-Agent — set `User-Agent: DebriDav/0.12-torbox-usenet`

---

For the complete deployment guide with Tailscale/UFW security, FlareSolverr proxy setup, Prowlarr indexer configuration, Seerr integration, and OVH rescue recovery, see the `torbox-stack` skill's `references/vps-deployment.md`.
