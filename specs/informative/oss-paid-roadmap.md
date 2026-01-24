# miniforge.ai: Product Strategy (Corrected)

**Date:** 2026-01-22
**Clarity:** OSS = Individual's Software Factory, Paid = Organization's Fleet

---

## The Product Strategy (Clarified)

### OSS: The Complete Software Factory (Local)

**What it is:**
An autonomous software factory that runs on your local machine. Full SDLC automation from spec to production.

**What you get:**

- ✅ Full autonomous workflow execution (spec → agents → code → PRs → deployed)
- ✅ All specialized agents (planner, implementer, tester, reviewer)
- ✅ Inner/outer loops with validation & repair
- ✅ Policy gates with semantic intent validation
- ✅ Self-improving meta loop (learns from your execution)
- ✅ Local knowledge base (patterns from your workflows)
- ✅ Evidence bundles & full provenance
- ✅ Multi-repo orchestration (DAG, PR trains)
- ✅ **Local fleet management** - Operations console for YOUR workflows

**User:** Individual developer or small team (everyone runs their own instance)

**Value Prop:** "Stop writing boilerplate. Write specs, let the factory build it."

**Limitations:**

- Single user/machine
- Learning stays local (not shared across team)
- No central visibility
- Manual coordination if multiple people working on same repos

**Price:** Free (Apache 2.0)

---

### Paid: Distributed Fleet Management (Organization-Scale)

**What it is:**
Central coordination and observability for multiple miniforge instances across a team/org.

**What you get (everything OSS plus):**

#### 1. Central Operations Console

```
┌────────────────────────────────────────────────────────────────┐
│  Acme Corp Fleet Dashboard                                     │
├────────────────────────────────────────────────────────────────┤
│  ACTIVE ACROSS ORG (47 workflows, 12 users, 23 repos)          │
│                                                                 │
│  BY USER                                                        │
│  ├─ @alice (3 workflows)                                       │
│  │  ├─ rds-import        [████████▓▓] 80%  (Verify phase)     │
│  │  ├─ add-auth          [███▓▓▓▓▓▓▓] 30%  (Design phase)     │
│  │  └─ k8s-migration     [██▓▓▓▓▓▓▓▓] 20%  (Plan phase)       │
│  │                                                              │
│  ├─ @bob (2 workflows)                                         │
│  │  ├─ update-vpc        [██████████] 100% (Ready to merge)   │
│  │  └─ lambda-deploy     [█████▓▓▓▓▓] 50%  (Implement phase)  │
│  │                                                              │
│  └─ ... 10 more users                                          │
│                                                                 │
│  BY REPO                                                        │
│  ├─ acme/terraform-modules  (8 active workflows)               │
│  ├─ acme/terraform-live     (12 active workflows)              │
│  ├─ acme/k8s-manifests      (6 active workflows)               │
│  └─ acme/backend            (4 active workflows)               │
│                                                                 │
│  CONFLICTS DETECTED (2)                                         │
│  ⚠ Alice & Bob both modifying acme/terraform-modules/vpc       │
│  ⚠ Charlie's workflow waiting for Alice's RDS import           │
│                                                                 │
│  ORG-WIDE LEARNING                                              │
│  ├─ 1,247 patterns captured this month                         │
│  ├─ 89 heuristics improved org-wide                            │
│  └─ 23 new policy rules from team learnings                    │
└────────────────────────────────────────────────────────────────┘
```

#### 2. Aggregate Learning

- **OSS:** Your miniforge learns from your workflows
- **Paid:** Entire org learns from everyone's workflows

```
Alice runs RDS import workflow (OSS instance)
→ Her miniforge learns the pattern locally

With Paid:
→ Pattern captured by central fleet
→ Bob's miniforge downloads learned pattern
→ Bob's next RDS import uses Alice's learning
→ Charlie's miniforge also benefits
→ Org knowledge base grows collectively
```

**Result:** Team gets better together, not just individually

#### 3. Team Coordination

- **Conflict detection:** "Alice and Bob both editing vpc module"
- **Dependency awareness:** "Wait for Alice's RDS import before starting k8s changes"
- **Resource management:** "Max 10 concurrent workflows to avoid rate limits"
- **Workflow queueing:** "Your workflow queued behind Alice's (same repo)"

