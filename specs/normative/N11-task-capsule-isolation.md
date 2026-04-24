<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N11 — Task Capsule Isolation

**Version:** 0.2.0-draft
**Date:** 2026-04-23
**Status:** Draft
**Conformance:** MUST
**Amends:** N2-workflows §13.4, N10-governed-tool-execution §7

---

## 0. Architectural Decision Record

> **Miniforge SHALL treat the per-task capsule, not the git worktree and not the host
> process, as the primary execution boundary for governed workflows.**

This replaces the implicit execution model in which the agent subprocess runs on the
host and only shell/git commands are dispatched into the executor environment. Under
that model, the agent wrote files to `user.dir` on the host, the executor ran tests
and git operations in an isolated container, and no single boundary enclosed both.
The result was a provenance gap: the agent's file writes were unattested host-side
mutations, not bounded capsule outputs.

The capsule model closes this gap. The agent process, tool execution, filesystem
writes, build and test commands, git operations, and artifact emission all occur
inside one bounded, destroyable runtime. The control plane stays on the host. Task
outputs cross the capsule boundary only as declared artifacts or evidence records.

---

## 1. Purpose and Scope

This specification defines:

- The **task capsule** as the normative execution boundary (§2)
- The **phase-sharing model**: how sequential phases within one workflow share a
  capsule (§3)
- The **capsule lifecycle**: bootstrap, execute, export, destroy (§4)
- The **runtime specification** schema for capsule configuration (§5)
- The **agent invocation model**: how the agent subprocess runs inside the capsule (§6)
- **Execution modes**: local vs. governed, and the no-silent-downgrade rule (§7)
- **Secret handling and network policy** inside capsules (§8)
- **Evidence and artifact discipline**: what exits the capsule and how (§9)
- The **TaskExecutor protocol**: pluggable substrate contract with workspace persistence (§10)
- **Implementation mapping** to the existing codebase (§11)

### 1.1 Relationship to N2 and N10

**N2 §13.4** currently requires only worktree isolation (SHOULD). This spec replaces
that section with a stronger MUST requirement for governed execution and defines the
capsule as the correct isolation boundary.

**N10 §7** currently scopes capsule isolation to individual tool operations. This spec
extends capsule scope to the full task runtime — the agent process itself is inside the
capsule, not only the tools it calls.

### 1.2 Design Principles

1. **Full enclosure** — the agent process, its tools, and its outputs are inside one boundary
2. **Explicit export** — nothing leaves a capsule without a declared artifact or evidence record
3. **Reproducible** — the capsule runtime is defined by an immutable specification
4. **No silent downgrade** — governed tasks that cannot acquire a conformant capsule MUST fail, not fall back to host
  execution
5. **Phase continuity** — phases that share workspace state share one capsule; they do not hand off between separate
  capsules

---

## 2. Task Capsule

### 2.1 Definition

A **task capsule** is a bounded, destroyable runtime environment that contains the
full execution context of a miniforge task.

A task capsule MUST contain:

- the agent subprocess (e.g. `claude` CLI)
- the MCP server used by that agent
- governance hooks (`PreToolUse`, `PostToolUse`)
- all tool invocations performed by the agent
- filesystem reads and writes
- git operations
- build, test, and verification commands
- ephemeral credentials required for the task

### 2.2 Capsule Boundaries

A capsule MUST enforce:

**Filesystem** — agent has access to the workspace directory and temporary storage.
The capsule MUST NOT write to host filesystem paths. Host filesystem MUST NOT be
accessible inside the capsule unless explicitly mounted as a read-only input.

**Network** — deny-by-default. Outbound connections to declared endpoints (model
API, git remote, package registry, telemetry) are permitted when declared in the
runtime specification. Undeclared network access MUST be blocked.

**Credentials** — secrets are injected ephemerally at capsule start and MUST NOT
persist in exported artifacts, repo state, or evidence bundles.

**Time** — capsules MUST enforce the task timeout from the runtime specification.
A capsule MUST self-terminate when the timeout expires.

**Resources** — CPU and memory limits from the runtime specification MUST be enforced
by the capsule substrate.

### 2.3 Capsule Mechanisms

The following substrates satisfy the capsule requirements if they enforce the
properties in §2.2:

- Container (Docker, Podman)
- Kubernetes Job / Pod
- microVM (Firecracker, gVisor)
- WASM runtime with WASI
- Cloud function (Lambda, Cloud Run) — for stateless phases

A substrate is non-conformant if the agent process, mutable workspace, or privileged
tool execution occurs outside the bounded runtime.

### 2.4 Worktrees Inside Capsules

Git worktrees MAY be used inside a task capsule to provide separate source views or
support parallel file operations. Worktrees are a source-layout mechanism, not a
trust boundary. A worktree alone is not a conformant capsule.

---

## 3. Phase-Sharing Model

### 3.1 Task vs. Phase

In miniforge terminology:

- A **task** is a unit of work in a DAG run (one feature, one bug fix, one refactor).
- A **phase** is a stage of a task's lifecycle: plan → implement → verify → review
  → release.

Each task in a DAG run gets exactly one task capsule. All phases of that task execute
sequentially inside that capsule. The capsule is acquired before the first phase runs
and released after the last phase completes or fails.

```text
DAG Task N
  └─ Task Capsule (acquired at task start)
       ├─ implement phase  (runs inside capsule)
       ├─ verify phase     (runs inside capsule, reads files written by implement)
       ├─ review phase     (runs inside capsule)
       └─ release phase    (runs inside capsule, commits and pushes)
  └─ Task Capsule (released at task end)
```

### 3.2 Rationale for Phase Sharing

Phases within a task are semantically sequential and share filesystem state. The
verify phase reads files written by implement. The release phase commits the worktree
that verify validated. Separate capsules per phase would require explicit artifact
handoff between phases and destroy the natural git working-tree model.

The C4 diagram in the ADR document (plan / implement / verify / review as separate
nodes) depicts concurrent DAG tasks, not separate capsules per phase of a single task.
Each node in that diagram is a different task, each in its own capsule.

### 3.3 Multi-Task Concurrency

When a DAG run dispatches multiple tasks concurrently:

- Each task gets its own capsule.
- Capsules for concurrent tasks MUST NOT share a mutable filesystem.
- The repo snapshot injected into each capsule MUST be consistent: all concurrent
  capsules for the same run SHOULD start from the same commit or explicitly declared
  base reference.

---

## 4. Capsule Lifecycle

### 4.1 Phases

```text
1. CREATE    — capsule substrate allocated; runtime spec applied
2. BOOTSTRAP — repo checked out or snapshot mounted; credentials injected
3. EXECUTE   — task phases run sequentially inside the capsule
4. EXPORT    — declared artifacts and evidence copied out via capsule boundary
5. DESTROY   — capsule and all mutable state destroyed
```

All five phases MUST be executed in order. A capsule that fails during BOOTSTRAP or
EXECUTE MUST still proceed through EXPORT (for evidence capture) and DESTROY.

### 4.2 Bootstrap Requirements

During BOOTSTRAP, the capsule executor MUST:

1. Check out or clone the repo at the declared commit/ref into the workspace directory.
2. Configure git identity inside the capsule (user.name, user.email).
3. Inject declared environment variables and secrets.
4. Start the MCP server process if required by the task.
5. Write governance hook configuration (settings file for the agent CLI).

The bootstrap method is determined by the `:workspace/mode` in the runtime
specification:

| Mode | Mechanism | Use case |
|------|-----------|----------|
| `:checkout` | `git clone` inside container or worktree `copy-to!` | Standard; preferred for Docker/K8s |
| `:snapshot` | Filesystem snapshot or image layer with repo pre-baked | Cached fast starts |
| `:artifact-input` | Prior task capsule artifact mounted as input | Dependent tasks in DAG |

### 4.3 Export Requirements

Before destroying the capsule, the executor MUST:

1. Export each declared artifact to the artifact store.
2. Export the evidence record to the evidence bundle store.
3. Copy any declared output files to the artifact store via `copy-from!`.

Implicit host-side file writes are prohibited in governed execution mode. The agent
MUST NOT assume that files written to its workspace are accessible on the host after
the capsule exits. All outputs that must persist MUST be explicitly exported.

---

## 5. Runtime Specification

### 5.1 Schema

Each task capsule is created from a **runtime specification**:

