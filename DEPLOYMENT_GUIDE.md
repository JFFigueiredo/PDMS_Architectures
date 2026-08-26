# PDMS – Deployment Guide (benchmarks)

How to rebuild the **five-host** campaign layout, run the five PDMS variants, and (optionally)
populate a fresh `data/` tree. Compose files and app sources live in **this** `PDMS/` folder.
Measured facts (CPU model, image tags, applied cgroup limits) are in the deposit:
`data/VMs/VM_INFRASTRUCTURE_2026-08-14.md` and `data/VMs/env_facts_*/`.

Use placeholders (`<db-host>`, `<app-host>`, …) — never commit live cloud IPs, SSH keys, or passwords.

---

## 0. Two ways to use the deposit

| Goal | What you need | Doc |
|------|---------------|-----|
| **Use the published dataset** | Unpack Zenodo tarball; run `scripts/compare_reps.py` / `compare_apps.py` on bundled `data/` | deposit `README.md` |
| **Re-run the campaign** | Five VMs, SSH keys, this guide + `PDMS/scripts/data_collector/auto_collect.sh` | §1–§12 below |

The deposit ships **75 repetitions** in `data/` (~12 GB). Most reusers only need the first row.
Section **§12** is for anyone who must **reproduce raw captures from scratch**.

Required deposit layout (for analysis **and** for `auto_collect.sh`):

```
<root>/
  data/              ← raw captures; auto_collect writes here
  results/           ← optional reference aggregates
  derived/           ← provenance audit tables
  scripts/           ← compare_reps.py, compare_apps.py, …
  PDMS/              ← this folder (compose, sources, JMeter, collectors)
```

Run collectors from a machine that has **`data/` and `PDMS/` as siblings** (same as the live
`analysis/` tree or the Zenodo tarball root).

---

## 1. Hosts (all identical size)

| Role | Used by | Size (campaign) |
|------|---------|-----------------|
| **App** | all five architectures | Azure `Standard_D2s_v6`, 2 vCPU / 8 GiB, Ubuntu 24.04 |
| **DB + Kafka** | all | same |
| **JMeter** | all | same |
| **Observability** | all | same |
| **Besu** | `decentralized` only | same |

One region, UTC. Four VMs for monolith / microservices / async / faas; **five** when running decentralized.

App budget (verified applied): **1.8 CPU / 6144 MiB** total per architecture
(microservices split 1.0 + 0.5 + 0.3). Full limits: `data/VMs/VM_INFRASTRUCTURE_*.md` §3.

There is **no automated cloud provisioner** in this repo — you create the VMs yourself (any provider)
matching the sizes above, then follow §2–§6.

---

## 2. Install once per VM

