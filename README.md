# cloud-itonami-5610

Open Business Blueprint for **ISIC Rev.5 5610**: restaurants and mobile food
service activities (restaurants, food trucks, food-prep and delivery
operations).

This repository designs a forkable OSS business for community food service
operations: hygiene certification, robotics-assisted food preparation and
delivery, and incident reporting — run by a qualified operator so a food
service business keeps its own hygiene and incident records instead of
renting a closed operations platform.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (food-prep, plating,
in-restaurant/last-mile delivery) operate under an actor that proposes
actions and an independent **Food Service Governor** that gates them. The
governor never dispatches hardware itself; `:high`/`:safety-critical`
actions (allergen handling, food-temperature control, cross-contamination
risk) require human sign-off.

## Core Contract

```text
intake + identity + hygiene certification + food-prep/delivery mission
        |
        v
Food Service Advisor -> Food Service Governor -> certification, dispatch, incident report, or human approval
        |
        v
robot actions (gated) + service record + incident report + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, issue
a hygiene certification outside its verified inspection scope, or publish
an incident report without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `5610`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Operations-coordination actor (`restaurantops`)

This repo also implements the langgraph-clj `cloud-itonami-isic-*` actor
pattern: a `RestaurantAdvisor` (contained intelligence node) sealed behind
an independent `RestaurantGovernor`, gating every proposal before it can
touch the SSoT. Today's implementation is **coordination-only** — it never
performs or authorizes direct robot/kitchen-equipment actuation or a
food-safety-clearance decision itself; the robotics-premised physical
dispatch layer described above is a separate, not-yet-wired capability
this actor's proposals could eventually feed into, always gated the same
way.

### Features

- **Closed proposal-op allowlist**: `log-service-record`,
  `schedule-staffing-operation`, `coordinate-supply-order`,
  `flag-food-safety-concern` (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Location unverified** — the target location's business
     registration + health permit must exist AND be independently
     registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — finalizing a food-safety-clearance decision,
     overriding an allergen-exclusion requirement, direct
     kitchen-equipment actuation, and food-safety-authority enforcement
     (health-department clearance, inspection sign-off, license/permit
     actions) are permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-food-safety-concern` — ALWAYS escalates, regardless of
    confidence or phase. A "flag a concern" op is never auto-commit
    eligible and never finalizes a food-safety-authority decision itself
    — it only surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold — a large-value
    procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: service-record logging only (approval-gated)
  - Phase 2: + staffing-operation scheduling, supply-order proposals
    (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (food-safety concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Out of scope (structural, not a rollout milestone)

This actor is **operations coordination only**. It never performs or
authorizes:

- Finalizing a food-safety-clearance decision.
- Overriding an allergen-exclusion requirement.
- Direct kitchen-equipment actuation or control (ovens, fryers, walk-in
  coolers, etc.).
- Food-safety-authority enforcement (health-department clearance,
  inspection sign-off, license suspension, compliance enforcement).

The governor's `scope-exclusion-violations` check re-scans every proposal
for this failure mode independently of the advisor's own framing, and
treats it as a HARD, permanent block regardless of confidence or how
clean everything else is.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

### Test suite

- `test/restaurantops/governor_test.clj` — unit tests of governor hard
  checks, scope exclusion, and the self-trip regression test
- `test/restaurantops/advisor_test.clj` — advisor proposal shape and
  consistency
- `test/restaurantops/phase_test.clj` — rollout phase logic
- `test/restaurantops/governor_contract_test.clj` — full graph
  integration, audit trail
- `test/restaurantops/store_contract_test.clj` — Store protocol and
  MemStore implementation

### Modules

- `restaurantops.store` — SSoT (MemStore, String-keyed location
  directory, append-only ledger)
- `restaurantops.advisor` — contained intelligence node (mock +
  real-LLM seam)
- `restaurantops.governor` — independent compliance layer
- `restaurantops.phase` — staged rollout (0→3)
- `restaurantops.operation` — langgraph-clj StateGraph
- `restaurantops.sim` — demo driver

## License

AGPL-3.0-or-later.