```clojure
{:task/id                uuid?
 :task/name              keyword?
 :task/class             #{:A :B :B+ :C}        ; action class — see N10 §3
 :task/repo-snapshot     {:repo/url  string?
                          :commit    string?      ; full SHA
                          :ref       string?}     ; branch or tag (informative)
 :task/runtime-spec      {:runtime/class         #{:docker :k8s :vm :wasm}
                          :image                 string?  ; e.g. "miniforge/task-runner:latest"
                          :env                   {keyword? string?}
                          :network-policy        {:mode  #{:deny-default :allow-listed}
                                                  :allow [{:host string?
                                                           :port int?
                                                           :proto #{:tcp :https}}]}
                          :secrets               [{:name   keyword?
                                                   :source keyword?  ; secret store key
                                                   :scope  keyword?  ; :env :file :none
                                                   :mount  string?}] ; file path if :file scope
                          :resources             {:cpu              string?  ; "500m"
                                                  :memory           string?  ; "1Gi"
                                                  :timeout-seconds  int?}
                          :workspace             {:mode             #{:checkout :snapshot :artifact-input}
                                                  :use-worktree?    boolean?}
                          :artifacts-out         [{:name      keyword?
                                                   :path      string?  ; path inside capsule
                                                   :required? boolean?}]}
 :task/policy-pack-ref   string?
 :task/evidence-required boolean?}
```

### 5.2 Required Fields

For governed execution, the following fields MUST be present:

- `:task/class`
- `:task/repo-snapshot` (all three sub-fields)
- `:task/runtime-spec` with `:runtime/class`, `:image`, `:network-policy`, `:resources`, `:workspace/mode`
- `:task/evidence-required true`

### 5.3 Defaults

Implementations SHOULD provide sensible defaults for omitted fields:

| Field | Default |
|-------|---------|
| `:runtime/class` | `:docker` if Docker available, else `:k8s` if K8s available |
| `:network-policy/mode` | `:deny-default` |
| `:workspace/mode` | `:checkout` |
| `:resources/timeout-seconds` | `3600` |
| `:task/evidence-required` | `true` |

---

## 6. Agent Invocation Model

### 6.1 Agent Runs Inside the Capsule

The agent subprocess (e.g. the `claude` CLI) MUST execute inside the task capsule.
Spawning the agent on the host and routing only shell commands through the executor
is non-conformant for governed execution.

### 6.2 Implementation: Capsule-Aware exec-fn

The LLM client (`llm_client.clj`) uses an injectable `exec-fn` to spawn the agent
CLI. The default implementation uses `babashka.process/shell` on the host. For
governed execution, the runner MUST inject a **capsule-aware exec-fn** that routes
the agent command through `executor-execute!`:

```clojure
(defn capsule-exec-fn
  "Returns an exec-fn that runs the agent CLI inside the task capsule."
  [executor environment-id workdir]
  (fn [cmd]
    (let [command-str (clojure.string/join " " cmd)
          result (dag/executor-execute! executor environment-id command-str
                                        {:capture-output? true
                                         :workdir workdir})]
      (if (dag/ok? result)
        {:out (get-in result [:data :stdout] "")
         :err (get-in result [:data :stderr] "")
         :exit (get-in result [:data :exit-code] 0)}
        {:out ""
         :err (str "Capsule exec error: " (:error result))
         :exit 1}))))
```

This exec-fn is passed to `records/create-client` and takes the place of
`impl/default-exec-fn` when an active capsule environment is present.

### 6.3 MCP Server Inside the Capsule

If the agent process runs inside the capsule, the MCP server it connects to MUST
also run inside the capsule (or be reachable from inside via a declared network
endpoint).

The preferred approach for local-dev and Docker is to run the MCP server as a
sidecar process inside the capsule:

1. During BOOTSTRAP, the executor starts `bb miniforge mcp-serve` inside the capsule.
2. The MCP config file written into the workspace points to a localhost socket or port.
3. The agent CLI connects to that local endpoint.

This requires that the capsule image include `bb` and the miniforge bb tasks (or the
`miniforge` binary). `Dockerfile.task-runner-clojure` already includes `bb` and is
the recommended base. The `MINIFORGE_CMD` environment variable controls which
miniforge binary the MCP server uses.

### 6.4 Governance Hooks Inside the Capsule