#### 4. RBAC & Approvals

- **Role-based access:** Junior devs auto-approve low-risk, escalate high-risk
- **Approval workflows:** Infra changes require platform team approval
- **Audit trail:** Central log of who approved what, when, why
- **Policy distribution:** Org-wide policy packs, version controlled

#### 5. Analytics & Insights

- **Team velocity:** How many workflows per week, success rate, time to production
- **Cost tracking:** LLM token usage per user, per workflow, per repo
- **Learning velocity:** How fast is org knowledge base growing
- **Bottleneck detection:** Which phase takes longest, which agent is busiest
- **MTTR:** Mean time to recovery when workflows fail

#### 6. Shared Policy & Knowledge

- **Central policy marketplace:** Org creates/shares policy packs internally
- **Shared heuristics:** Best practices distributed org-wide
- **Pattern library:** Successful workflows become templates
- **Knowledge curation:** Platform team curates org-specific patterns

**User:** Teams, organizations (10+ developers)

**Value Prop:** "Coordinate autonomous software production across your entire engineering org"

**Price:** TBD (per-seat, per-repo, or hybrid)

---

## The OSS/Paid Boundary (Polylith Clean Split)

### OSS Components (Open Source)

**All in monorepo: `miniforge-oss/`**

```
components/
├── schema/              # Domain types
├── logging/             # Structured logging
├── llm/                 # LLM client
├── tool/                # Tool protocol
├── agent/               # Agent runtime + specialized agents
├── task/                # Task management
├── loop/                # Inner/outer loops
├── workflow/            # Workflow orchestration
├── knowledge/           # Local knowledge base (Zettelkasten)
├── policy/              # Policy engine
├── policy-pack/         # Policy pack registry
├── heuristic/           # Heuristic registry
├── artifact/            # Artifact store
├── repo-dag/            # Repo dependency graph
├── pr-train/            # PR train orchestration
├── gate/                # Validation gates
├── observer/            # Pattern observation
├── operator/            # Local operator agent
└── reporting/           # Local reporting

bases/
├── cli/                 # Command-line interface
└── local-fleet/         # Local operations console (TUI/Web)

projects/
└── oss-cli/             # OSS build configuration
```

**What OSS can do:**

- Run workflows on local machine
- Full autonomous SDLC
- Self-improvement (local learning)
- Policy enforcement
- Multi-repo orchestration
- Local fleet view (your workflows only)

### Paid Components (Commercial)

**Additional components: `miniforge-enterprise/`**

```
components/
├── fleet-distributed/   # Multi-instance coordination
├── fleet-analytics/     # Org-wide analytics
├── authz/               # RBAC, approvals, audit
├── knowledge-shared/    # Org-wide knowledge sharing
├── policy-distribution/ # Central policy marketplace
├── conflict-detection/  # Cross-user conflict detection
├── resource-management/ # Org-wide resource limits
└── telemetry/           # Central telemetry aggregation

bases/
├── fleet-server/        # Central fleet coordinator
├── fleet-dashboard/     # Org-wide web dashboard
└── api-server/          # REST API for fleet management

projects/
└── enterprise/          # Enterprise build configuration
```

**What Paid adds:**

- Central visibility across all users
- Aggregate learning (org knowledge base)
- Team coordination (conflict detection)
- RBAC & approval workflows
- Org-wide analytics
- Shared policy packs
- Resource management at scale

---

## User Journey

### Individual Developer (OSS)

```
Day 1: Install miniforge
$ brew install miniforge
$ miniforge init

Day 2: First workflow
$ miniforge run rds-import-spec.edn
# Watches agents work in local console
# Learns the pattern

Day 3: Second workflow (similar)
$ miniforge run elasticache-import-spec.edn
# Faster because learned from Day 2

Week 2: Regular use
# Running 3-5 workflows per week
# Local knowledge base growing
# Policy packs customized for projects

Month 2: Power user
# Workflows complete in minutes
# Meta loop improved heuristics
# Rarely needs manual intervention
```

### Team of 10 (Paid)

