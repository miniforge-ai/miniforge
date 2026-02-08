# Web Dashboard Component

Real-time workflow monitoring dashboard with WebSocket-based event streaming.

## Architecture

- **Backend**: Clojure with http-kit (HTTP + WebSocket server)
- **Frontend**: ClojureScript with re-frame + reagent
- **Build**: shadow-cljs

## Development

### Building ClojureScript

Compile ClojureScript for development:

```bash
cd components/web-dashboard
clojure -M:cljs watch app
```

Compile for production:

```bash
cd components/web-dashboard
clojure -M:cljs release app
```

### Running the Server

From the repo root:

```bash
# Start the dashboard
clojure -M -m ai.miniforge.cli.main dashboard

# Or with custom port
clojure -M -m ai.miniforge.cli.main dashboard --port 3000
```

Or via the CLI:

```bash
miniforge dashboard
miniforge dashboard --port 3000
```

## Structure

```text
components/web-dashboard/
├── deps.edn                    # Clojure dependencies
├── shadow-cljs.edn             # ClojureScript build config
├── src/                        # Clojure backend
│   └── ai/miniforge/web_dashboard/
│       ├── interface.clj       # Public API
│       └── server.clj          # HTTP + WebSocket server
├── src-cljs/                   # ClojureScript frontend
│   └── ai/miniforge/web_dashboard/
│       ├── core.cljs           # App initialization
│       ├── db.cljs             # App state model
│       ├── events.cljs         # re-frame events
│       ├── subscriptions.cljs  # re-frame subscriptions
│       ├── views.cljs          # UI components
│       └── websocket.cljs      # WebSocket client
├── resources/public/           # Static assets
│   ├── index.html              # Main HTML
│   └── css/app.css             # Styles
└── test/                       # Tests
    └── ai/miniforge/web_dashboard/
        └── interface_test.clj
```

## Features

- Real-time event streaming via WebSocket
- re-frame state management
- Automatic reconnection on disconnect
- Event log with last 100 events
- Workflow tracking

## TODO: 5 N5 Views

The following views will be added in future PRs:

1. **Workflow List** (N5 3.2.1) - Table of active workflows
2. **Workflow Detail** (N5 3.2.2) - Phase list + agent output
3. **Evidence** (N5 3.2.3) - Tree view of evidence artifacts
4. **Artifacts** (N5 3.2.4) - File browser + content viewer
5. **DAG Kanban** (N5 3.2.5) - Task states in kanban columns
