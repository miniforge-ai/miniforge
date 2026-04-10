# Demo Polyglot Repository

A purpose-built repository with intentional violations across 6 languages to demonstrate miniforge's three-tier compliance scanning.

## Languages & Violations

| File | Language | Violations |
|------|----------|------------|
| `src/main.rs` | Rust | unsafe block, .unwrap(), TODO |
| `src/app.swift` | Swift | force-unwrap (!), force-cast (as!), FIXME |
| `src/core.clj` | Clojure | (or (:key m) default), inline anon fns, TODO |
| `src/api.py` | Python | unused imports, hardcoded API key, TODO |
| `src/handler.ts` | TypeScript | console.log, any type, TODO |
| `src/deploy/k8s.yaml` | Kubernetes | :latest tag, hostNetwork, privileged, no limits |
| `scripts/setup.sh` | Bash | hardcoded passwords and tokens |

## Usage

```bash
# From miniforge repo root:
cd examples/demo-polyglot-repo
git init && git add -A && git commit -m "demo"

# Run the demo
bb miniforge init .
bb miniforge scan                    # policy packs + linters
bb miniforge scan --semantic         # + LLM behavioral analysis
bb miniforge scan --execute          # apply auto-fixes
```
