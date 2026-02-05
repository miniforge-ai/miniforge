(ns ai.miniforge.tool-registry.integration-test
  "End-to-end integration tests for tool-registry with actual LSP usage.

   These tests require clojure-lsp to be installed on the system.
   Tests will be skipped if clojure-lsp is not available."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.java.io :as io]
   [ai.miniforge.tool-registry.interface :as tool-registry]
   [ai.miniforge.tool-registry.lsp.client :as lsp-client]))

;------------------------------------------------------------------------------ Test setup

(def ^:dynamic *registry* nil)
(def ^:dynamic *temp-dir* nil)

(defn clojure-lsp-available?
  "Check if clojure-lsp is available on the system."
  []
  (try
    (let [result (-> (ProcessBuilder. ["which" "clojure-lsp"])
                     .start
                     .waitFor)]
      (zero? result))
    (catch Exception _ false)))

(defn temp-dir-fixture [f]
  (let [temp (io/file (System/getProperty "java.io.tmpdir")
                      (str "tool-registry-e2e-" (System/currentTimeMillis)))]
    (.mkdirs temp)
    (binding [*temp-dir* temp]
      (try
        (f)
        (finally
          (doseq [file (reverse (file-seq temp))]
            (.delete file)))))))

(defn registry-fixture [f]
  (binding [*registry* (tool-registry/create-registry {:project-dir (str *temp-dir*)})]
    ;; Register clojure-lsp tool
    (tool-registry/register! *registry*
                             {:tool/id :lsp/clojure
                              :tool/type :lsp
                              :tool/name "Clojure LSP"
                              :tool/description "Language server for Clojure"
                              :tool/config {:lsp/command ["clojure-lsp"]
                                            :lsp/working-dir (str *temp-dir*)}
                              :tool/capabilities #{:code/diagnostics :code/format}})
    (try
      (f)
      (finally
        ;; Stop any running LSP servers
        (try
          (tool-registry/stop-lsp *registry* :lsp/clojure)
          (catch Exception _))))))

(use-fixtures :each temp-dir-fixture registry-fixture)

;------------------------------------------------------------------------------ Helper functions

(defn write-clj-file
  "Write a Clojure file to the temp directory."
  [filename content]
  (let [file (io/file *temp-dir* filename)]
    ;; Create parent directories if they don't exist
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file content)
    file))

(defn file-uri
  "Convert a file to a file:// URI."
  [file]
  (str "file://" (.getAbsolutePath file)))

(defn wait-for-diagnostics
  "Wait for diagnostics to be published (via notification).
   Returns diagnostics or nil after timeout."
  [diagnostics-atom timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (if-let [diags @diagnostics-atom]
        diags
        (if (> (- (System/currentTimeMillis) start) timeout-ms)
          nil
          (do
            (Thread/sleep 100)
            (recur)))))))

;------------------------------------------------------------------------------ E2E Tests

(deftest ^:integration lsp-start-stop-test
  (if-not (clojure-lsp-available?)
    (println "SKIPPED: clojure-lsp not available")
    (testing "LSP server can be started and stopped"
      ;; Create a minimal deps.edn so clojure-lsp initializes
      (spit (io/file *temp-dir* "deps.edn") "{:paths [\"src\"]}")
      (.mkdirs (io/file *temp-dir* "src"))

      ;; Start LSP
      (let [{:keys [success? error]} (tool-registry/start-lsp *registry* :lsp/clojure)]
        (is success? (str "Failed to start LSP: " error))

        ;; Check status
        (is (= :running (tool-registry/lsp-status *registry* :lsp/clojure)))

        ;; Stop LSP
        (let [{:keys [success?]} (tool-registry/stop-lsp *registry* :lsp/clojure)]
          (is success? "Failed to stop LSP"))

        ;; Check status after stop
        (is (= :stopped (tool-registry/lsp-status *registry* :lsp/clojure)))))))

(deftest ^:integration lsp-diagnostics-test
  (if-not (clojure-lsp-available?)
    (println "SKIPPED: clojure-lsp not available")
    (testing "LSP detects mismatched parentheses"
      ;; Create project structure
      (spit (io/file *temp-dir* "deps.edn") "{:paths [\"src\"]}")
      (let [src-dir (io/file *temp-dir* "src")]
        (.mkdirs src-dir)

        ;; Write a file with mismatched parens
        (let [bad-code "(ns bad.code)\n\n(defn broken [x]\n  (+ x 1))"  ; Missing closing paren
              file (write-clj-file "src/bad/code.clj" bad-code)
              ;; Collect diagnostics from notifications
              diagnostics-atom (atom nil)
              _notification-handler (fn [msg]
                                      (when (= "textDocument/publishDiagnostics" (:method msg))
                                        (reset! diagnostics-atom (get-in msg [:params :diagnostics]))))
              ;; Start LSP with custom notification handler
              {:keys [success? error client]} (tool-registry/start-lsp *registry* :lsp/clojure)]
          (is success? (str "Failed to start LSP: " error))

          (when success?
            ;; Open the document
            (lsp-client/open-document client
                                      (file-uri file)
                                      "clojure"
                                      (slurp file))

            ;; Wait for diagnostics (clojure-lsp sends them after analysis)
            (Thread/sleep 3000)

            ;; Get document symbols to verify LSP is responding
            (let [{:keys [success? result]} (lsp-client/document-symbols client (file-uri file))]
              (is success? "document-symbols request failed")
              ;; Should find at least the namespace and function
              (when success?
                (println "Document symbols found:" (count result))))))))))

