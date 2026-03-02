(ns ai.miniforge.mcp.artifact-server-integration-test
  "Integration tests for the full artifact session → MCP server round-trip.

   These tests exercise the same code path that hung during dogfooding:
   create-session! → write-mcp-config! → spawn MCP server → submit artifact → read-artifact."
  (:require [clojure.test :as test :refer [deftest testing is]]
            [cheshire.core :as json]
            [ai.miniforge.agent.artifact-session :as session])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- send-rpc!
  "Send a JSON-RPC request and read the response.
   Returns parsed JSON map or nil on timeout."
  [^BufferedWriter writer ^BufferedReader reader id method params]
  (let [msg (json/generate-string {:jsonrpc "2.0" :id id :method method :params params})]
    (.write writer msg)
    (.newLine writer)
    (.flush writer)
    (let [f (future (.readLine reader))
          line (deref f 10000 ::timeout)]
      (when-not (or (= line ::timeout) (nil? line))
        (json/parse-string line true)))))

(defn- start-mcp-server
  "Start the MCP artifact server subprocess using the session's MCP config.
   Returns {:proc process :writer writer :reader reader}."
  [session]
  (let [config (json/parse-string (slurp (:mcp-config-path session)) true)
        cmd (get-in config [:mcpServers :artifact :command])
        args (get-in config [:mcpServers :artifact :args])
        proc (-> (ProcessBuilder. (into-array String (cons cmd args)))
                 (.redirectErrorStream false)
                 (.start))
        writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream proc) "UTF-8"))
        reader (BufferedReader. (InputStreamReader. (.getInputStream proc) "UTF-8"))]
    ;; Give server time to start
    (Thread/sleep 300)
    {:proc proc :writer writer :reader reader}))

(defn- stop-mcp-server
  "Close stdin and wait for process exit."
  [{:keys [^Process proc ^BufferedWriter writer]}]
  (.close writer)
  (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)
  (when (.isAlive proc)
    (.destroyForcibly proc)))

;------------------------------------------------------------------------------ Layer 1
;; Full round-trip test

(deftest full-round-trip-test
  (testing "create-session → write-mcp-config → spawn server → submit artifact → read-artifact"
    (let [sess (-> (session/create-session!) session/write-mcp-config!)]
      (try
        (let [{:keys [writer reader] :as server} (start-mcp-server sess)]
          (try
            ;; Initialize
            (let [init-resp (send-rpc! writer reader 1 "initialize" {})]
              (is (= "2024-11-05" (get-in init-resp [:result :protocolVersion]))))

            ;; Submit code artifact
            (let [resp (send-rpc! writer reader 2 "tools/call"
                         {"name" "submit_code_artifact"
                          "arguments" {"files" [{"path" "src/hello.clj"
                                                 "content" "(ns hello)\n(defn world [] :ok)"
                                                 "action" "create"}]
                                       "summary" "Round-trip test artifact"
                                       "language" "clojure"}})]
              (is (some? (get-in resp [:result :content 0 :text]))))

            ;; Read artifact through artifact-session
            (let [artifact (session/read-artifact sess)]
              (is (map? artifact))
              (is (instance? java.util.UUID (:code/id artifact)))
              (is (= "Round-trip test artifact" (:code/summary artifact)))
              (is (= 1 (count (:code/files artifact))))
              (is (= "src/hello.clj" (:path (first (:code/files artifact)))))
              (is (instance? java.util.Date (:code/created-at artifact))))
            (finally
              (stop-mcp-server server))))
        (finally
          (session/cleanup-session! sess))))))

;------------------------------------------------------------------------------ Layer 2
;; Server shutdown test

(deftest server-clean-shutdown-test
  (testing "closing stdin causes process to exit within 5 seconds"
    (let [sess (-> (session/create-session!) session/write-mcp-config!)]
      (try
        (let [{:keys [^Process proc ^BufferedWriter writer]} (start-mcp-server sess)]
          (.close writer)
          (let [exited? (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)]
            (is exited? "Server should exit within 5 seconds of stdin close")
            (when (.isAlive proc)
              (.destroyForcibly proc))))
        (finally
          (session/cleanup-session! sess))))))

;------------------------------------------------------------------------------ Layer 3
;; Concurrent sessions test

(deftest concurrent-sessions-test
  (testing "two independent sessions don't interfere"
    (let [sess-a (-> (session/create-session!) session/write-mcp-config!)
          sess-b (-> (session/create-session!) session/write-mcp-config!)]
      (try
        (let [server-a (start-mcp-server sess-a)
              server-b (start-mcp-server sess-b)]
          (try
            ;; Submit different artifacts to each session
            (send-rpc! (:writer server-a) (:reader server-a) 1 "tools/call"
              {"name" "submit_code_artifact"
               "arguments" {"files" [{"path" "a.clj" "content" "(ns a)" "action" "create"}]
                            "summary" "Session A artifact"}})

            (send-rpc! (:writer server-b) (:reader server-b) 1 "tools/call"
              {"name" "submit_plan"
               "arguments" {"name" "Session B plan"
                            "tasks" [{"description" "task b" "type" "implement"}]}})

            ;; Read each session's artifact independently
            (let [artifact-a (session/read-artifact sess-a)
                  artifact-b (session/read-artifact sess-b)]
              (is (some? (:code/id artifact-a)))
              (is (= "Session A artifact" (:code/summary artifact-a)))
              (is (some? (:plan/id artifact-b)))
              (is (= "Session B plan" (:plan/name artifact-b)))
              ;; Verify isolation — A has code, B has plan
              (is (nil? (:plan/id artifact-a)))
              (is (nil? (:code/id artifact-b))))
            (finally
              (stop-mcp-server server-a)
              (stop-mcp-server server-b))))
        (finally
          (session/cleanup-session! sess-a)
          (session/cleanup-session! sess-b))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.mcp.artifact-server-integration-test)
  :leave-this-here)
