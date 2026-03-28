# Web Dashboard v2 - UI Design Mockups

**Design Philosophy**: Tableau-inspired density + Terminal aesthetic

## Color Palette

```
Background:       #1e1e1e  ████████
Surface:          #2d2d2d  ████████
Surface Elevated: #353535  ████████
Border:           #3d3d3d  ────────
Text:             #d4d4d4  ████████
Text Muted:       #888888  ████████
Primary:          #00d4ff  ████████ (cyan)
Success:          #4ec9b0  ████████ (teal)
Warning:          #dcdcaa  ████████ (yellow)
Error:            #f48771  ████████ (salmon)
```

## Typography

```
Font: 'SF Mono', 'JetBrains Mono', 'Menlo', monospace
Base Size: 14px
Line Height: 1.6
```

---

## View 1: Dashboard Overview (Landing Page)

**Route**: `/`

**Purpose**: High-density metrics overview, Tableau-inspired

```
╔═══════════════════════════════════════════════════════════════════════╗
║ MINIFORGE                  🔴 2 HIGH  🟡 5 MED  🟢 40 LOW            ║
║                            ⏳ 3 Run   ✓ 45 Done  ✗ 2 Fail            ║
╠══════╦════════════════════════════════════════════════════════╦══════╣
║ Nav  ║ Dashboard Overview               Last Update: 2s ago   ║ Act  ║
║──────║────────────────────────────────────────────────────────║──────║
║ •Dash║ ┌─────────┬─────────┬─────────┬─────────┬─────────┐  ║Batch ║
║ Work ║ │   12    │   47    │   3     │   45    │   2     │  ║Approv║
║ PRs  ║ │ REPOS   │ PRs     │ RUNNING │ DONE    │ FAILED  │  ║      ║
║ Trn  ║ └─────────┴─────────┴─────────┴─────────┴─────────┘  ║Refres║
║ Evid ║                                                        ║      ║
║ Task ║ ┌─────────────────────────┬──────────────────────────┐║Filter║
║      ║ │ PR Risk Breakdown       │ Workflow Status (24h)    │║      ║
║      ║ │                         │                          │║Search║
║      ║ │      ╱───╲              │ ▂▃▅▇█▇▅▃▂▁              │║      ║
║      ║ │    ╱🔴 4% ╲             │                          │║      ║
║      ║ │   │🟡 11%  │            │ ✓ 45  ✗ 2  ⏳ 3          │║      ║
║      ║ │    ╲🟢 85%╱             │                          │║      ║
║      ║ │      ───                │ Avg: 12min               │║      ║
║      ║ │                         │ Success: 95.7%           │║      ║
║      ║ └─────────────────────────┴──────────────────────────┘║      ║
║      ║                                                        ║      ║
║      ║ Recent Activity                                       ║      ║
║      ║ ┌────────────────────────────────────────────────────┐║      ║
║      ║ │ ✓ PR #42 approved (acme/api)           2 min ago   │║      ║
║      ║ │ ⏳ Workflow started (feature/auth)      5 min ago   │║      ║
║      ║ │ ✗ Workflow failed (fix/rate-limit)     8 min ago   │║      ║
║      ║ │ ✓ PR #38 merged (acme/infra)          15 min ago   │║      ║
║      ║ └────────────────────────────────────────────────────┘║      ║
╚══════╩════════════════════════════════════════════════════════╩══════╝
```

**Components**:
- **Metric Cards**: Large numbers, clear labels, subtle borders
- **Pie Chart**: SVG with color-coded slices, percentage labels
- **Sparkline**: SVG polyline showing trend over time
- **Activity Feed**: Scrollable list, icons for status, relative timestamps

---

## View 2: PR Fleet (Multi-Repo PR Monitoring)

**Route**: `/prs`

**Purpose**: See all PRs across repos, risk analysis, batch operations