**All five:** Ubuntu 24.04 LTS · Docker Engine + Compose plugin (campaign: Docker **29.7.2**) · user in `docker` group:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
# log out/in, then: docker --version && docker compose version
```

**JMeter only:** OpenJDK 17 · Apache JMeter **5.6.3** · optional `jmeter/bin/setenv.sh`:
`HEAP="-Xms1g -Xmx2g -XX:MaxMetaspaceSize=256m"` (campaign default was ~1g).

Copy the **`PDMS/jmeter/`** tree to `~/jmeter` on the JMeter VM (runners + `scripts/*.jmx`).
Campaign load uses **`run-step-load.sh`**, **`run-read-heavy.sh`**, **`run-write-heavy.sh`**
— see `JMETER_TEST_METHODOLOGY.md`.

**App only:** rsync this `PDMS/` tree to the App VM (§6) · build images for the variant · mint a
**fresh local JWT** for JMeter (§7). App signing keys (`jwt-keys/`) are **not** shipped in the
deposit — generate before build/run:

```bash
cd pdms-monolith   # or any variant module
mvn -q exec:java -Dexec.mainClass=ipl.pdms.util.GenerateJwtKeys
mvn -q exec:java -Dexec.mainClass=ipl.pdms.util.GenerateJwtToken
```

Copy `public-key.pem` (and mount `private-key.pem` at runtime via `jwt-keys/`) consistently across
variants if you share one key pair.

DB / Observability / Besu need only Docker beyond the compose files below.

---

## 3. What runs where

| VM | Start with (paths relative to `PDMS/`) | Ports | Software |
|----|----------------------------------------|-------|----------|
| **DB + Kafka** | `docker-compose_DB_KAFKA.yml` | 5432, 9092 | `postgres:15`, `confluentinc/cp-kafka:7.4.0` (`max_connections=250`) |
| **App** | `docker-compose.<variant>.yml` | 8080 (microservices also 8081/8082) | built `pdms-*`; Temurin **25** |
| **Observability** | `docker-compose.observability.yml` (or `…-microservices.yml`) | 9090, 3000 | `prom/prometheus:v3.11.3`, `grafana/grafana:13.0.1` |
| **Besu** | `docker-compose.besu.yml` | 8545, 9545 | `hyperledger/besu:24.3` → `pdms-besu:24.3` (**decentralized only**) |
| **JMeter** | `~/jmeter/` runners + `.jmx` | outbound to App | JMeter 5.6.3 |

### Software pins (campaign)

| Component | Version |
|-----------|---------|
| OS | Ubuntu 24.04.4 LTS |
| Docker | 29.7.2 (cgroup v2) |
| PostgreSQL | 15.x (`postgres:15`) |
| Kafka | Confluent 7.4.0 |
| Prometheus | v3.11.3 |
| Grafana | 13.0.1 |
| Besu | 24.3.3 |
| JMeter | 5.6.3 |
| App JVM | Temurin 25 |
| JMeter JVM | OpenJDK 17 |

---

## 4. Configuration (`.env` on App)

Same for every variant. On the App VM (e.g. `~/PDMS/.env`):

```env
POSTGRES_HOST=<db-host>
KAFKA_HOST=<db-host>
POSTGRES_PORT=5432
KAFKA_PORT=9092
POSTGRES_USER=pdms_user
POSTGRES_PASSWORD=<local-password>
```

**Decentralized only** — also set:

```env
PDMS_BLOCKCHAIN_ENABLED=true
PDMS_BLOCKCHAIN_RPC_URL=http://<besu-host>:8545
PDMS_BLOCKCHAIN_PRIVATE_KEY=<fresh-local-key>
```

Use a **fresh** signing key; never ship campaign keys.

---

## 5. How you run for benchmarks

- **One container (port 8080):** monolith, faas, or decentralized — run, measure, stop, then next variant.
- **Three containers:** microservices or microservices-async (person + consent + snapshot). Ports 8080 / 8081 / 8082.
- Only **one** variant set at a time. Stop before switching (§6).

| Variant | Compose file (campaign) | Build hint | API ports |
|---------|-------------------------|------------|-----------|
| monolith | `docker-compose.monolith.yml` | `docker build -f pdms-monolith/Dockerfile -t pdms-monolith:latest pdms-monolith/` | 8080 |
| faas | **`docker-compose.faas.remote.yml`** | `docker build -f pdms-faas/Dockerfile -t pdms-faas:latest pdms-faas/` | 8080 |
| decentralized | `docker-compose.decentralized.yml` | build app image; Besu on Besu VM | 8080 |
| microservices | `docker-compose.microservices.yml` | `docker compose -f docker-compose.microservices.yml build` | 8080–8082 |
| microservices-async | `docker-compose.microservices-async.yml` | `docker compose -f docker-compose.microservices-async.yml build` | 8080–8082 |

`docker-compose.faas.yml` is a **local-dev** overlay only; the campaign and `restart_all_services.sh`
use **`docker-compose.faas.remote.yml`** on the App VM.

---

## 6. Minimal run loop

1. Up **DB + Kafka** → **Observability** → **App** (one variant).  
2. Decentralized: up **Besu** before App.  
3. JMeter: `BASE_URL=http://<app-host>:8080` + campaign runners under `~/jmeter/`.  
4. Collect artifacts → `data/<variant>/<scenario>/<rep>/` (§12).  
5. Analyse with `scripts/compare_reps.py` / `compare_apps.py` from deposit root.

### Copy tree to App VM

From this `PDMS/` folder:

```bash
rsync -avz --progress \
  --exclude='.git' --exclude='**/target' \
  --exclude='**/jwt-keys' --exclude='**/.env' \
  ./ <ssh-user>@<app-host>:~/PDMS/