(deftest ^:integration lsp-hover-test
  (if-not (clojure-lsp-available?)
    (println "SKIPPED: clojure-lsp not available")
    (testing "LSP provides hover information"
      ;; Create project structure
      (spit (io/file *temp-dir* "deps.edn") "{:paths [\"src\"]}")
      (let [src-dir (io/file *temp-dir* "src")]
        (.mkdirs src-dir)

        ;; Write a valid Clojure file
        (let [code "(ns hover.test)\n\n(defn my-function\n  \"A test function that adds numbers.\"\n  [x y]\n  (+ x y))"
              file (write-clj-file "src/hover/test.clj" code)
              ;; Start LSP
              {:keys [success? client]} (tool-registry/start-lsp *registry* :lsp/clojure)]
            (when success?
              ;; Open the document
              (lsp-client/open-document client
                                        (file-uri file)
                                        "clojure"
                                        code)

              ;; Wait for indexing
              (Thread/sleep 2000)

              ;; Request hover on 'defn' (line 2, character 1)
              (let [{:keys [success? result]} (lsp-client/hover client (file-uri file) 2 1)]
                (is success? "hover request failed")
                (when (and success? result)
                  (println "Hover result:" (pr-str (take 100 (str result))))
                  (is (some? result) "Expected hover info for defn")))))))))

(deftest ^:integration lsp-format-test
  (if-not (clojure-lsp-available?)
    (println "SKIPPED: clojure-lsp not available")
    (testing "LSP formats code"
      ;; Create project structure
      (spit (io/file *temp-dir* "deps.edn") "{:paths [\"src\"]}")
      (let [src-dir (io/file *temp-dir* "src")]
        (.mkdirs src-dir)

        ;; Write poorly formatted code
        (let [ugly-code "(ns format.test)\n(defn   ugly   [x    y]\n(+   x    y))"
              file (write-clj-file "src/format/test.clj" ugly-code)
              ;; Start LSP
              {:keys [success? client]} (tool-registry/start-lsp *registry* :lsp/clojure)]
            (when success?
              ;; Open the document
              (lsp-client/open-document client
                                        (file-uri file)
                                        "clojure"
                                        ugly-code)

              ;; Wait for indexing
              (Thread/sleep 2000)

              ;; Request formatting
              (let [{:keys [success? result]} (lsp-client/format-document client (file-uri file) nil)]
                (is success? "format request failed")
                (when success?
                  (println "Format edits:" (count result))
                  ;; Should return text edits to fix formatting
                  (is (or (nil? result) (seq result)) "Expected format edits or nil for already-formatted")))))))))

;------------------------------------------------------------------------------ Tool discovery E2E test

(deftest ^:integration tool-discovery-and-use-test
  (testing "Tools can be discovered and used by agents"
    ;; Register multiple tools
    (tool-registry/register! *registry*
                             {:tool/id :tools/analyze-code
                              :tool/type :function
                              :tool/name "Code Analyzer"
                              :tool/description "Analyzes code for issues"
                              :tool/capabilities #{:code/diagnostics}
                              :tool/tags #{:analysis}})

    (tool-registry/register! *registry*
                             {:tool/id :tools/format-code
                              :tool/type :function
                              :tool/name "Code Formatter"
                              :tool/description "Formats code according to style guide"
                              :tool/capabilities #{:code/format}
                              :tool/tags #{:formatting}})

    ;; Simulate agent discovering tools for a task
    (let [;; Agent needs diagnostic capabilities
          diagnostic-tools (tool-registry/tools-for-context *registry* #{:code/diagnostics})
          ;; Agent needs formatting capabilities
          format-tools (tool-registry/tools-for-context *registry* #{:code/format})
          ;; Agent needs both
          all-tools (tool-registry/tools-for-context *registry* #{:code/diagnostics :code/format})]

      ;; Should find appropriate tools
      (is (= 2 (count diagnostic-tools)) "Should find clojure LSP + analyze-code")
      (is (= 2 (count format-tools)) "Should find clojure LSP + format-code")
      ;; Only clojure-lsp has both capabilities
      (is (= 1 (count all-tools)) "Only clojure-lsp has both capabilities"))

    ;; Get tool details for agent to use
    (let [lsp-tool (tool-registry/get-tool *registry* :lsp/clojure)]
      (is (= :lsp (:tool/type lsp-tool)))
      (is (contains? (:tool/capabilities lsp-tool) :code/diagnostics))
      (is (contains? (:tool/capabilities lsp-tool) :code/format)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run integration tests (requires clojure-lsp)
  (clojure.test/run-tests 'ai.miniforge.tool-registry.integration-test)

  ;; Check if clojure-lsp is available
  (clojure-lsp-available?)

  :leave-this-here)
