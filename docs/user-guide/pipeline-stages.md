<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Pipeline Stages: Required Processing and Pass-Through

A Data Foundry pipeline is a list of stages. Each stage has a family
(`:stage/family`), and each family requires something the stage must name.
The runner checks every requirement before any connector opens, so a pipeline
that names something missing is refused before it reads a source or writes a
sink.

## Required processing by family

| Family | The stage must name | Where it resolves |
|--------|---------------------|-------------------|
| `:ingest` | `:stage/connector-ref` | A bound connector implementing `Connector` and `SourceConnector` |
| `:publish` | `:stage/connector-ref` | A bound connector implementing `Connector` and `SinkConnector` |
| `:validate` | `:stage/quality-pack` in `:stage/config` | At least one rule in `:pack/rules`, each with a `:rule/check-fn` |
| Any other family | `:stage/transform` with a `:transform/type` in `:stage/config` | A function under that type in the run context's `:transforms` |

A transform, spawn fn or rule check fn may be a function, a multimethod, or a
bound Var holding one.

Any stage may also name a `:stage/spawn-fn` in its config. It must resolve to a
function in the run context's `:spawn-fns`.

A `:validate` stage runs only its quality pack. A `:stage/transform` on a
validate stage does not count as its processing.

## Optional processing: declared pass-through

A non-connector stage that skips processing on purpose declares it:

```clojure
{:stage/name   "Normalize"
 :stage/family :normalize
 :stage/config {:stage/pass-through? true}}
```

The stage passes its input records through unchanged. Its stage run carries
`:pass-through? true`, which records that processing was skipped by
declaration rather than by a missing transform.

The rules:

1. A non-connector stage with no processing and no pass-through declaration is
   refused.
2. On a non-connector stage of any family, declaring `:stage/pass-through? true`
   together with a `:stage/transform` or a `:stage/quality-pack` is a conflict
   and is refused. Pass-through cannot stand in for a transform that failed to
   register.
3. Connector stages (`:ingest`, `:publish`) always need a connector;
   `:stage/pass-through?` has no effect on them.

The shipped `risk-data` pack's Normalize stage is an example
(`packs/data-foundry/risk-data/pipelines/fred-risk-data.edn`).

## Preflight problems

`execute-pipeline` runs preflight after structural validation. When anything
fails to resolve it returns a failure with `:preflight/problems` and no
pipeline run. Every problem is reported, not only the first. Each one names
the stage id and name, a `:preflight/problem` kind, the missing
`:preflight/ref` and a message.

| Kind | Meaning |
|------|---------|
| `:connector/missing` | No connector is bound to the stage's connector ref |
| `:connector/role` | The bound connector does not implement the family's protocols |
| `:processing/undeclared` | A non-connector stage names no processing and does not declare pass-through |
| `:pass-through/conflict` | Pass-through is declared beside a transform or quality pack |
| `:transform/unresolved` | The `:transform/type` names no registered function |
| `:quality-pack/empty` | The quality pack has no rules |
| `:quality-pack/malformed` | `:pack/rules` is present but is not a list |
| `:quality-rule/unresolved` | A rule descriptor did not resolve to an implementation |
| `:quality-rule/invalid` | A built-in rule's descriptor could not be built (for example `:required` with no `:rule/field`) |
| `:spawn-fn/unresolved` | The `:stage/spawn-fn` names no registered function |

To check a pack without running it:

```bash
mf etl validate <pipeline.edn> --env <env.edn>
```

`mf etl validate` and `mf etl run` supply the built-in transforms and the
built-in rule types (`:required`, `:type-check`, `:range`, `:pattern`).

## Stages spawned during a run

A spawn fn adds child stages after preflight has run, so the executor checks
them when they execute. A spawned non-connector child fails with
`:error-kind :configuration` when it has no processing and no declaration, a
pass-through conflict, an unregistered transform, an empty or malformed
quality pack, or a rule with no check fn. As with any failed stage, the run
halts and the stages still pending are skipped.

## Rules that fail to run

A quality rule's check fn must return a map with a boolean `:valid?`. If it
throws (including a failed `assert`), or returns anything else, the validate
stage fails with `:error-kind :execution` and lists the failures under
`:quality/rule-errors`. A rule that fails to run is never counted as a pass,
and never as a record the rule rejected. Records a rule ran on and rejected
are domain violations, reported in the stage's quality report.