```

Also copy **`grafana/`** and **`grafana-data/`** to the Observability VM when using the bundled
dashboards. Copy **`docker/besu/`** build context to the Besu VM for `decentralized`.

Create `.env` on the App VM after copy (§4).

### Build / run / stop (App)

```bash
cd ~/PDMS
docker build -f pdms-monolith/Dockerfile -t pdms-monolith:latest pdms-monolith/   # example
docker compose -f docker-compose.monolith.yml up -d
curl -s http://localhost:8080/actuator/health
docker compose -f docker-compose.monolith.yml down    # before switching variant
```

DB / Observability / Besu (on respective VMs, from `~/PDMS`):

```bash
docker compose -f docker-compose_DB_KAFKA.yml up -d
docker compose -f docker-compose.observability.yml up -d
docker compose -f docker-compose.besu.yml up -d          # decentralized only
```

---

## 7. JWT (JMeter auth)

Every API call uses `Authorization: Bearer <token>`. The deposit **does not** include signing keys
or campaign tokens.

1. **Generate keys** (once per environment): §2 `GenerateJwtKeys` in a variant module.  
2. **Mount keys** at runtime: `jwt-keys/public-key.pem` + `private-key.pem` in the app container
   (compose volume or image build — see each `docker-compose.*.yml`).  
3. **Mint a token** for JMeter:

```bash
cd pdms-monolith
mvn -q exec:java -Dexec.mainClass=ipl.pdms.util.GenerateJwtToken
# copy printed token, or read from module jwt-keys/ as documented in that utility
```

4. **Pass to runners** on the JMeter VM:

```bash
export JWT_TOKEN="<your-token>"
BASE_URL=http://<app-host> ./run-step-load.sh monolith
```

Do **not** reuse tokens or keys from the published dataset.

---

## 8. Observability scrape

On the Observability host, point Prometheus at the App (replace `<app-host>`). Campaign bundles use
`prometheus.yml` or `prometheus.observability-microservices.yml` mounted by the observability compose files.

**Single app:**

```yaml
- job_name: 'pdms-app'
  metrics_path: '/actuator/prometheus'
  static_configs:
    - targets: ['<app-host>:8080']
  scrape_interval: 10s
```

**Microservices:**

```yaml
- job_name: 'pdms-microservices'
  metrics_path: '/actuator/prometheus'
  static_configs:
    - targets: ['<app-host>:8080', '<app-host>:8081', '<app-host>:8082']
  scrape_interval: 10s
```

Reload Prometheus after editing. Collection uses dashboard queries from
`grafana/dashboards/pdms-dashboard.json` via `data_view/collect_prometheus_metrics.sh` (synced by
`collect_all_remote_data.sh`).

---

## 9. Quick reference

| Step | Command |
|------|---------|
| Start DB/Kafka | `docker compose -f docker-compose_DB_KAFKA.yml up -d` |
| Start observability | `docker compose -f docker-compose.observability.yml up -d` |
| Start Besu | `docker compose -f docker-compose.besu.yml up -d` |
| Start variant | `docker compose -f docker-compose.<variant>.yml up -d` |
| Stop variant | `docker compose -f docker-compose.<variant>.yml down` |
| Health | `curl http://localhost:8080/actuator/health` |

---

## 10. Troubleshooting

