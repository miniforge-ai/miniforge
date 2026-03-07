(ns ai.miniforge.agent.artifact-session-error-test
  "Regression test for Run 7: artifact file not found logs ERROR not WARN.
   The previous WARN level was misleading — a missing artifact is fatal
   (the implement phase fails with 0ms duration)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.agent.artifact-session :as session]))

(deftest read-artifact-missing-file-logs-error-test
  (testing "missing artifact file prints ERROR (not WARN) to stderr"
    (let [s (session/create-session!)
          stderr-output (java.io.StringWriter.)]
      (try
        ;; Capture stderr output
        (binding [*err* stderr-output]
          (session/read-artifact s))
        (let [output (str stderr-output)]
          (is (re-find #"ERROR" output)
              "Should log ERROR level, not WARN")
          (is (re-find #"artifact file not found" output)
              "Should mention artifact file not found")
          (is (re-find #"MCP tool" output)
              "Should hint at root cause (MCP tool not called)"))
        (finally
          (session/cleanup-session! s))))))

(deftest read-artifact-missing-returns-nil-test
  (testing "returns nil when artifact file doesn't exist"
    (let [s (session/create-session!)]
      (try
        (is (nil? (session/read-artifact s)))
        (finally
          (session/cleanup-session! s))))))