```
╔═══════════════════════════════════════════════════════════════════════╗
║ PR Fleet                    [Filter ▾] [Sort: Risk ▾]    [✓ Batch]   ║
╠═══════════════════════════════════════════════════════════════════════╣
║ ☑ acme/api (3 PRs, 1 high-risk)                                      ║
║ ├─☐ #42  🟢 LOW    Add auth endpoint         +50 -20      1h ago    ║
║ │  └─ AI: Adds JWT middleware, well-tested                           ║
║ ├─☐ #45  🟡 MED    Fix rate limiter          +120 -45      3h ago    ║
║ │  └─ AI: Refactors limiter logic, 5 files changed                   ║
║ └─☐ #47  🔴 HIGH   Refactor database         +850 -320     1d ago    ║
║    └─ AI: Major DB migration, needs careful review                   ║
║                                                                        ║
║ ▸ acme/frontend (2 PRs, 0 high-risk)                                 ║
║ ▸ acme/infra (1 PR, 1 high-risk)                                     ║
║                                                                        ║
║ Selected: 2 PRs (all low-risk)                      [Approve All]    ║
╚═══════════════════════════════════════════════════════════════════════╝
```

**Features**:
- **Tree View**: Collapsible repos, indented PRs
- **Risk Badges**: Color-coded dots (🔴🟡🟢) with risk level
- **AI Preview**: Collapsed summary under each PR
- **Batch Selection**: Checkboxes, "Select all low-risk" button
- **Filters**: Risk level, repo, author, age
- **Sort**: Risk, age, changes, CI status

---

## View 3: PR Detail (Deep Dive)

**Route**: `/pr/:repo/:number`

**Purpose**: Full analysis of single PR with AI and actions

```
╔═══════════════════════════════════════════════════════════════════════╗
║ PR #42 - Add JWT authentication                [Approve] [Reject]    ║
║ 📦 acme/api  👤 alice  📊 +50/-20  ⏰ 1 hour ago  ✓ CI Passing      ║
╠═══════════════════════════════════════════════════════════════════════╣
║ ┌──────────┬──────────┬──────────┬──────────┬──────────┐            ║
║ │ 🟢 LOW   │ SIMPLE   │   70     │    3     │   ✓      │            ║
║ │ Risk     │ Complex  │ Changes  │ Files    │ CI       │            ║
║ └──────────┴──────────┴──────────┴──────────┴──────────┘            ║
║                                                                        ║
║ 🤖 AI Summary                                          [Regenerate]   ║
║ ┌────────────────────────────────────────────────────────────────┐  ║
║ │ This PR adds JSON Web Token (JWT) authentication to the API.  │  ║
║ │ Key changes:                                                   │  ║
║ │ • Creates auth middleware for token validation                │  ║
║ │ • Adds user context to requests                               │  ║
║ │ • Includes comprehensive test coverage                        │  ║
║ │                                                                 │  ║
║ │ Risk Assessment: LOW                                           │  ║
║ │ • Small, focused change (70 lines)                             │  ║
║ │ • Follows existing auth patterns                              │  ║
║ │ • All tests passing, good coverage                            │  ║
║ │                                                                 │  ║
║ │ Recommendation: Safe to approve and merge                     │  ║
║ └────────────────────────────────────────────────────────────────┘  ║
║                                                                        ║
║ 💬 Ask a Question                                                     ║
║ ┌────────────────────────────────────────────────────────────────┐  ║
║ │ What security considerations are there?              [Ask]     │  ║
║ └────────────────────────────────────────────────────────────────┘  ║
║                                                                        ║
║ Conversation:                                                         ║
║ ┌────────────────────────────────────────────────────────────────┐  ║
║ │ Q: How does this handle token expiration?                      │  ║
║ │ A: The middleware checks the exp claim in the JWT payload...   │  ║
║ └────────────────────────────────────────────────────────────────┘  ║
║                                                                        ║
║ 📋 Files Changed                                    [View Full Diff]  ║
║ • src/middleware/auth.js        +35 -5                                ║
║ • src/routes/api.js             +10 -8                                ║
║ • test/middleware/auth.test.js  +25 -0                                ║
╚═══════════════════════════════════════════════════════════════════════╝
```

**Components**:
- **Stats Grid**: 5 metric cards in a row
- **AI Summary**: Expandable section with markdown rendering
- **Chat Interface**: Input box + conversation history
- **File List**: Tree with diff stats (+/- lines)
- **Action Bar**: Approve, Reject, Comment buttons

---

## View 4: Train View (PR Dependencies)

**Route**: `/trains/:train-id`

**Purpose**: Visualize PR dependency chains for ordered merging

