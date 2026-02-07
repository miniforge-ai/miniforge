# work/

This directory contains **ephemeral workflow specifications and task definitions**
that are **inputs to miniforge** for active development work.

## What Goes Here

### Workflow Specifications (`.spec.edn`)

Miniforge workflow specs describing work to be done:

- Bug fixes
- Feature implementations
- Refactoring tasks
- Integration work

**Example:** `fix-artifact-persistence.spec.edn`

### Task Definitions (`.edn`)

DAG task definitions for parallel execution:

- Dogfooding task lists
- Multi-task development sessions
- GitLab/GitHub integration work

**Example:** `dogfood-tasks.edn`

## What Does NOT Go Here

- **Design specifications** → `specs/informative/`
- **Normative requirements** → `specs/normative/`
- **PR documentation** → `docs/prs/` or `docs/pull-requests/`
- **Completed specs** → `specs/archived/`

## Lifecycle

```text
work/                    # Active work inputs
  ├── my-feature.spec.edn  → Execute with: miniforge run work/my-feature.spec.edn
  └── tasks.edn            → Execute with: bb dogfood --dag work/tasks.edn
       ↓
[Miniforge executes work]
       ↓
specs/archived/          # Completed work archived
  └── my-feature.spec.edn
```

## Tracked vs. Gitignored

**Tracked** (committed to repo):

- Workflow specs for ongoing work (`.spec.edn`)
- Task definitions for repeatable dogfooding
- Specs that document discovered bugs

**Gitignored** (local-only):

- `work/dogfood-tasks*.edn` - One-time dogfooding sessions
- `work/dogfood-session-*.edn` - Session-specific state
- `work/*-local.spec.edn` - Personal experiments
- `work/*-wip.edn` - Work in progress

## Directory Structure

```text
work/
├── README.md                           # This file
├── fix-artifact-persistence.spec.edn   # Bug fix spec (tracked)
├── tool-registry-phase4-6.spec.edn     # Feature spec (tracked)
├── decompose-test-files.spec.edn       # Refactoring spec (tracked)
├── gitlab-support-tasks.edn            # GitLab integration tasks (tracked)
└── dogfood-tasks.edn                   # Dogfooding session (gitignored)
```

## Usage

### Run a workflow spec

```bash
miniforge run work/fix-artifact-persistence.spec.edn
```

### Execute DAG tasks

```bash
bb dogfood --dag work/dogfood-tasks.edn
```

### Archive completed work

```bash
git mv work/completed-feature.spec.edn specs/archived/
```

## Relationship to Other Directories

| Directory              | Purpose                          | Tracked? |
|------------------------|----------------------------------|----------|
| `work/`                | Active work inputs               | Selective |
| `specs/normative/`     | N1-N6 requirements (MUST/SHALL)  | Yes      |
| `specs/informative/`   | Design docs & guidance           | Yes      |
| `specs/archived/`      | Completed workflow specs         | Yes      |
| `docs/prs/`            | PR documentation artifacts       | Yes      |
| `docs/checkpoints/`    | Development snapshots            | No       |

---

**This directory enables autonomous dogfooding:** Miniforge works on itself by
consuming specs from `work/` and producing changes to the codebase.