| Issue | Action |
|-------|--------|
| Cannot reach Postgres/Kafka | From App: `nc -zv <db-host> 5432` and `9092`; open firewall between VMs |
| Port in use | `docker compose -f … down` before starting another variant |
| JWT / auth errors | Regenerate keys (§7); export fresh `JWT_TOKEN` on JMeter VM |
| Docker permission denied | `sudo usermod -aG docker $USER`, re-login |
| Container exits | `docker logs <name>` — check `.env` hosts/credentials |
| Microservices can’t see each other | Start all three via the same compose file (service DNS names) |
| `auto_collect` ssh fails | Check §12 SSH config and `KEYS_DIR` PEM paths |
| JMeter exit 127 | Set `JMETER_HOME=/opt/jmeter` (non-interactive ssh skips `.bashrc`) |

---

## 11. Do not publish

SSH keys, cloud IPs/usernames, DB/Grafana passwords, GitHub tokens, blockchain private keys,
hardcoded JWTs, `**/jwt-keys/` private material. Keep `grafana-data/` (dashboards).

---

## 12. Re-running the full campaign (populate `data/`)

This section closes the gaps needed to **clone and re-run without contacting the authors**.
Read `scripts/data_collector/AUTO_COLLECT.md` and `JMETER_TEST_METHODOLOGY.md` alongside this.

### 12.1 Prerequisites checklist

- [ ] Five Ubuntu 24.04 VMs (§1), Docker installed (§2), clocks in UTC  
- [ ] Deposit unpacked so **`data/` and `PDMS/` are siblings** (§0)  
- [ ] PEM key files on your **operator machine** (the host that runs `auto_collect.sh`)  
- [ ] `~/.ssh/config` aliases or exported `*_HOST` variables (§12.2)  
- [ ] `PDMS/` rsynced to App VM; `jmeter/` rsynced to JMeter VM; DB/Obs/Besu compose trees in place  
- [ ] Fresh JWT keys + `JWT_TOKEN` on JMeter VM (§7)  
- [ ] Empty or partial `data/<variant>/<scenario>/{1..5}/` directories (script fills gaps only)

### 12.2 SSH keys and host aliases

Collector scripts SSH from your operator machine to every VM. Set **`KEYS_DIR`** to the folder
that holds your PEM files (default: `$HOME/.ssh/pdms-keys`):

```bash
export KEYS_DIR="$HOME/.ssh/pdms-keys"
chmod 600 "$KEYS_DIR"/*.pem
```

Expected key filenames (rename yours to match, or edit the scripts):

| Key file | Used for |
|----------|----------|
| `PDMS_key_sec.pem` | App VM |
| `KafkaAndDB_sec.pem` | DB + Kafka VM |
| `Jmeter_key_sec.pem` | JMeter VM |
| `Observability_sec.pem` | Observability VM |
| `besu.pem` | Besu VM (`decentralized` only) |

**`~/.ssh/config` example** (adjust `HostName` / `User` / `IdentityFile`):

```
Host jmeter_sec
  HostName <jmeter-host>
  User <ssh-user>
  IdentityFile ~/.ssh/pdms-keys/Jmeter_key_sec.pem

Host dbandkafka_sec
  HostName <db-host>
  User <ssh-user>
  IdentityFile ~/.ssh/pdms-keys/KafkaAndDB_sec.pem
```

`auto_collect.sh` defaults: `JMETER_SSH=jmeter_sec`, `DB_SSH=dbandkafka_sec`. Override if you use
full `user@host` strings instead of aliases.

Edit **host placeholders** at the top of `scripts/data_collector/collect_all_remote_data.sh` and
`restart_all_services.sh` if you do not use SSH config aliases:

```bash
APP_HOST="<ssh-user>@<app-host>"
DB_HOST="<ssh-user>@<db-host>"
JMETER_HOST="<ssh-user>@<jmeter-host>"
PROM_HOST="<ssh-user>@<observability-host>"
BESU_HOST="<ssh-user>@<besu-host>"
```

### 12.3 Environment variables (operator machine)

