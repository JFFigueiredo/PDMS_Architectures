# PDMS Architectures — benchmark harness

Five deployment variants of one Personal Data Management System under a shared contract, used in the campaign dataset deposited on Zenodo.

| Doc | Purpose |
|-----|---------|
| [`DEPLOYMENT_GUIDE.md`](DEPLOYMENT_GUIDE.md) | Rebuild VMs, compose, JWT setup |
| [`ARCHITECTURES_FOR_BENCHMARK.md`](ARCHITECTURES_FOR_BENCHMARK.md) | Variant design and fairness |
| [`JMETER_TEST_METHODOLOGY.md`](JMETER_TEST_METHODOLOGY.md) | Load-test protocol |
| [`OUTBOX_RUNTIME_CONTROL.md`](OUTBOX_RUNTIME_CONTROL.md) | Combined in-memory business HTTP/outbox flag, ordinary type allowlists and independent decentralized ledger |

**Dataset** (raw captures, aggregates, scripts): see Zenodo DOI in the companion paper — not stored in this repo.

**Licence:** Apache-2.0 ([`LICENSE`](LICENSE)). JWT keys are generated locally (`DEPLOYMENT_GUIDE.md` §2); they are not committed.

**Release:** `v1.0.0` matches the Paper 1 campaign harness snapshot.

## Shared deployment migration owner

The [runtime guide](OUTBOX_RUNTIME_CONTROL.md) describes gateway-managed enable/disable
and restart reconciliation. All five architectures can stay running while only one
serves business HTTP and consumes ordinary outbox work. Decentralized's ledger stays
permitted independently. [Local validation](../papers/todo/06-dynamic-switching-DEFERRED/experiments/runtime-smoke/VALIDATION_2026-10-21_MEMORY.md)
records the tested cases and the existing synchronous-microservices Kafka gap.

For the current five-architecture deployment, [PDMS Gateway embeds Flyway](../papers/todo/06-dynamic-switching-DEFERRED/implementation/gateway/docs/MIGRATIONS.md)
and migrates the shared schema, including the ledger tables, before backends start.
Flyway defaults to disabled in all nine backend processes (`PDMS_FLYWAY_ENABLED=false`).
Compose also sets `SPRING_FLYWAY_ENABLED` explicitly, including for older images.
Isolated standalone deployments can opt in with `PDMS_FLYWAY_ENABLED=true`; keep it
disabled on every backend sharing the gateway-managed database.