The `PreToolUse` / `PostToolUse` hooks (written by `write-claude-settings!`) invoke
`bb miniforge hook-eval`. When the agent runs inside the capsule, this hook invocation
also runs inside the capsule. The settings file MUST be written into the workspace
directory inside the capsule during BOOTSTRAP.

### 6.5 Streaming Output Across the Capsule Boundary

The current streaming implementation (`stream-exec-fn`) reads from the agent
subprocess stdout via a direct pipe. When the agent runs inside a container, stdout
is not directly accessible via a pipe; it comes through `docker exec` stdout.

Implementations MAY use:

- `docker exec` with stdout capture and a streaming reader (Docker executor)
- `kubectl exec` with streaming (K8s executor)
- A log-forwarding sidecar that writes to the event stream

For the initial governed-mode implementation, non-streaming (batch) invocation via
`executor-execute!` with full output capture is conformant. Streaming is a
performance enhancement, not a correctness requirement.

---

## 7. Execution Modes

### 7.1 Local Mode

Local mode is for developer iteration. It is explicitly non-governed.

- Worktree-only execution is allowed.
- Agent runs on host.
- Class A and B actions tolerated without capsule.
- Marked in evidence as `:execution/mode :local`.

Local mode MAY be enabled by:

- Absence of a Docker or K8s executor in the registry.
- Explicit `:execution/mode :local` in the workflow spec or invocation opts.

### 7.2 Governed Mode

Governed mode is required for Class B+ actions and all production runs.

- Per-task capsules REQUIRED.
- Agent MUST run inside capsule.
- Explicit artifact export REQUIRED.
- Evidence bundle MUST be recorded.
- Class B+ network access MUST be policy-declared.

Governed mode is activated by:

- `:execution/mode :governed` in the workflow spec.
- Any task declaring `:task/class :B+` or `:C`.
- Presence of secret-bearing tasks.
- Tasks that modify protected branches or deployment state.

### 7.3 No Silent Downgrade

**Implementations MUST NOT silently downgrade governed tasks to host or
worktree-only execution.**

If governed mode is required and no conformant capsule substrate is available, the
task MUST fail with `:execution/error :no-conformant-capsule`. The failure MUST be
logged and included in the evidence record.

The rationale: silent downgrade destroys the trust model. A PR that was validated
in governed mode (inside a capsule) and a PR that was validated in local mode (on the
host) are different things from a compliance perspective, and MUST NOT be treated
identically.

### 7.4 Runtime Substrate Priority

For governed mode, the executor MUST prefer:

```text
Kubernetes → Docker → VM/microVM → fail (do not fall through to worktree)
```

The worktree executor is not in the governed-mode priority chain.

---

## 8. Secret Handling and Network Policy

### 8.1 Secret Injection

Secrets MUST be injected into the capsule at BOOTSTRAP time as:

- Environment variables (`:scope :env`)
- Files at a declared path (`:scope :file`)

Secrets MUST NOT be:

- Written into image layers
- Present in any exported artifact
- Logged or included in evidence records (only secret *names* and *scopes* are recorded)

Capsule teardown at DESTROY time MUST ensure:

- Env vars are not accessible after container/pod termination
- Mounted secret files are unmounted and destroyed

The `ANTHROPIC_API_KEY` is the primary secret for current tasks. It is injected
as an environment variable and scoped to the capsule lifetime.

### 8.2 Network Policy Enforcement

The deny-by-default network policy requires explicit allow-listing for:

| Endpoint | Required by |
|----------|-------------|
| `api.anthropic.com:443` | Agent (model API calls) |
| `github.com:22` or `:443` | Git remote operations |
| `repo.hex.pm:443`, `repo1.maven.org:443` | Package fetch during build/test |
| Telemetry endpoint | Event stream emission |

The runtime specification MUST declare these endpoints in `:network-policy/allow`
for any task that requires them.

---

## 9. Evidence and Artifact Discipline

### 9.1 What Must be Recorded

Each task capsule MUST produce an evidence record containing:

```clojure
{:evidence/runtime-class    keyword?  ; :docker, :k8s, etc.
 :evidence/image-digest     string?   ; sha256:...
 :evidence/repo-commit      string?   ; full SHA of base commit
 :evidence/policy-pack-ref  string?
 :evidence/secrets-policy   string?   ; policy name, NOT secret values
 :evidence/artifact-manifest [{:name keyword? :path string? :sha256 string?}]
 :evidence/task-started-at  inst?
 :evidence/task-finished-at inst?
 :evidence/exit-status      int?
 :evidence/execution-mode   #{:local :governed}}
```

### 9.2 Artifact Export Gate

Before DESTROY, the executor MUST verify that all `:required? true` artifacts in
`:artifacts-out` were successfully exported. If a required artifact was not produced,
the task MUST be marked failed with `:export/missing-required-artifact`.

### 9.3 Host-Side Writes Prohibited

In governed execution mode, agents MUST NOT rely on `System/getProperty "user.dir"`
to resolve their workspace. The workspace path MUST come from the capsule context:
`:execution/worktree-path` in the phase context map, which is set to the capsule's
workspace directory during BOOTSTRAP.

---

## 10. TaskExecutor Protocol

Task capsules are created and managed via a pluggable backend protocol. The
**TaskExecutor** protocol is the normative contract between the workflow
runner (which requests a capsule per task) and the concrete substrate
implementation (Docker, Kubernetes, or worktree fallback).

This protocol is what Fleet's distributed executors (e.g., K8s with object-store
workspace persistence) extend; it MUST remain stable across substrates.

### 10.1 Protocol Methods

Implementations MUST provide these methods:

```clojure
(defprotocol TaskExecutor
  (executor-type [this]
    "Returns the substrate keyword: :kubernetes | :docker | :worktree.")

  (available? [this]
    "Returns true if this executor can currently acquire environments on
     the host. Used by the executor registry for selection and for runtime
     health checks. MUST NOT throw.")

  (acquire-environment! [this task-id env-config]
    "Acquire an isolated capsule for a task. env-config is a map:
       {:workspace/mode      :checkout | :persist | :none
        :workspace/repo-url  string        ; required when mode=:checkout
        :workspace/commit    string        ; full SHA; required when mode=:checkout
        :workspace/branch    string        ; optional; branch to create
        :env                 {string string}
        :workdir             string
        :resources           {:cpu :memory :timeout-ms}
        :network-policy      {...}         ; see §8.2
        :secrets             [{...}]}      ; see §8.1
     Returns:
       {:environment-id uuid
        :workdir        string             ; absolute path inside capsule
        :runtime-class  keyword            ; matches executor-type
        :image-digest   string             ; OPTIONAL for container substrates
        :acquired-at    inst}
     MUST fail (not downgrade) if the requested substrate is unavailable.")

  (executor-execute! [this environment-id command opts]
    "Execute a command inside the capsule. command is argv (seq of string)
     or a single string routed through /bin/sh -c. opts:
       {:env        {string string}        ; merged over env-config env
        :stdin      string                  ; OPTIONAL
        :timeout-ms long
        :cwd        string}                 ; OPTIONAL, defaults to :workdir
     Returns:
       {:exit-status int
        :stdout      string
        :stderr      string
        :started-at  inst
        :finished-at inst}")

  (copy-to! [this environment-id host-path capsule-path]
    "Copy a file or directory from host into capsule. host-path MUST be
     absolute. capsule-path is relative to :workdir. Idempotent: overwrites.")

  (copy-from! [this environment-id capsule-path host-path]
    "Copy a file or directory from capsule to host. capsule-path is
     relative to :workdir.")

  (persist-workspace! [this environment-id persistence-config]
    "Persist the capsule workspace to a durable layer so it can be restored
     into a different capsule (possibly on a different node). persistence-config:
       {:persistence/kind   :git | :object-store
        :git/remote         string             ; for :git
        :git/branch         string             ; for :git
        :object-store/uri   string             ; for :object-store (s3://, gs://, ...)
        :object-store/key   string             ; for :object-store
        :object-store/credentials keyword}     ; ref to secret name
     Returns:
       {:workspace/digest   string             ; sha256 of persisted bundle
        :workspace/uri      string             ; retrievable address
        :workspace/bytes    long
        :persisted-at       inst}
     MUST produce a reproducible digest: the same workspace state MUST
     yield the same digest regardless of substrate or persistence kind.")

  (restore-workspace! [this environment-id persistence-ref]
    "Restore a previously persisted workspace into this capsule.
     persistence-ref is the map returned by persist-workspace!.
     MUST verify the restored workspace matches :workspace/digest and
     MUST fail if it does not. MAY be called immediately after
     acquire-environment! with :workspace/mode :persist (no :workspace/commit
     required in that case — the persisted state defines the starting point).")

  (release-environment! [this environment-id]
    "Destroy the capsule and reclaim resources. MUST be called exactly once
     per acquire-environment!. MUST succeed even if the capsule was already
     terminated (idempotent tear-down). MUST NOT propagate host-side writes."))
```

