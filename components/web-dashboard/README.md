# Web Dashboard Component

Real-time workflow monitoring dashboard with server-side rendering + htmx.

## Architecture

- **Backend**: Clojure with http-kit (HTTP + WebSocket server)
- **Frontend**: Server-rendered HTML (hiccup) + htmx for dynamic updates
- **No build step**: Direct deployment via Homebrew

## Design Principle: Simple Made Easy

This implementation chooses **simple** (server-side rendering) over **easy** (ClojureScript/re-frame):

- **One language** (Clojure) - no ClojureScript build step
- **Direct rendering** - hiccup generates HTML
- **htmx for interactivity** - declarative HTML attributes
- **WebSocket for push** - real-time event stream
- **No build complexity** - works immediately via Homebrew

## Features

**5 N5 Views:**

1. **Workflow List** (N5 3.2.1) - Table with status, phase, progress
2. **Workflow Detail** (N5 3.2.2) - Phase list + agent output
3. **Evidence** (N5 3.2.3) - Tree view of evidence artifacts
4. **Artifacts** (N5 3.2.4) - File browser + content viewer
5. **DAG Kanban** (N5 3.2.5) - Task states in kanban columns

**Interactions:**

- Click navigation (no page reloads)
- Real-time updates via WebSocket + htmx polling
- Search/filter workflows
- Sort by column headers
- Expand/collapse trees
- Copy agent output

## Usage

```bash
# Start the dashboard
miniforge dashboard

# Custom port
miniforge dashboard --port 3000

# Auto-open browser
miniforge dashboard --open
```

Visit <http://localhost:8080> to see the dashboard.

## Security

The dashboard binds to `127.0.0.1` unless `MINIFORGE_WEB_HOST` names another
address. On a loopback bind it answers only to `localhost`, `127.0.0.1`, `[::1]` or
its configured address on its own port in the `Host` header (a `Host`
without a port means 80), so a DNS rebinding attack cannot reach it.

On any bind it:

1. refuses a WebSocket upgrade (`/ws`, `/ws/events`) or a state-changing
   request whose `Origin` is not the dashboard's own;
2. refuses a state-changing request with a `text/plain` body;
3. accepts events on `/api/events/ingest` and `/ws/events` only with this
   start's token in the `X-Miniforge-Ingest-Token` header. The token is in
   the discovery file `~/.miniforge/dashboard.port`, which only its owner
   can read.

A public bind requires operator login (`MINIFORGE_WEB_USERNAME` and
`MINIFORGE_WEB_PASSWORD`). The dashboard does not terminate TLS, so put a
public bind behind a TLS proxy that sets `X-Forwarded-Proto: https`, and
set `MINIFORGE_WEB_TRUST_PROXY=true` so the dashboard believes that
header (an explicit `:trust-proxy? false` option overrides it, and a
loopback bind ignores forwarded headers). Origins are then compared as `https` on port 443, and the session
cookie carries the `Secure` flag. Without the setting, X-Forwarded-Proto
is ignored.

## Structure

```text
components/web-dashboard/
├── deps.edn                    # Dependencies (http-kit, hiccup, jsonista)
├── src/                        # Clojure backend
│   └── ai/miniforge/web_dashboard/
│       ├── interface.clj       # Public API (start!, stop!, get-port)
│       ├── server.clj          # HTTP + WebSocket server
│       └── views.clj           # Server-rendered views (all 5 N5 views)
├── resources/public/           # Static assets
│   └── css/app.css             # Styles
└── test/                       # Tests
    └── ai/miniforge/web_dashboard/
        └── interface_test.clj
```

## Technical Details

**Server-side rendering:**

- `hiccup` generates HTML from Clojure data structures
- Views are pure functions returning hiccup vectors
- No JavaScript framework - just htmx (loaded from CDN)

**Dynamic updates:**

- htmx handles client-side interactions via HTML attributes
- WebSocket pushes events and triggers htmx refreshes
- Polling for updates every 2 seconds (configurable)

**State management:**

- Server maintains workflow state via event-stream queries
- No client-side state - server is source of truth
- WebSocket for real-time push notifications

## Why Not ClojureScript?

The initial implementation used ClojureScript/re-frame, but we pivoted to server-side rendering because:

1. **No additional complexity** - No build toolchain, one language
2. **Aligns with Homebrew deployment** - No compilation needed at install time
3. **Follows "Simple Made Easy"** - Fewer braids (concepts/dependencies)
4. **Meets all requirements** - All N5 views work with server rendering
5. **Better startup** - No JS framework overhead

htmx provides sufficient interactivity for our use case without the complexity of a full SPA framework.
