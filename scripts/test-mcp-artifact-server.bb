#!/usr/bin/env bb
;; MCP Artifact Server Tests
;; ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
;;
;; Spawns the MCP artifact server as a subprocess and sends JSON-RPC messages
;; through stdin/stdout to test every handler. This exercises the exact code
;; path that hung during dogfooding.
;;
;; Usage:
;;   bb scripts/test-mcp-artifact-server.bb

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

;; --------------------------------------------------------------------------- Helpers

(def test-count (atom 0))
(def pass-count (atom 0))
(def fail-count (atom 0))

(defn report [status test-name msg]
  (swap! test-count inc)
  (if (= status :pass)
    (do (swap! pass-count inc)
        (println (str "  ✓ " test-name)))
    (do (swap! fail-count inc)
        (println (str "  ✗ " test-name " — " msg)))))

(defn assert= [test-name expected actual]
  (if (= expected actual)
    (report :pass test-name nil)
    (report :fail test-name (str "expected " (pr-str expected) " got " (pr-str actual)))))

(defn assert-true [test-name v]
  (if v
    (report :pass test-name nil)
    (report :fail test-name "expected truthy value")))

(defn assert-nil [test-name v]
  (if (nil? v)
    (report :pass test-name nil)
    (report :fail test-name (str "expected nil, got " (pr-str v)))))

(defn assert-match [test-name pattern s]
  (if (and (string? s) (re-find pattern s))
    (report :pass test-name nil)
    (report :fail test-name (str "expected match for " pattern " in " (pr-str s)))))