```
Month 1: Team onboarding
- Everyone installs OSS
- Connect to central fleet server
- Shared policy packs distributed
- Org knowledge base initialized

Month 2: Team coordination
- Alice runs RDS workflow → team learns pattern
- Bob's workflow uses Alice's pattern (faster)
- Platform team sets org policies
- Junior devs auto-approve low-risk changes

Month 3: Scaling
- 10 users, 50 workflows/week
- Central dashboard shows bottlenecks
- Analytics: "Implementer agent is bottleneck, add capacity"
- Learning velocity: Org knowledge grows 20% per week

Month 6: Maturity
- 500+ patterns in org knowledge base
- 95% success rate (up from 70%)
- 3x velocity (intent → production in 2 hours vs 2 days)
- Team of 10 shipping like team of 30
```

---

## Why This Split Works

### OSS Value (Complete on Its Own)

- Individual dev gets full autonomous factory
- No feature crippling ("upgrade to run workflows")
- Local learning works
- Can use forever for free

**Result:** Real adoption, real users, real feedback

### Paid Value (Clear Upgrade Path)

- Team hits coordination pain ("Alice and I both changed vpc")
- Need visibility ("What's everyone working on?")
- Want shared learning ("Why can't Bob use Alice's patterns?")
- Analytics matter ("Which workflows take longest?")

**Result:** Natural upgrade when team grows beyond 3-5 people

### OSS → Paid Conversion Triggers

1. **>3 users on same repos**
   - Conflict detection becomes valuable
   - Coordination overhead without it

2. **Knowledge silos**
   - Alice learned RDS patterns, Bob learning same thing
   - Wasteful duplication

3. **Management visibility**
   - Engineering manager wants to see team velocity
   - No visibility into autonomous work

4. **Compliance/audit**
   - Need central audit trail
   - Approval workflows required

5. **Policy consistency**
   - Different users have different policy packs
   - Want org-wide standards

**Upgrade pitch:** "Your team is already productive with OSS. Paid coordinates everyone."

---

## Go-to-Market Strategy

### Phase 1: OSS Beta (Week 8)

**Target:** 100 individual developers

**Messaging:**
> "Autonomous software factory for platform engineers. Write specs, let agents build it. Free, open-source, local-first."

**Channels:**

- HackerNews launch
- r/devops, r/terraform, r/kubernetes
- Twitter (DevOps/SRE community)
- Blog: "I built a self-improving Terraform workflow system"

**Success Metrics:**

- 100+ installs (Homebrew analytics)
- 20+ active weekly users
- 5+ GitHub contributors
- 10+ community policy packs

### Phase 2: OSS Growth (Months 2-3)

**Target:** 500+ users, identify design partners

**Messaging:**
> "Join 500+ platform engineers using miniforge to automate infrastructure changes"

**Channels:**

- Case studies from power users
- Conference talks (HashiConf, KubeCon)
- Documentation & tutorials
- Community Slack/Discord

**Success Metrics:**

- 500+ active users
- 20+ community contributors
- 50+ community policy packs
- 10+ design partners identified for Paid

### Phase 3: Paid Preview (Month 4)

**Target:** 10 teams (5-20 developers each)

**Messaging:**
> "Your team is already using miniforge OSS. Coordinate everyone with distributed fleet management."

**Channels:**

- Direct outreach to OSS power users
- "Upgrade" prompt in OSS when multiple users detected
- Design partner program

**Features:**

- Central dashboard (web)
- Aggregate learning
- Team coordination
- RBAC basics

**Pricing:** $X/user/month (validate with design partners)

**Success Metrics:**

- 10 paying teams
- $10K+ MRR
- 90%+ retention
- 10%+ OSS → Paid conversion

### Phase 4: General Availability (Month 6)

**Target:** 50+ teams, $50K+ MRR

**Messaging:**
> "Coordinate autonomous software production across your engineering org"

**Channels:**

- Enterprise sales (for 20+ seat orgs)
- Self-serve for smaller teams
- Partner channel (consultancies, SIs)

**Features:**

- Full analytics suite
- Advanced RBAC
- SSO integrations
- Policy marketplace
- SLA/support tiers

**Success Metrics:**

- 50+ paying teams
- $50K+ MRR
- <5% churn
- 2-3 enterprise customers (100+ seats)

---

## Pricing Model (To Validate)

### OSS

- **Free forever**
- Full feature set
- Local fleet management
- Community support (GitHub Discussions)

