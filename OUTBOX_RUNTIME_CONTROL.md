# In-memory architecture enable flag

The gateway controls one `enabled` flag per architecture. It permits both business
HTTP and ordinary outbox processing. All backend processes start disabled; their
containers, health and authenticated management endpoints remain running.

Decentralized keeps processing hash-chain ledger messages when disabled. Besu
processing remains permitted when its existing startup blockchain capability is
available. The ordinary flag never pauses that ledger lane.

See the [gateway runtime guide](../papers/todo/06-dynamic-switching-DEFERRED/implementation/gateway/docs/RUNTIME.md) for the current API,
operator commands, handover acknowledgements, restart behavior and limitations.
The gateway updates all three processes of each split architecture. Use one gateway
writer and send workload traffic through it.

## Retained PDMS implementation

- `RuntimeOutboxControl`: local immutable flag/allowlist, revision and boot identity;
  transaction guard and acknowledgement after admitted work drains.
- `ArchitectureEnabledFilter`: gates `/api/**` using the same local flag.
- `OutboxControlController`: authenticated GET/PATCH at `/actuator/outbox-control`.
- Existing outbox repositories/services keep their scoped event filters and
  transaction-based row claims. The two async snapshot clients retain outstanding
  exchanges so disable can wait before their snapshot dependency stops.

The old `OutboxPolicy` DTO and nine backend `R__outbox_runtime_control.sql` resources
were removed. There are no policy/audit SQL queries in backend runtime control and
no dependency on `outbox_runtime_control` or `outbox_runtime_audit`. Existing old
control tables can remain unused. The gateway retains a no-op historical repeatable
for Flyway history compatibility; it does not drop existing tables.

Use the [gateway CLI](../PDMS_GATEWAY/scripts/runtime_control.py) for the current
contract. The older `scripts/outbox_control.py` targets the historical shared-policy
API and is retained for old deployments; it is not the current operator tool.

## Startup

Configure `PDMS_OUTBOX_ADMIN_TOKEN` consistently on the gateway and all backends.
The [gateway migrates the shared schema](../papers/todo/06-dynamic-switching-DEFERRED/implementation/gateway/docs/MIGRATIONS.md) before
backends start; backend Flyway defaults to `PDMS_FLYWAY_ENABLED=false`.
For decentralized's Besu worker, retain `PDMS_BLOCKCHAIN_ENABLED=true` and its
existing RPC/key configuration. This startup capability is separate from the
ordinary runtime flag.

Pending events remain in the actual outbox. Flag changes do not bypass outbox
creation for accepted business requests, alter direct consent Kafka publishers,
or make other architectures produce ledger anchors. Submitted external operations
can outlive a timeout; reconciliation remains necessary after uncertain failures.

Validation on 21 October passed 23 focused PDMS tests and 156 real Docker checks,
including all five architectures and actual ledger/Besu work after ordinary disable.
The gateway suite passed 170 tests. See the
[validation report](../papers/todo/06-dynamic-switching-DEFERRED/experiments/runtime-smoke/VALIDATION_2026-10-21_MEMORY.md)
for evidence and limits. Nothing in PDMS is staged or committed.