```
╔═══════════════════════════════════════════════════════════════════════╗
║ Train: feature/auth-system (3 PRs)           [Approve All] [Merge →] ║
╠═══════════════════════════════════════════════════════════════════════╣
║ Merge Order Visualization:                                           ║
║                                                                        ║
║   #42 ────┐                                                           ║
║           ├──→ #43 ────┐                                              ║
║   #40 ────┘            └──→ #44                                       ║
║                                                                        ║
║ PRs in Order:                                                         ║
║ ┌────────────────────────────────────────────────────────────────┐  ║
║ │ 1. #42  Add auth endpoint               ✓ Ready to merge      │  ║
║ │    🟢 LOW   +50 -20   alice   ✓ CI Pass  ✓ Reviews: 2/2      │  ║
║ │    └─→ Blocks: #43                                            │  ║
║ ├────────────────────────────────────────────────────────────────┤  ║
║ │ 2. #43  Add user service                ● CI running          │  ║
║ │    🟡 MED   +120 -45  bob     ⏳ CI       ✓ Reviews: 2/2      │  ║
║ │    └─→ Blocks: #44                                            │  ║
║ ├────────────────────────────────────────────────────────────────┤  ║
║ │ 3. #44  Add admin dashboard             ⏳ Waiting for #43    │  ║
║ │    🟢 LOW   +80 -30   carol   ● CI       ✗ Reviews: 1/2      │  ║
║ └────────────────────────────────────────────────────────────────┘  ║
║                                                                        ║
║ Next Action: Merge #42 (ready), then wait for #43 CI to complete     ║
╚═══════════════════════════════════════════════════════════════════════╝
```

**Features**:
- **Dependency Graph**: ASCII art or SVG arrows showing dependencies
- **Ordered List**: PRs in merge order with readiness indicators
- **Readiness Checks**: CI status, reviews, merge conflicts
- **Smart Actions**: "Approve All" only approves ready PRs, "Merge →" sequences merges

---

## View 5: Workflow List

**Route**: `/workflows`

**Purpose**: Monitor active workflows (N5 §3.2.1)

```
╔═══════════════════════════════════════════════════════════════════════╗
║ Workflows                              [Filter ▾] [Search...]         ║
╠═══════╦═════════════════╦═══════╦═════════╦════════════╦═════════════╣
║ Status║ Name            ║ Phase ║Progress ║Agent Status║ Time        ║
╠═══════╬═════════════════╬═══════╬═════════╬════════════╬═════════════╣
║ ⏳ RUN ║ feature/auth    ║ Plan  ║ ▓▓▓▓░░  ║ Analyzing  ║ 5 min       ║
║ ✓ DONE║ fix/rate-limit  ║ Verify║ ▓▓▓▓▓▓  ║ Tests pass ║ 15 min      ║
║ ✗ FAIL║ refactor/db     ║ Impl  ║ ▓▓░░░░  ║ Build err  ║ 1 hour      ║
║ ◐ BLCK║ feature/ui      ║ Spec  ║ ▓░░░░░  ║ Waiting    ║ 2 hours     ║
╚═══════╩═════════════════╩═══════╩═════════╩════════════╩═════════════╝
```

**Click row** → Navigate to `/workflow/:id` (detail view)

---

## View 6: Workflow Detail

**Route**: `/workflow/:id`

**Purpose**: Phase progression and agent output (N5 §3.2.2)

```
╔═══════════════════════════════════════════════════════════════════════╗
║ Workflow: feature/auth                            [← Back to List]   ║
╠═════════════════╦═════════════════════════════════════════════════════╣
║ Phases          ║ Agent Output                                        ║
║─────────────────║─────────────────────────────────────────────────────║
║ ✓ Spec          ║ [Spec] Created specification for JWT authentication ║
║ ✓ Plan          ║ ...                                                 ║
║ ● Implement     ║ [Implement] Creating middleware...                  ║
║ ○ Verify        ║ > Creating src/middleware/auth.js                   ║
║ ○ Release       ║ > Adding JWT verification                           ║
║                 ║ > Writing tests...                                  ║
║                 ║                                                      ║
║                 ║ [Copy] [Download]                                   ║
║                 ║─────────────────────────────────────────────────────║
║                 ║ Auto-scrolling output... (streaming)                ║
╚═════════════════╩═════════════════════════════════════════════════════╝
```