### Paid Tiers

#### Team ($49/user/month)

- Up to 50 users
- Central fleet dashboard
- Aggregate learning
- Team coordination
- Basic RBAC
- Email support

#### Enterprise ($99/user/month)

- Unlimited users
- Advanced analytics
- Custom policy marketplace
- SSO (Okta, Auth0)
- Advanced RBAC
- Audit logs
- Dedicated support
- On-prem deployment option

#### Add-ons

- **Premium Support:** $500/month (SLA, Slack access)
- **Professional Services:** Implementation, training, custom scanners
- **Managed Hosting:** Run fleet server for you

**Alternative Pricing:**

- Per-repo instead of per-seat ($X/repo/month)
- Hybrid: Base fee + per-user
- Usage-based: LLM token consumption

**To validate with design partners:**

- What's the budget for tools like this?
- Per-seat vs per-repo preference?
- What features justify what price?

---

## Competitive Positioning

### vs. Agent CLI Frameworks (Claude Code, Cursor, etc.)

**They are:**

- AI assistants (human-in-the-loop)
- Single agent
- Conversational
- No learning
- No coordination

**miniforge is:**

- Autonomous factory (agents-in-the-loop)
- Multi-agent orchestra
- Specification-driven
- Self-improving
- Team coordination (Paid)

**When to use Agent CLIs:** Quick tasks, exploration, learning
**When to use miniforge:** Repetitive workflows, multi-repo changes, team coordination

### vs. GitHub Copilot Workspace

**Copilot Workspace:**

- IDE-integrated
- AI pair programming
- GitHub-centric
- Single user
- Closed source

**miniforge:**

- CLI/Web-based
- Autonomous workflow execution
- Multi-platform (GitHub, GitLab, Bitbucket)
- Team coordination (Paid)
- Open source core

**Differentiation:** Autonomy, multi-repo, team scale, self-improvement

### vs. Devin/Cognition

**Devin:**

- Full AI software engineer
- Black box (no control)
- Expensive
- Early stage

**miniforge:**

- Specialized for infrastructure/platform work
- White box (see agents working, intervene)
- Affordable (OSS free, Paid reasonable)
- Production-ready

**Differentiation:** Transparency, control, cost, platform focus

---

## Revenue Model

### Year 1 Targets

- **OSS Users:** 1,000+ active monthly
- **Paid Teams:** 50
- **Average Seats:** 10/team
- **Price:** $49/user/month
- **MRR:** $24.5K
- **ARR:** $294K

### Year 2 Targets

- **OSS Users:** 5,000+ active monthly
- **Paid Teams:** 200
- **Average Seats:** 15/team
- **Price:** $49/user/month (Team tier)
- **MRR:** $147K
- **ARR:** $1.76M

**Path to profitability:**

- Year 1: Build OSS, validate Paid
- Year 2: Scale Paid, break even
- Year 3: Profitable, enterprise focus

---

## The Wedge

### Entry Point: OSS Individual Use

"Try the autonomous software factory for free on your machine"

### Expansion: Team Coordination

"Your team is already using it, coordinate everyone for $49/user/month"

### Enterprise: Fleet at Scale

"Run software factories across 100+ engineers with central visibility"

### Ultimate: The Platform

"Every engineer has an AI factory, coordinated org-wide"

---

## Summary

### OSS (Free)

**Product:** Complete autonomous software factory (local)
**User:** Individual developers, small teams
**Value:** Stop writing boilerplate, let agents build it
**Monetization:** None (pure OSS)

### Paid ($49-99/user/month)

**Product:** Distributed fleet management (org-scale)
**User:** Teams, organizations (10+ developers)
**Value:** Coordinate autonomous production across engineering org
**Monetization:** SaaS subscription

### The Strategy

1. **Build OSS first** - Complete, valuable, free
2. **Grow user base** - 1,000+ individual users
3. **Launch Paid** - Natural upgrade for teams
4. **Scale revenue** - Team → Enterprise → Platform

**This works because:**

- OSS isn't crippled (full value standalone)
- Paid solves real pain (team coordination)
- Clear upgrade path (individual → team → org)
- Moat strengthens over time (learning compounds)

---

**Ready to build the right thing?**
