# Assistant evaluations

[Versión en español](../evaluations.md) · [README](../../README.md)

## Running tests and evaluations

```bash
cd ai-service
pip install -e ".[evals]" pytest ruff mypy
pytest -q
python -m evals.run --tag smoke
python -m evals.run --model claude-haiku-5-5 --model claude-sonnet-5-5 --out report.json
```

Live-model evaluations require ANTHROPIC_API_KEY and incur token costs. Scenarios are defined in YAML and use an in-memory fake core rather than the running Spring backend. They cover precise and ambiguous bookings, confirmations, grounding, off-topic requests, prompt injection, owner configuration and handoff. Deterministic checks run first; an LLM judge is used for tone. The smoke subset runs in CI when the agent changes and the API secret is available, as documented in the original README.

## Documented results

Run date: 2026-11-18. Prompt version: 2026-11-18.3.

| Model | Passed scenarios | Mean cost per scenario | Mean latency per scenario |
|---|---:|---:|---:|
| claude-haiku-5-5 | 29/29 (100%) | USD 0.00054 | 3.9 s |

The [runner](../../ai-service/evals/run.py) sums agent costs across all turns in each scenario and measures elapsed time for the entire scenario. The cost does not necessarily include judge usage. These are documented results on a simulated core, not production metrics or a guarantee of general accuracy.

An end-to-end test uncovered an issue not covered in the original evals: an extra “yes” after automatic booking caused the agent to attempt the same booking again and incorrectly say it was unavailable. The documented fix returns `already_booked` from prepare_booking; the regression scenario `booking_extra_yes_after_booked` covers the case.
