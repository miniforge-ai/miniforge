(ns ai.miniforge.mcp-artifact-server.interface
  "Public API for the MCP artifact server component.

   Provides:
   - start-server: Run the MCP server (blocking stdin/stdout loop)
   - handle-tool-call: Direct tool invocation for in-process testing
   - tool-definitions: List of registered MCP tool definitions"
  (:require [ai.miniforge.mcp-artifact-server.server :as server]
            [ai.miniforge.mcp-artifact-server.tools :as tools]))

(defn start-server
  "Start the MCP artifact server.

   Reads JSON-RPC 2.0 messages from stdin, writes responses to stdout.
   Blocks until stdin is closed.

   Arguments:
   - artifact-dir — directory path where artifact.edn will be written"
  [artifact-dir]
  (server/run-server artifact-dir))

(defn handle-tool-call
  "Invoke a tool handler directly (for testing without JSON-RPC).

   Arguments:
   - tool-name — string name of the tool (e.g. \"submit_plan\")
   - params — map of tool arguments (string keys)
   - artifact-dir — directory path for artifact persistence

   Returns {:content [{:type \"text\" :text message}]}
   Throws on unknown tool or validation failure."
  [tool-name params artifact-dir]
  (tools/handle-tool-call tool-name params
                          (fn [artifact]
                            (let [path (str artifact-dir "/artifact.edn")]
                              (spit path (pr-str artifact))
                              path))))

(def tool-definitions
  "List of MCP tool definitions registered by this component."
  tools/tool-definitions)

(def tool-registry
  "Full tool registry map (tool-name → tool config)."
  tools/tool-registry)
