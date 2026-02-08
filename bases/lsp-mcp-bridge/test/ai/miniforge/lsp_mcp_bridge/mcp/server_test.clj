(ns ai.miniforge.lsp-mcp-bridge.mcp.server-test
  "Integration test: MCP server protocol via subprocess.

   Spawns the MCP bridge as a subprocess and exchanges
   MCP JSON-RPC messages over stdio."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json])
  (:import
   [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]))

;; Helper: send a JSON line and read response
(defn- send-recv
  "Send a JSON-RPC message and read one line response."
  [^BufferedWriter writer ^BufferedReader reader msg]
  (let [json-str (json/generate-string msg)]
    (.write writer json-str)
    (.write writer "\n")
    (.flush writer)
    ;; Read response line
    (when-let [line (.readLine reader)]
      (json/parse-string line true))))

(deftest mcp-initialize-and-tools-list-test
  (testing "MCP server responds to initialize and tools/list"
    (let [proc (ProcessBuilder.
                 (into-array String
                   ["bb" "-cp"
                    "bases/lsp-mcp-bridge/src:components/tool-registry/resources:components/response/src"
                    "-m" "ai.miniforge.lsp-mcp-bridge.main"]))
          _ (.redirectErrorStream proc false)
          _ (doto (.environment proc)
              (.put "MINIFORGE_PROJECT_DIR" (System/getProperty "user.dir")))
          process (.start proc)
          writer (BufferedWriter.
                   (OutputStreamWriter. (.getOutputStream process) "UTF-8"))
          reader (BufferedReader.
                   (InputStreamReader. (.getInputStream process) "UTF-8"))]

      (try
        ;; 1. Send initialize
        (let [init-resp (send-recv writer reader
                          {:jsonrpc "2.0"
                           :id 1
                           :method "initialize"
                           :params {:capabilities {}}})]
          (is (= 1 (:id init-resp))
              "Response ID should match request")
          (is (some? (:result init-resp))
              "Should have a result")
          (is (string? (get-in init-resp [:result :protocolVersion]))
              "Should have protocol version")
          (is (= "miniforge-lsp" (get-in init-resp [:result :serverInfo :name]))
              "Should identify as miniforge-lsp"))

        ;; 2. Send notifications/initialized
        (let [json-str (json/generate-string
                         {:jsonrpc "2.0"
                          :method "notifications/initialized"})]
          (.write writer json-str)
          (.write writer "\n")
          (.flush writer))

        ;; 3. Send tools/list
        (let [tools-resp (send-recv writer reader
                           {:jsonrpc "2.0"
                            :id 2
                            :method "tools/list"
                            :params {}})]
          (is (= 2 (:id tools-resp))
              "Response ID should match request")
          (is (vector? (get-in tools-resp [:result :tools]))
              "Should have tools array")
          (is (= 10 (count (get-in tools-resp [:result :tools])))
              "Should have 10 tools")
          (let [tool-names (set (map :name (get-in tools-resp [:result :tools])))]
            (is (contains? tool-names "lsp_hover"))
            (is (contains? tool-names "lsp_definition"))
            (is (contains? tool-names "lsp_references"))
            (is (contains? tool-names "lsp_rename"))
            (is (contains? tool-names "lsp_format"))
            (is (contains? tool-names "lsp_document_symbols"))
            (is (contains? tool-names "lsp_code_actions"))
            (is (contains? tool-names "lsp_completion"))
            (is (contains? tool-names "lsp_diagnostics"))
            (is (contains? tool-names "lsp_servers"))))

        ;; 4. Send tools/call for lsp_servers (meta tool, no LSP server needed)
        (let [servers-resp (send-recv writer reader
                             {:jsonrpc "2.0"
                              :id 3
                              :method "tools/call"
                              :params {:name "lsp_servers"
                                       :arguments {}}})]
          (is (= 3 (:id servers-resp)))
          (is (some? (get-in servers-resp [:result :content]))
              "Should have content in result"))

        (finally
          (.destroy process))))))

(deftest mcp-tools-call-unknown-tool-test
  (testing "MCP server returns error for unknown tool"
    (let [proc (ProcessBuilder.
                 (into-array String
                   ["bb" "-cp"
                    "bases/lsp-mcp-bridge/src:components/tool-registry/resources:components/response/src"
                    "-m" "ai.miniforge.lsp-mcp-bridge.main"]))
          _ (doto (.environment proc)
              (.put "MINIFORGE_PROJECT_DIR" (System/getProperty "user.dir")))
          process (.start proc)
          writer (BufferedWriter.
                   (OutputStreamWriter. (.getOutputStream process) "UTF-8"))
          reader (BufferedReader.
                   (InputStreamReader. (.getInputStream process) "UTF-8"))]

      (try
        ;; Initialize first
        (send-recv writer reader
          {:jsonrpc "2.0" :id 1 :method "initialize" :params {:capabilities {}}})

        ;; Call unknown tool
        (let [resp (send-recv writer reader
                     {:jsonrpc "2.0"
                      :id 2
                      :method "tools/call"
                      :params {:name "nonexistent_tool"
                               :arguments {}}})]
          (is (= 2 (:id resp)))
          (is (true? (get-in resp [:result :isError]))
              "Should be marked as error"))

        (finally
          (.destroy process))))))
