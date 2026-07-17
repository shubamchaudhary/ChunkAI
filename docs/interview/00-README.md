# LogLens — Interview Prep Pack

Four short docs, in the order you'd use them in an interview. They are the
*talking-track* companion to the full design in
[`../02-target-architecture.md`](../02-target-architecture.md). Read those, then
rehearse from these.

| File | Use it to answer... |
|---|---|
| [`01-data-flow.md`](01-data-flow.md) | "Walk me through your system." — the whiteboard diagram + every step and *why that step exists*. |
| [`02-design-defense.md`](02-design-defense.md) | "Why Kafka? Why blob storage? Why a table per session?" — each decision defended **with numbers** and its alternative. |
| [`03-honest-tradeoffs.md`](03-honest-tradeoffs.md) | "Where did you over-engineer?" — the honest self-critique. Saying this *first* is a strong signal. |
| [`04-questions-you-might-miss.md`](04-questions-you-might-miss.md) | The purpose of the graphs, failure modes, the anomaly-filter weakness, the cost model, security, testing — the questions that catch people out. |
| [`05-scale-math.md`](05-scale-math.md) | "Why does this architecture exist?" — the org-target numbers (250 services, 10⁵–10⁶ chunks/corpus) and the "design for your target, because architecture doesn't retrofit" thesis. |

## The one-sentence pitch

> "LogLens answers whole-corpus analytical questions over log archives by moving
> the LLM work from query-time to ingest-time: deterministic parsers extract
> exact numbers, LLMs explain only the anomalous windows, and everything is
> materialized into typed tables so user questions are answered by SQL —
> instant, exact, and hallucination-proof by construction."

## The one framing that wins the interview

Every decision below is framed as **"problem → cheapest correct solution → the
alternative I rejected → the number that proves it."** Interviewers don't grade
the tech you added; they grade whether you can *defend and criticize* it. This
pack is built to let you do both.
