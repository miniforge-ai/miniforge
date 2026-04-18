<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Two specs for richer agent context: clj-xref + tree-sitter symbols

## Summary

Specs only, no code. Adds two complementary work specs in `work/`:

- **`clj-xref-context-neighborhoods.spec.edn`** — integrate clj-xref as
  a Clojure-specific context provider in `context-pack`. Dependency
  neighborhoods (callers, callees, protocol implementors, multimethod
  dispatch) replace the current text-only BM25 neighborhoods for
  Clojure tasks.
- **`tree-sitter-symbol-context.spec.edn`** — reopens Phase 2 of the
  archived repo-context plan. Lifts the existing `policy-pack/ast.clj`
  tree-sitter CLI wrapper into a shared `tree-sitter-bridge` component,
  adds symbol extraction to `repo-index`, and wires symbol neighborhoods
  into `context-pack` for all languages miniforge OSS targets (Clojure,
  TypeScript, Python, Go, Rust, Swift).

## Why both

Current state verified by code audit:

- `repo-index` is file-level: git-tree walk + BM25 lexical search + repo
  maps. No symbols, no AST.
- `context-pack` assembles on top of that, token-budgeted. Text in,
  text out.
- `policy-pack/src/.../ast.clj` DOES shell out to `tree-sitter query`,
  but only for rule detection. NOT wired into the agent context pipeline.
- `work/archive/done/repo-context-implementation-plan.md` had a Phase 2
  (tree-sitter symbols) that was archived before implementation. Phase 1
  (file-level) shipped; Phase 2 didn't.

So when an implement agent is asked to change `process-payment`, context
today is "files whose text contains the string `process-payment`" — not
"files that actually call or implement `process-payment`." That's a
real signal gap.

The two specs close it at different levels:

| | clj-xref | tree-sitter |
|---|---|---|
| Scope | Clojure only | Clojure + TS + Py + Go + Rust + Swift |
| Basis | clj-kondo static analysis | tree-sitter grammars (CLI) |
| Queries | who-calls/calls-who/who-implements/who-dispatches + arity-aware + protocols/multimethods | def + ref + container + same-file neighbors + imports |
| Strength | Semantic superset for Clojure | Language-agnostic coverage |
| Cost | ~1 week of work | ~2 weeks (per original plan) |

They compose. For a Clojure task, the pack includes *both* the
tree-sitter symbol neighborhood AND the clj-xref call graph. For a
non-Clojure task, tree-sitter alone. For a language without a grammar
installed, we fall back to the current BM25 behavior gracefully.

## Non-goals (in both specs)

- **LSP replacement** — use clojure-lsp / rust-analyzer for editor UX.
- **Persisted xref DB** — in-memory only for v1. Persistence becomes a
  follow-up once we measure the value.
- **New tree-sitter grammars** — upstream grammars only, pinned
  versions.

## Recommended landing order

1. **clj-xref** first — narrow, additive, fastest dogfood win for the
   Clojure-heavy codebases miniforge currently operates on (including
   its own source).
2. **tree-sitter symbols** after — wider infra, needs grammar install
   task, refactors `policy-pack/ast.clj` into a shared brick.
3. Both should be **feature-flagged off** on first ship and turned on
   only after a measurable win in a dogfood A/B (token usage +
   convergence rate on identical specs).

## Test plan

- [x] Both specs parse as valid EDN (pre-commit clj lint pass)
- [ ] Work item tracked for running clj-xref spec first via miniforge
- [ ] Dogfood A/B framework wired into context-pack events for
      measurement (small ancillary change; part of the first spec)

## References

- clj-xref: <https://github.com/danlentz/clj-xref>
- policy-pack AST wrapper: `components/policy-pack/src/ai/miniforge/policy_pack/ast.clj`
- Archived plan: `work/archive/done/repo-context-implementation-plan.md`
- N1 architecture §2.27 onward: relevant normative context for the
  symbol-id scheme referenced in the tree-sitter spec.
