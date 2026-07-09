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

## License

AGPL-3.0-or-later.