### 10.2 Method Requirements

**Idempotency and failure.**

- `acquire-environment!` MUST fail loudly (no downgrade) per N11.MD.1.
- `release-environment!` MUST be idempotent — calling twice with the same id
  MUST succeed on both calls.
- `persist-workspace!` MUST be repeatable with stable digests; calling twice
  on an unchanged workspace MUST yield the same `:workspace/digest`.
- `restore-workspace!` MUST verify digest before considering the restore
  successful; digest mismatch is a non-retriable failure.

**Ordering.** The lifecycle MUST be:

```text
acquire-environment! → { executor-execute! | copy-to! | copy-from!
                       | persist-workspace! | restore-workspace! }*
                     → release-environment!
```

`restore-workspace!` called on an environment that already has a populated
workspace (via `:workspace/mode :checkout`) MUST fail with
`:workspace/already-populated` unless the workspace was explicitly cleared.

**Workspace persistence kinds.**

- `:git` — push the workspace to a task branch via `git push`; restore via
  `git fetch` + checkout. Required for all substrates as a baseline (works
  even in worktree fallback).
- `:object-store` — tar the workspace, upload to S3/GCS/MinIO, restore via
  download + extract. Required for the `:kubernetes` substrate when pods may
  reschedule across nodes; OPTIONAL for `:docker` and `:worktree`.

Implementations MAY support additional persistence kinds but MUST support
at least `:git` for all substrates.

**Workspace digest.** The `:workspace/digest` is computed over a canonical
serialization of tracked workspace contents:

```text
SHA-256(
  for each tracked file F in lexicographic path order:
    "<posix-path>\n"
    "<sha256-hex of F bytes>\n"
)
```

Where "tracked" means: everything under `:workdir` that would be included by
`git add -A` followed by `git ls-files -z` — i.e., respecting `.gitignore`.
Untracked files MUST NOT contribute to the digest, to ensure reproducibility.

### 10.3 Executor Registry and Selection

Implementations MUST provide an executor registry and selection API:

```clojure
;; Create a registry from a config map. Registered executors are instantiated
;; lazily; selection MAY instantiate.
(create-executor-registry
  {:kubernetes {...}   ; substrate-specific config
   :docker     {...}
   :worktree   {...}})

;; Select the preferred available executor given the workflow's execution mode.
(select-executor registry
  {:execution/mode     :governed | :local
   :preferred           keyword  ; OPTIONAL explicit preference
   :require-persistence keyword}) ; OPTIONAL required persistence kind
;; Returns a TaskExecutor or throws :no-conformant-executor
```

Selection MUST respect N11.MD.1 (no silent downgrade): in `:governed` mode,
`:worktree` is NEVER selected unless explicitly preferred AND the execution
mode permits it.

### 10.4 Evidence Emissions

Every lifecycle method MUST emit events (N3) and contribute to the evidence
record (§9.1):

| Method | Event | Evidence contribution |
|--------|-------|-----------------------|
| `acquire-environment!` | `capsule/acquired` | `:evidence/runtime-class`, `:evidence/image-digest`, `:evidence/task-started-at` |
| `executor-execute!` | `capsule/command-executed` (throttled per N3 §4.2) | command + exit status + duration in run transcript |
| `persist-workspace!` | `workspace/persisted` | `:evidence/workspace-persistence` with digest + uri |
| `restore-workspace!` | `workspace/restored` | verification result + source digest |
| `release-environment!` | `capsule/released` | `:evidence/task-finished-at`, `:evidence/exit-status` |

The `workspace/persisted` and `workspace/restored` events MUST be emitted when
persistence crosses capsule boundaries (e.g., phase transitions that hand off
workspace state between capsules).

### 10.5 Fleet Extension Point