(defn assert-contains [test-name coll item]
  (if (some #{item} coll)
    (report :pass test-name nil)
    (report :fail test-name (str "expected " (pr-str coll) " to contain " (pr-str item)))))

;; --------------------------------------------------------------------------- Server process management

(defn start-server [artifact-dir]
  (let [proc (p/process {:in :pipe :out :pipe :err :pipe}
                        "bb" "miniforge" "mcp-serve"
                        "--artifact-dir" artifact-dir)]
    ;; Give server a moment to start
    (Thread/sleep 200)
    proc))

(defn send-request! [proc msg]
  (let [writer (java.io.BufferedWriter.
                 (java.io.OutputStreamWriter. (:in proc) "UTF-8"))
        reader (java.io.BufferedReader.
                 (java.io.InputStreamReader. (:out proc) "UTF-8"))]
    (.write writer (json/generate-string msg))
    (.newLine writer)
    (.flush writer)
    ;; Read response (with timeout)
    (let [future (future (.readLine reader))
          line (deref future 5000 ::timeout)]
      (cond
        (= line ::timeout) (throw (ex-info "Timeout reading response" {:msg msg}))
        (nil? line) nil
        :else (json/parse-string line true)))))

(defn send-notification! [proc msg]
  (let [writer (java.io.BufferedWriter.
                 (java.io.OutputStreamWriter. (:in proc) "UTF-8"))]
    (.write writer (json/generate-string msg))
    (.newLine writer)
    (.flush writer)))

(defn close-server! [proc]
  (.close (:in proc))
  (let [^Process process (:proc proc)
        exited? (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)]
    (when-not exited?
      (.destroyForcibly process))))

;; --------------------------------------------------------------------------- JSON-RPC message builders

(defn rpc-request [id method params]
  {:jsonrpc "2.0" :id id :method method :params params})

(defn rpc-notification [method params]
  {:jsonrpc "2.0" :method method :params params})

;; --------------------------------------------------------------------------- Tests

(defn test-initialize [proc]
  (println "\n── initialize")
  (let [resp (send-request! proc (rpc-request 1 "initialize" {}))
        result (:result resp)]
    (assert= "has protocolVersion" "2024-11-05" (:protocolVersion result))
    (assert-true "has capabilities" (map? (:capabilities result)))
    (assert-true "has serverInfo" (map? (:serverInfo result)))
    (assert= "server name" "miniforge-artifact-server" (get-in result [:serverInfo :name]))))

(defn test-tools-list [proc]
  (println "\n── tools/list")
  (let [resp (send-request! proc (rpc-request 2 "tools/list" {}))
        tools (get-in resp [:result :tools])
        tool-names (set (map :name tools))]
    (assert= "returns 7 tools" 7 (count tools))
    (assert-true "has submit_code_artifact" (contains? tool-names "submit_code_artifact"))
    (assert-true "has submit_plan" (contains? tool-names "submit_plan"))
    (assert-true "has submit_test_artifact" (contains? tool-names "submit_test_artifact"))
    (assert-true "has submit_release_artifact" (contains? tool-names "submit_release_artifact"))
    (assert-true "has context_read" (contains? tool-names "context_read"))
    (assert-true "has context_grep" (contains? tool-names "context_grep"))
    (assert-true "has context_glob" (contains? tool-names "context_glob"))
    ;; Check input schemas exist
    (doseq [tool tools]
      (assert-true (str (:name tool) " has inputSchema") (map? (:inputSchema tool))))))

(defn test-submit-code-artifact [proc artifact-dir]
  (println "\n── submit_code_artifact")
  (let [resp (send-request! proc
               (rpc-request 3 "tools/call"
                 {"name" "submit_code_artifact"
                  "arguments" {"files" [{"path" "src/core.clj"
                                         "content" "(ns core)\n(defn greet [] \"hello\")"
                                         "action" "create"}]
                               "summary" "Add greeting function"
                               "language" "clojure"}}))
        result (:result resp)
        content-text (get-in result [:content 0 :text])]
    (assert-true "success response" (some? content-text))
    (assert-match "mentions file count" #"1 file" content-text)
    ;; Check artifact.edn was written
    (let [artifact-file (str artifact-dir "/artifact.edn")
          artifact (edn/read-string (slurp artifact-file))]
      (assert-true "artifact has :code/id" (some? (:code/id artifact)))
      (assert= "artifact summary" "Add greeting function" (:code/summary artifact))
      (assert= "artifact language" "clojure" (:code/language artifact))
      (assert= "file count" 1 (count (:code/files artifact)))
      (assert= "file path" "src/core.clj" (:path (first (:code/files artifact))))
      (assert= "file action" :create (:action (first (:code/files artifact)))))))

(defn test-submit-plan [proc artifact-dir]
  (println "\n── submit_plan")
  (let [resp (send-request! proc
               (rpc-request 4 "tools/call"
                 {"name" "submit_plan"
                  "arguments" {"name" "Auth feature"
                               "tasks" [{"description" "Design API" "type" "design"}
                                        {"description" "Implement handler" "type" "implement"
                                         "dependencies" [0]}]
                               "complexity" "medium"
                               "risks" ["Token expiry edge case"]}}))
        result (:result resp)
        artifact (edn/read-string (slurp (str artifact-dir "/artifact.edn")))]
    (assert-true "success response" (some? (get-in result [:content 0 :text])))
    (assert-true "artifact has :plan/id" (some? (:plan/id artifact)))
    (assert= "plan name" "Auth feature" (:plan/name artifact))
    (assert= "task count" 2 (count (:plan/tasks artifact)))
    (assert= "complexity" :medium (:plan/estimated-complexity artifact))
    (assert= "risks" ["Token expiry edge case"] (:plan/risks artifact))
    ;; Check task UUIDs generated
    (doseq [task (:plan/tasks artifact)]
      (assert-true (str "task has UUID id: " (:task/description task))
                   (re-matches #"[0-9a-f]{8}-.*" (:task/id task))))))

(defn test-submit-test-artifact [proc artifact-dir]
  (println "\n── submit_test_artifact")
  (let [test-content "(ns my-test (:require [clojure.test :refer :all]))
(deftest greeting-test
  (testing \"greets\"
    (is (= \"hello\" (greet)))))"
        resp (send-request! proc
               (rpc-request 5 "tools/call"
                 {"name" "submit_test_artifact"
                  "arguments" {"files" [{"path" "test/my_test.clj"
                                         "content" test-content}]
                               "summary" "Test greeting function"
                               "type" "unit"
                               "framework" "clojure.test"}}))
        result (:result resp)
        artifact (edn/read-string (slurp (str artifact-dir "/artifact.edn")))]
    (assert-true "success response" (some? (get-in result [:content 0 :text])))
    (assert-true "artifact has :test/id" (some? (:test/id artifact)))
    (assert= "test type" :unit (:test/type artifact))
    (assert= "framework" "clojure.test" (:test/framework artifact))
    (assert-true "assertions counted" (pos? (:test/assertions-count artifact)))
    (assert-true "cases counted" (pos? (:test/cases-count artifact)))))

(defn test-submit-release-artifact [proc artifact-dir]
  (println "\n── submit_release_artifact")
  (let [resp (send-request! proc
               (rpc-request 6 "tools/call"
                 {"name" "submit_release_artifact"
                  "arguments" {"branch_name" "feature/auth"
                               "commit_message" "feat: add authentication"
                               "pr_title" "Add user authentication"
                               "pr_description" "## Summary\nAdds JWT auth"
                               "files_summary" "3 files created"}}))
        result (:result resp)
        artifact (edn/read-string (slurp (str artifact-dir "/artifact.edn")))]
    (assert-true "success response" (some? (get-in result [:content 0 :text])))
    (assert-true "artifact has :release/id" (some? (:release/id artifact)))
    (assert= "branch name" "feature/auth" (:release/branch-name artifact))
    (assert= "commit message" "feat: add authentication" (:release/commit-message artifact))
    (assert= "pr title" "Add user authentication" (:release/pr-title artifact))
    (assert= "files summary" "3 files created" (:release/files-summary artifact))))

(defn test-missing-required-params [proc]
  (println "\n── missing required params")
  (let [resp (send-request! proc
               (rpc-request 7 "tools/call"
                 {"name" "submit_code_artifact"
                  "arguments" {"summary" "no files"}}))]
    (assert= "error code -32602" -32602 (get-in resp [:error :code]))))

(defn test-empty-files-array [proc]
  (println "\n── empty files array")
  (let [resp (send-request! proc
               (rpc-request 8 "tools/call"
                 {"name" "submit_code_artifact"
                  "arguments" {"files" [] "summary" "empty"}}))]
    (assert= "error code -32602" -32602 (get-in resp [:error :code]))))

(defn test-unknown-tool [proc]
  (println "\n── unknown tool")
  (let [resp (send-request! proc
               (rpc-request 9 "tools/call"
                 {"name" "nonexistent_tool" "arguments" {}}))]
    (assert= "error code -32601" -32601 (get-in resp [:error :code]))))

(defn test-unknown-method [proc]
  (println "\n── unknown method")
  (let [resp (send-request! proc
               (rpc-request 10 "some/unknown/method" {}))]
    (assert= "error code -32601" -32601 (get-in resp [:error :code]))))

(defn test-notifications-initialized [proc]
  (println "\n── notifications/initialized (no response expected)")
  ;; Send notification — should not produce a response
  (send-notification! proc (rpc-notification "notifications/initialized" {}))
  ;; Give server time to process
  (Thread/sleep 100)
  ;; Verify server is still alive by sending a real request
  (let [resp (send-request! proc (rpc-request 11 "initialize" {}))]
    (assert-true "server still responsive after notification"
                 (some? (get-in resp [:result :protocolVersion])))))

(defn test-large-payload [proc artifact-dir]
  (println "\n── large payload (100KB)")
  (let [large-content (apply str (repeat 100000 "x"))
        resp (send-request! proc
               (rpc-request 12 "tools/call"
                 {"name" "submit_code_artifact"
                  "arguments" {"files" [{"path" "src/large.clj"
                                         "content" large-content
                                         "action" "create"}]
                               "summary" "Large file"}}))
        artifact (edn/read-string (slurp (str artifact-dir "/artifact.edn")))
        stored-content (:content (first (:code/files artifact)))]
    (assert-true "success response" (some? (get-in resp [:result :content 0 :text])))
    (assert= "content not truncated" 100000 (count stored-content))))

(defn test-rapid-sequential-calls [proc artifact-dir]
  (println "\n── rapid sequential calls (5 in a row)")
  (doseq [i (range 5)]
    (send-request! proc
      (rpc-request (+ 13 i) "tools/call"
        {"name" "submit_code_artifact"
         "arguments" {"files" [{"path" (str "src/seq-" i ".clj")
                                "content" (str "(ns seq-" i ")")
                                "action" "create"}]
                      "summary" (str "Sequential call " i)}})))
  ;; Only the last artifact should persist (overwrite behavior)
  (let [artifact (edn/read-string (slurp (str artifact-dir "/artifact.edn")))]
    (assert= "last artifact summary" "Sequential call 4" (:code/summary artifact))
    (assert= "last artifact file path" "src/seq-4.clj" (:path (first (:code/files artifact))))))

;; --------------------------------------------------------------------------- Main

(defn run-tests []
  (println "🧪 MCP Artifact Server Tests")
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

  (let [artifact-dir (str (fs/create-temp-dir {:prefix "mcp-test-"}))]
    (try
      (println (str "\nArtifact dir: " artifact-dir))
      (let [proc (start-server artifact-dir)]
        (try
          (test-initialize proc)
          (test-tools-list proc)
          (test-submit-code-artifact proc artifact-dir)
          (test-submit-plan proc artifact-dir)
          (test-submit-test-artifact proc artifact-dir)
          (test-submit-release-artifact proc artifact-dir)
          (test-missing-required-params proc)
          (test-empty-files-array proc)
          (test-unknown-tool proc)
          (test-unknown-method proc)
          (test-notifications-initialized proc)
          (test-large-payload proc artifact-dir)
          (test-rapid-sequential-calls proc artifact-dir)
          (finally
            (close-server! proc))))
      (finally
        (fs/delete-tree artifact-dir))))

  (println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (println (str "Results: " @pass-count " passed, " @fail-count " failed, " @test-count " total"))

  (when (pos? @fail-count)
    (System/exit 1)))

(run-tests)
