(ns ai.miniforge.mcp-artifact-server.server
  "Main MCP JSON-RPC 2.0 stdin/stdout loop.
   Babashka-compatible."
  (:require [ai.miniforge.mcp-artifact-server.protocol :as protocol]
            [ai.miniforge.mcp-artifact-server.tools :as tools]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Artifact persistence

(defn- write-artifact!
  "Write an artifact EDN map to the artifact directory. Returns the path."
  [artifact-dir artifact]
  (let [path (str artifact-dir "/artifact.edn")]
    (spit path (pr-str artifact))
    path))

;------------------------------------------------------------------------------ Layer 1
;; MCP dispatch

(defn handle-initialize [_params]
  {:protocolVersion "2024-11-05"
   :capabilities {:tools {:listChanged false}}
   :serverInfo {:name "miniforge-artifact-server"
                :version "3.0.0"}})

(defn handle-tools-list [_params]
  {:tools tools/tool-definitions})

(defn handle-tools-call [params artifact-dir]
  (let [tool-name (get params "name")
        arguments (get params "arguments" {})]
    (tools/handle-tool-call tool-name arguments #(write-artifact! artifact-dir %))))

(defn dispatch
  "Route a JSON-RPC method to the appropriate handler."
  [method params artifact-dir]
  (case method
    "initialize"                  (handle-initialize params)
    "tools/list"                  (handle-tools-list params)
    "tools/call"                  (handle-tools-call params artifact-dir)
    "notifications/initialized"   nil
    "notifications/cancelled"     nil
    (throw (ex-info (str "Method not found: " method) {:code -32601}))))

;------------------------------------------------------------------------------ Layer 2
;; Main loop

(defn run-server
  "Run the MCP server loop, reading JSON-RPC messages from stdin.

   Arguments:
   - artifact-dir — directory path for artifact persistence"
  [artifact-dir]
  (binding [*out* *err*]
    (println "miniforge-artifact MCP server started, artifact-dir:" artifact-dir))
  (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))]
    (loop []
      (when-let [line (.readLine reader)]
        (when-not (str/blank? line)
          (try
            (let [msg (protocol/parse-message line)]
              (if msg
                (let [id (get msg "id")
                      method (get msg "method")
                      params (get msg "params" {})]
                  (if id
                    ;; Request — needs response
                    (try
                      (when-let [result (dispatch method params artifact-dir)]
                        (protocol/write-response id result))
                      (catch Exception e
                        (let [code (or (:code (ex-data e)) -32603)]
                          (binding [*out* *err*]
                            (println "Error handling" method ":" (ex-message e)))
                          (protocol/write-error id code (ex-message e)))))
                    ;; Notification — no response
                    (try
                      (dispatch method params artifact-dir)
                      (catch Exception e
                        (binding [*out* *err*]
                          (println "Notification error:" (ex-message e)))))))
                (binding [*out* *err*]
                  (println "Parse error: invalid JSON"))))
            (catch Exception e
              (binding [*out* *err*]
                (println "Parse error:" (ex-message e))))))
        (recur)))))