Fleet-grade deployments extend this protocol in two ways:

1. **Object-store workspace persistence for Kubernetes** — a Fleet-provided
   implementation of `persist-workspace!`/`restore-workspace!` using
   `:object-store` kind (S3/GCS/MinIO). This implementation MUST conform to
   §10.2 and MUST NOT require changes to OSS callers.
2. **Cross-node capsule binding** — a Fleet scheduler MAY bind a
   `restore-workspace!` call on node B to a persistence record produced by
   `persist-workspace!` on node A. The persistence URI MUST be network-reachable
   from both nodes.

OSS MUST NOT assume cross-node capsule binding; it is a Fleet capability.

---

## 11. Implementation Mapping

This section maps the normative requirements above to the existing codebase and
identifies the minimum change set.

### 10.1 Immediate Gaps

| Gap | Location | Required Change |
|-----|----------|-----------------|
| Runner always requests worktree | `runner.clj:220–221` | Read runtime-spec from workflow opts; create registry with Docker/K8s when governed mode |
| `acquire-environment!` does not bootstrap repo | `docker.clj:428–442`, `kubernetes.clj` | Add `:workspace` handling: run `git clone` inside container post-create |
| Agent exec-fn runs on host | `llm_client.clj:652–660` | Inject `capsule-exec-fn` (§6.2) when executor context is present |
| MCP server starts on host | `artifact_session.clj:201–237` | For governed mode, start MCP server inside capsule via `executor-execute!` |
| Governance hooks reference host binary | `artifact_session.clj:183–199` | Write settings file into capsule workspace; `MINIFORGE_CMD` must resolve inside capsule |
| No execution mode concept | `runner.clj`, workflow spec | Add `:execution/mode #{:local :governed}` to workflow opts and runner |
| No downgrade guard | `runner.clj:431–444` | After executor selection, check mode compatibility; fail if governed + worktree |

### 10.2 `acquire-environment!` Bootstrap Extension

The Docker executor's `acquire-environment!` currently accepts `:env`, `:workdir`,
and `:resources`. The `:workspace` key from the runtime specification MUST be added:

```clojure
(acquire-environment! [_this task-id env-config]
  ;; 1. Create container (existing)
  ;; 2. NEW: if (:workspace/mode env-config) = :checkout
  ;;    (executor-execute! env-id (str "git clone " repo-url " " workdir) {})
  ;;    (executor-execute! env-id "git config user.email miniforge@miniforge.ai" {})
  ;;    (executor-execute! env-id "git config user.name miniforge" {})
  ;; 3. Return environment record with :workdir
  )
```

### 10.3 Runner Execution Mode Selection

```clojure
(defn- acquire-execution-environment! [workflow-id {:keys [execution-mode repo-url commit] :as opts}]
  (let [governed? (= :governed execution-mode)
        registry  (if governed?
                    (create-registry {:kubernetes {:image "miniforge/task-runner:latest"}
                                      :docker     {:image "miniforge/task-runner:latest"}
                                      :worktree   {}}) ; worktree registered but not preferred
                    (create-registry {:worktree {}}))
        preferred (if governed? nil :worktree) ; nil = use priority order
        executor  (select-executor registry :preferred preferred)]
    (when (and governed? (= :worktree (executor-type executor)))
      (throw (ex-info "No conformant capsule available for governed execution"
                      {:execution/error :no-conformant-capsule})))
    ...))
```

### 10.4 Phase Context Keys

When a capsule environment is active, the phase context MUST carry:

```clojure
{:execution/mode          :governed   ; or :local
 :execution/executor      executor    ; TaskExecutor instance
 :execution/environment-id env-id    ; capsule ID
 :execution/worktree-path workdir    ; workspace path inside capsule
 :execution/capsule-exec-fn exec-fn} ; capsule-aware exec-fn for LLM client
```

The `:execution/capsule-exec-fn` key is new. Phase implementations that invoke the
LLM client MUST use this exec-fn when present, instead of the default host exec-fn.

### 10.5 What Does Not Need to Change

The following are already conformant with this spec or are in the right direction:

