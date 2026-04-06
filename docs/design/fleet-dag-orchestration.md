# Fleet DAG Orchestration

## Context

The current DAG executor runs sub-workflows as JVM futures within a single process. This works
for local and single-host execution but couples orchestrator lifetime to task execution and caps
parallelism at host resources. The move to containerized execution creates a natural opportunity
to decouple these concerns and support a fleet model where a single persistent orchestrator
manages many concurrent full workflows across a cluster.

## The Shift

### Current model (in-process)

```text
run-dag! (JVM process)
  └── future: sub-workflow A  →  acquire worktree  →  run pipeline  →  release
  └── future: sub-workflow B  →  acquire worktree  →  run pipeline  →  release
  └── future: sub-workflow C  →  acquire worktree  →  run pipeline  →  release
  └── future: sub-workflow D  →  acquire worktree  →  run pipeline  →  release

Task state lives in ConcurrentHashMap in the orchestrating JVM.
Orchestrator must stay alive for the duration of all tasks.
```

### Target model (fleet)

```text
compile-dag! (any process, any time)
  └── emits: DAG artifact (EDN) → submitted to fleet scheduler

Fleet scheduler (persistent, long-running)
  └── watches DAG artifact
  └── for each ready node (dependencies satisfied):
        → spin container → run sub-workflow to completion → signal done
  └── on completion: unlock dependents, update DAG state
  └── on failure:    propagate-failures, surface to governance

Task state lives in an external store (DB/queue).
Orchestrator lifetime is independent of any individual task.
```

The DAG structure becomes a first-class submission artifact. Compile once, hand off to the fleet,
receive completion/failure events. Multiple specs can be in-flight simultaneously under a single
fleet scheduler.

## The OSS / Fleet Boundary

The key question is what Miniforge OSS must provide to make fleet integration clean vs what Fleet
adds on top.

### OSS provides: fleet-ready surface

#### Serializable DAG format

The DAG is currently built and executed in-memory. OSS needs to be able to emit a DAG as an
EDN artifact: a list of tasks with ids, descriptions, dependencies (edges), execution config
(executor type, branch, env), and a budget envelope. This is the submission unit.

```edn
{:dag/id       #uuid "..."
 :dag/spec     "path/to/spec.md"
 :dag/tasks    [{:task/id   #uuid "..."
                 :task/name "implement-auth"
                 :task/deps [#uuid "..."]   ; predecessor task ids
                 :task/spec {...}
                 :task/budget {:tokens 50000}}
                ...]
 :dag/created  #inst "..."}
```

#### External state store interface

Currently task state (`:pending`, `:running`, `:completed`, `:failed`) lives in a
`ConcurrentHashMap`. OSS should define a protocol for this store and ship an in-memory default
implementation. Fleet provides a persistent implementation (Postgres, Redis, etc.).

```clojure
(defprotocol DAGStateStore
  (get-task-state  [store dag-id task-id])
  (set-task-state! [store dag-id task-id state result])
  (get-dag-state   [store dag-id])
  (ready-tasks     [store dag-id]))   ; tasks whose deps are all :completed
```

#### Single-task runner entry point

A container needs a way to run exactly one task and exit cleanly with a structured result. OSS
should provide a CLI entry point (or bb task) that accepts a task spec, runs the sub-workflow
pipeline, and emits a result artifact the fleet scheduler can read.

```shell
mf run-task --dag-id <id> --task-id <id> --state-endpoint <url>
```

This is the executable unit the fleet scheduler launches per container. It replaces the
in-process future.

#### Idempotent task execution

Tasks need to be safe to restart if a container is lost mid-run. This means persisting
intermediate phase results to the external state store rather than only keeping them in memory.
The current artifact session already writes to disk; the missing piece is syncing those artifacts
to the external store at phase boundaries.

#### Completion / failure signaling

Currently futures complete in-process. A containerized task runner needs to signal the external
scheduler on completion (success + artifacts) or failure (error + propagation data). This can be
as simple as a POST to a state endpoint, a write to a shared volume, or a queue message.

### Fleet adds on top

**Scheduler**: watches the state store for ready tasks, respects DAG edges, enforces concurrency
limits per DAG and per tenant, handles retry policies.

**Container orchestration**: maps each ready task to a container (Docker, ECS, k8s Job), manages
lifecycle, collects exit codes.

**Persistent state store**: implements `DAGStateStore` against Postgres or Redis; survives
restarts; supports multiple concurrent DAGs.

**Governance surface**: the scheduler is the natural place to enforce policy gates. When a task
fails a policy check (budget, compliance, release rules), the scheduler pauses the DAG and
surfaces to the dashboard rather than failing outright. A human approves or retries; the
scheduler resumes without re-running completed nodes.

**Multi-tenancy**: multiple orgs submit DAGs to the same fleet; scheduler tracks ownership,
billing, quotas.

**Observability**: scheduler emits events (task started, completed, failed, paused) that feed
the dashboard. Real-time view of every in-flight workflow across the fleet.

## What Does Not Change

The pipeline execution logic (plan/implement/verify/release phases, agent invocations, artifact
management) is entirely inside the containerized sub-workflow. The fleet scheduler knows nothing
about phases — it only knows task = container = sub-workflow. The current `runner.clj` pipeline
is already the right unit of work.

The DAG dependency model and `propagate-failures` logic remain the same; they just move from
in-process futures to the external scheduler.

## Sequencing: what OSS needs before Fleet can use it

The minimal OSS work that unblocks fleet integration, roughly in order:

1. **Serialize DAG to EDN** — make the DAG structure emittable as a data artifact so it can be
   submitted to the fleet scheduler without needing the OSS process to be alive.

2. **`DAGStateStore` protocol + in-memory impl** — extract the ConcurrentHashMap state into a
   protocol. In-process scheduler uses the in-memory impl unchanged. Fleet plugs in the
   persistent impl.

3. **Single-task runner CLI** — `mf run-task` entry point so a container can run one task and
   exit cleanly with a structured result.

4. **Phase-boundary artifact sync** — persist intermediate results to the state store at each
   phase boundary so tasks are restartable.

Items 1 and 3 are the minimum for fleet to start experimenting. Items 2 and 4 are needed for
production reliability.

## Open Questions

- **DAG submission protocol**: push (OSS POSTs to fleet) vs pull (fleet polls a known location
  for new DAG artifacts)? Push is simpler for the fleet; pull is simpler for OSS (just write a
  file).

- **State store coupling**: should the in-memory `DAGStateStore` impl live in OSS or in a
  separate fleet-compat layer? Probably OSS, since in-process execution still needs it.

- **Artifact visibility**: when a containerized task writes artifacts, how does the fleet
  scheduler surface them back to the user? Options: shared volume, object store (S3), or a
  sidecar that syncs to the state store.

- **Nested DAGs**: if a sub-workflow itself generates a DAG (e.g., a large spec decomposes into
  sub-tasks at plan time), does the fleet scheduler handle nested DAG submission dynamically, or
  are all nodes known at compile time?