**Components**:
- **Phase Timeline**: Vertical list with status icons (✓ ● ○ ✗)
- **Agent Output**: Streaming text area, auto-scroll, syntax highlighting
- **Actions**: Copy output, download log

---

## Design System Components

### Buttons

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ Primary  │  │Secondary │  │  Danger  │  │  Ghost   │
└──────────┘  └──────────┘  └──────────┘  └──────────┘
 #00d4ff bg   #3d3d3d bg    #f48771 bg    transparent
```

### Badges

```
🔴 HIGH    🟡 MEDIUM   🟢 LOW      ● Running
✓ Success  ✗ Failed    ⏳ Waiting  ○ Pending
```

### Progress Bars

```
[▓▓▓▓▓▓▓░░░] 70%    (filled: primary, empty: border color)
```

### Cards

```
┌─────────────────┐
│ 12              │  (number: large, bold)
│ REPOS           │  (label: small, muted)
└─────────────────┘
```

### Tables

```
╔═══════╦═════════╦═════════╗
║ Header║ Header  ║ Header  ║
╠═══════╬═════════╬═════════╣
║ Cell  ║ Cell    ║ Cell    ║
║ Cell  ║ Cell    ║ Cell    ║
╚═══════╩═════════╩═════════╝
```

---

## Responsive Breakpoints

### Desktop (>1200px)
- 3-column layout (nav | main | actions)
- Tables show all columns
- Charts full size

### Tablet (768-1199px)
- 2-column layout (nav collapses to icons | main+actions)
- Tables scroll horizontally
- Charts scale down

### Mobile (<768px)
- 1-column stacked layout
- Nav becomes hamburger menu
- Cards stack vertically
- Tables scroll horizontally with pinned first column

---

## Interaction Patterns

### htmx Attributes

```html
<!-- Auto-refresh every 5s -->
<div hx-get="/api/workflows" hx-trigger="every 5s">

<!-- Click to load detail -->
<tr hx-get="/api/pr/acme/api/42" hx-target="#detail-panel">

<!-- Form submission -->
<button hx-post="/api/pr/acme/api/42/approve" hx-confirm="Approve this PR?">
```

### WebSocket Events

```javascript
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  switch(msg['event/type']) {
    case 'workflow/started':
      htmx.trigger('#workflow-list', 'refresh');
      break;
    case 'pr/approved':
      htmx.trigger('#pr-fleet', 'refresh');
      break;
  }
};
```

---

## File Organization

```
components/web-dashboard/
├── src/ai/miniforge/web_dashboard/
│   ├── interface.clj          (public API: start!, stop!)
│   ├── server.clj             (HTTP routes, WebSocket)
│   ├── components.clj         (reusable hiccup components)
│   ├── charts.clj             (SVG chart generators)
│   ├── metrics.clj            (aggregate data from event stream)
│   ├── github.clj             (gh CLI wrapper)
│   ├── ai.clj                 (LLM integration for summaries/chat)
│   ├── risk.clj               (PR risk analysis)
│   └── views/
│       ├── dashboard.clj      (overview page)
│       ├── pr_fleet.clj       (PR list)
│       ├── pr_detail.clj      (PR deep dive)
│       ├── train.clj          (train view)
│       ├── workflow_list.clj  (workflow table)
│       ├── workflow_detail.clj(workflow phases + output)
│       ├── evidence.clj       (evidence tree)
│       ├── artifacts.clj      (artifact browser)
│       └── dag_kanban.clj     (task kanban)
└── resources/public/
    └── css/
        ├── design-system.css  (variables, reset, utilities)
        └── app.css            (component styles, layout)
```

---

## Next Steps

1. **Review this spec**: Does it capture the vision?
2. **Dogfoodability assessment**: Can miniforge implement Phase 1-2 autonomously?
3. **Start implementation**: Begin with Phase 1 (design system) as a test

**Recommendation**: Hand Phase 1 to miniforge as a dogfooding experiment. If it produces a clean, well-architected design system, proceed with autonomous implementation. If it struggles with aesthetic decisions, provide guidance and iterate.