| Component | Status |
|-----------|--------|
| `dag-executor/protocols/executor.clj` | Protocol is correct; implementations need bootstrap |
| `sandbox.clj` in release-executor | Already routes git/gh ops through `executor-execute!` |
| `verify.clj` | Already runs `bb test` via executor in the environment |
| `release-executor/core.clj` | Already uses sandbox path exclusively (no host fallback) |
| Docker and K8s executor implementations | Protocol conformant; need bootstrap additions only |
| Evidence bundle structure | Compatible; add `:evidence/execution-mode` and `:evidence/image-digest` |

---

## 12. Conformance Requirements

| ID | Level | Requirement |
|----|-------|-------------|
| N11.CP.1 | MUST | In governed mode, the agent process MUST run inside the task capsule |
| N11.CP.2 | MUST | Capsule MUST enforce filesystem, network, time, and resource bounds |
| N11.CP.3 | MUST | Capsule MUST be destroyed after task completion or failure |
| N11.CP.4 | MUST | Secrets MUST NOT persist in exported artifacts or evidence records |
| N11.CP.5 | MUST | Required artifacts MUST be exported before capsule destruction |
| N11.PS.1 | MUST | All phases of a task MUST execute within the same task capsule |
| N11.PS.2 | MUST | Concurrent tasks MUST execute in separate capsules |
| N11.MD.1 | MUST | Governed tasks MUST fail, not downgrade, when no conformant capsule is available |
| N11.MD.2 | MUST | Evidence records MUST include `:evidence/execution-mode` |
| N11.RT.1 | MUST | Runtime specification MUST declare network policy for governed tasks |
| N11.RT.2 | MUST | Runtime specification MUST declare all required secrets by name and scope |
| N11.EV.1 | SHOULD | Evidence records SHOULD include `:evidence/image-digest` for container substrates |
| N11.LM.1 | MAY | Local mode MAY use worktree-only execution for Class A tasks |

---

## 13. New Definitions

**Task Capsule** — A bounded, destroyable runtime environment that contains the full
execution context of a DAG task (agent process, tools, filesystem, git, secrets).

**Governed Execution Mode** — An execution mode in which task isolation, policy
enforcement, evidence capture, and explicit artifact discipline are mandatory.
Required for Class B+ actions and all production runs.

**Local Execution Mode** — A non-governed mode for developer iteration. Worktree
execution is permitted. Evidence is recorded but marked `:local`. Not suitable for
production or compliance contexts.

**Runtime Specification** — A declarative description of the environment required
to execute a task capsule, including image, network policy, secrets, resources,
workspace bootstrap method, and artifact output declarations.

**Capsule Bootstrap** — The initialization phase of a task capsule in which the repo
is checked out, credentials are injected, and the MCP server and governance hooks are
started inside the capsule.

**No Silent Downgrade** — The property that a task configured for governed execution
will fail rather than execute in a less-isolated substrate (e.g., worktree-only or
host process) when no conformant capsule substrate is available.

---

## 14. References

- **N2 §13.4** — Task execution isolation (amended by this spec)
- **N10 §7** — Capsule isolation (scope extended by this spec to full task runtime)
- **N6** — Evidence provenance (evidence record structure)
- **N4** — Policy packs (action classification: Class A / B / B+ / C)
- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/docker.clj` — Docker executor
  (acquire-environment! extension point)
- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/kubernetes.clj` — K8s executor (same)
- `components/dag-executor/resources/executor/docker/Dockerfile.task-runner-clojure` — Recommended base image (includes
  bb, clj, gh CLI)
- `components/workflow/src/ai/miniforge/workflow/runner.clj:204–234` — `acquire-execution-environment!` (hardcoded to
  worktree; primary fix point)
- `components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj:652–660` — `default-exec-fn` (capsule-aware exec-fn
  injection point)
- `components/agent/src/ai/miniforge/agent/artifact_session.clj:201–237` — MCP config and governance hook setup (must
  run inside capsule)

---

**Version History:**

- 0.2.0-draft (2026-04-23): Fleet enablement amendments — TaskExecutor Protocol (§10)
  hoisted to normative; adds persist-workspace! / restore-workspace! methods, workspace
  digest, object-store persistence kind, Fleet extension point; renumbered prior §10–§13
  to §11–§14; closes OSS spec gap G1 for Fleet K8s workspace persistence
- 0.1.0-draft (2026-04-03): Initial task capsule isolation specification