| Variable | Default | Purpose |
|----------|---------|---------|
| `KEYS_DIR` | `$HOME/.ssh/pdms-keys` | Directory containing PEM files |
| `BASE_URL` | `http://<app-host>` | App URL for JMeter (include `:8080` if needed) |
| `JMETER_SSH` | `jmeter_sec` | SSH target for JMeter VM |
| `DB_SSH` | `dbandkafka_sec` | SSH target for Postgres connection drain before dump |
| `JWT_TOKEN` | *(unset)* | Set on JMeter VM or pass via runner env (§7) |
| `STEPS` | `2 5 15 50` | Concurrency ladder |
| `STEP_DURATION` | `300` | Seconds per step |

Prometheus collection pulls queries from `PDMS/data_view/collect_prometheus_metrics.sh` and
`PDMS/grafana/dashboards/pdms-dashboard.json` — both must stay in the synced `PDMS/` tree.

### 12.4 Automated collection (`auto_collect.sh`)

From **`PDMS/scripts/data_collector/`** on a machine that can SSH to all VMs and sees
`../../../data/` (deposit root):

```bash
export KEYS_DIR="$HOME/.ssh/pdms-keys"
export BASE_URL="http://<app-host>:8080"

# one architecture, all missing reps (mix → read → writte)
./auto_collect.sh monolith

# smoke test: single rep
./auto_collect.sh monolith mix 1
```

Supported architectures: `monolith`, `microservices`, `microservices-async`, `faas`, `decentralized`.

Per gap the script: (1) `restart_all_services.sh` — rebuild/recreate stacks, (2) wait for health,
(3) SSH to JMeter and run the scenario script, (4) `collect_all_remote_data.sh` — pull app/DB/Prom/JMeter/Besu/host metrics,
(5) validate file counts, (6) copy into `data/<arch>/<scenario>/<rep>/`.

Scenario → folder mapping:

| Scenario dir | JMeter runner |
|--------------|---------------|
| `mix` | `run-step-load.sh` |
| `read` | `run-read-heavy.sh` |
| `writte` | `run-write-heavy.sh` |

Full campaign = **5 variants × 3 scenarios × 5 reps = 75** folders under `data/`.

### 12.5 Manual single-run (without `auto_collect`)

1. `restart_all_services.sh <variant>` from `PDMS/scripts/data_collector/`  
2. On JMeter VM: `JWT_TOKEN=… BASE_URL=http://<app-host>:8080 ./run-step-load.sh <variant>`  
3. From operator cwd: `./collect_all_remote_data.sh <stagingName> <variant> <scenario> [start_ts] [end_ts]`  
4. Validate and move artifacts into `data/<variant>/<scenario>/<rep>/` matching the layout in
   `data/validation_report.md` (Addendum 6).

### 12.6 After collection — verify and analyse

From **deposit root** (parent of `data/` and `PDMS/`):

```bash
# per cell
python3 scripts/compare_reps.py monolith mix
python3 scripts/compare_apps.py mix

# provenance audit (optional)
python3 scripts/attribution_table.py
```

Compare your `results/` to the bundled reference copies. Infrastructure facts for a new run:
`./PDMS/scripts/data_collector/collect_env_facts.sh` → `data/VMs/env_facts_<timestamp>/`.

### 12.7 What this repo does **not** automate

- Cloud VM creation (you match §1 sizes yourself)  
- Firewall rules between VMs (must allow App↔DB, JMeter→App, Prom→App, App↔Besu)  
- TLS / public DNS (campaign used private IPs + SSH)  
- Re-running is **expensive** (~75 × ~30 min load + collection per full campaign)

---

## Summary

1. **Reuse data:** unpack deposit → `scripts/compare_reps.py` (§0).  
2. **Re-run campaign:** five VMs (§1) · Docker (§2) · SSH keys + aliases (§12.2) · sync `PDMS/` + `jmeter/` · JWT (§7).  
3. Up DB+Kafka → Observability → (Besu) → App (§3, §6).  
4. `.env` on App (§4); one variant at a time (§5); faas uses **`docker-compose.faas.remote.yml`**.  
5. **`auto_collect.sh`** from `PDMS/scripts/data_collector/` (§12.4) → `data/`.  
6. Analyse from deposit root (§12.6).  
7. Keep secrets out of any public fork (§11).
