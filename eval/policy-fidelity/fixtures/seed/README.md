# Seeded fixtures — policy-judge eval TEST COLLATERAL

These files are **not** miniforge components and **not** shipped code. They are
test collateral for the policy-judge fidelity eval in `eval/policy-fidelity/`.

Each file is a small synthetic source sample that **intentionally** violates a
known set of engineering-standards rules (or is intentionally clean). The eval
feeds each sample to the LLM judge and scores whether the judge flags exactly the
seeded violations — recall/precision against `../truth.edn`, which is the answer
key. So the standards violations you see are deliberate: they are the judge's
exam questions, not code to fix.

`order_service.clj.txt`, for example, is seeded with a magic number, a string
tier enum, throw-in-normal-flow, a manual `:status` check and a hardcoded
user-facing string. `truth.edn` lists exactly which rules each file violates. The
domains (order / config / billing / page-fetch / etc.) are arbitrary
realistic-looking filler to give the judge non-trivial code — not real business
logic. None of it runs.

## Why they don't lint or load

- Stored as `*.clj.txt`, not `*.clj`: not a real namespace, not on any source
  path, skipped by clj-kondo's staged-lint, never compiled or loaded by anything.
- The harness reads `<key>.txt` and presents it to the judge under the realistic
  `<key>.clj` path, so the judge sees production-shaped input.

## Editing rules

- Do **not** add `;; VIOLATION ...` / `Seeded: ...` markers inside these files.
  They leak the answer key to the judge and skew the score (and induce false
  dead-code hits). The code stays natural; the answer key lives only in
  `truth.edn`.
- Keep filenames domain-neutral. A name like `near_miss` / `clean_x` primes the
  judge via the path it sees — that is why these are not named `*_fixture` or
  `*_test` either.
- If you change a file's content, re-derive its `truth.edn` entry and re-run
  `clojure -M:dev:test -i eval/policy-fidelity/run.clj`.
