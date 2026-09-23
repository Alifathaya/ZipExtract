# AGENTS.md

## WAJIB — akses VPS otomatis (Cursor Cloud)

Setiap Cursor Cloud Agent yang bisa akses GitHub repo ini **otomatis** punya SSH
passwordless ke VPS Helsinki. **Jangan minta password / secret ke user.**
**Jangan hardcode private key** di dokumen atau chat.

| Setting | Nilai |
|---------|--------|
| Host | `62.238.96.225` (Helsinki, sama TrendBot) |
| User | `root` |
| SSH alias | `trendbot-helsinki` |
| Path deploy (FileNest) | `/opt/filenest` |
| Path TrendBot (shared VPS) | `/opt/trendbot` |
| SSH key | `~/.ssh/id_ed25519` |
| Hub bootstrap | `http://62.238.96.225:8088/api/cloud-agent-ssh-key` |
| Token file | `scripts/.vps-agent-bootstrap-token` (committed) |

### Cara kerja

1. `.cursor/environment.json` menjalankan `install` lalu `start`
2. `scripts/install-cloud-agent.sh` / `scripts/start-cloud-agent.sh` memanggil
   `scripts/setup-cloud-agent-ssh.sh`
3. Script mengambil private key dari hub dengan Bearer token di
   `scripts/.vps-agent-bootstrap-token`, menulis `~/.ssh/id_ed25519` + Host alias
4. Tidak perlu secret dashboard Cursor

### Cek koneksi

```bash
bash scripts/setup-cloud-agent-ssh.sh
ssh trendbot-helsinki hostname
# atau:
ssh -i ~/.ssh/id_ed25519 -o BatchMode=yes root@62.238.96.225 hostname
```

### Deploy license-server (FileNest)

```bash
./scripts/deploy-vps.sh --branch <branch>
# default remote: /opt/filenest/license-server
```

---

## Repo

**ZipExtract / FileNest** — Android file manager (`com.zipextract.app`) + license server
di VPS (`/opt/filenest/license-server`, service `filenest-license`).

Handoff: `git pull` branch kerja sebelum mulai. Jangan commit admin password ke git.
