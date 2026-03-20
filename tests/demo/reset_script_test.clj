(ns demo.reset-script-test
  "Unit tests for the reset script's argument parsing, expected-fixtures
   configuration, and cleanup logic. Does not invoke subprocesses."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.edn :as edn]
            [babashka.fs :as fs]))

;; ============================================================================
;; Layer 0 — Constants mirrored from reset script
;; ============================================================================

(def expected-fixtures
  #{"fleet-repos.edn" "fleet-dag.edn" "train-state.edn"
    "blocked-pr.edn" "workflow-spec.edn" "evidence-bundle.edn"
    "policy-gate-results.edn" "demo-ids.edn"})

(def runtime-dirs
  ["tmp/demo" ".demo-state"])

(def log-patterns
  ["demo-*.log" "workflow-*.log"])

(defn reset-parse-args [args]
  {:keep-logs? (some #(= % "--keep-logs") args)})

;; ── Temp dir fixture ──

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (str (fs/create-temp-dir {:prefix "miniforge-reset-test-"}))]
    (binding [*test-dir* dir]
      (try (f)
           (finally
             (when (fs/exists? dir)
               (fs/delete-tree dir)))))))

(use-fixtures :each temp-dir-fixture)

;; ============================================================================
;; Layer 1 — Argument Parsing
;; ============================================================================

(deftest reset-parse-args-no-args-test
  (testing "No arguments yields falsy keep-logs?"
    (is (not (:keep-logs? (reset-parse-args []))))))

(deftest reset-parse-args-keep-logs-test
  (testing "--keep-logs flag detected"
    (is (:keep-logs? (reset-parse-args ["--keep-logs"])))))

(deftest reset-parse-args-keep-logs-among-others-test
  (testing "--keep-logs detected among other flags"
    (is (:keep-logs? (reset-parse-args ["--verbose" "--keep-logs" "--force"])))))

(deftest reset-parse-args-no-keep-logs-test
  (testing "Other flags don't trigger keep-logs"
    (is (not (:keep-logs? (reset-parse-args ["--verbose"]))))))

(deftest reset-parse-args-similar-flag-test
  (testing "Similar but different flag is not detected"
    (is (not (:keep-logs? (reset-parse-args ["--keep-log"]))))))

;; ============================================================================
;; Layer 1 — Expected fixtures configuration
;; ============================================================================

(deftest expected-fixtures-count-test
  (testing "Reset expects exactly 8 fixture files"
    (is (= 8 (count expected-fixtures)))))

(deftest expected-fixtures-all-edn-test
  (testing "All expected fixtures have .edn extension"
    (doseq [f expected-fixtures]
      (is (clojure.string/ends-with? f ".edn")
          (str f " should have .edn extension")))))

(deftest expected-fixtures-match-actual-disk-test
  (testing "Expected fixtures list matches files on disk"
    (let [fixture-dir (str (fs/canonicalize ".") "/tests/fixtures/demo")
          on-disk (set (map #(str (fs/file-name %)) (fs/glob fixture-dir "*.edn")))]
      (is (= expected-fixtures on-disk)))))

;; ============================================================================
;; Layer 2 — Cleanup simulation tests
;; ============================================================================

(deftest clean-runtime-dirs-test
  (testing "Runtime dirs are removed when they exist"
    (doseq [dir-name runtime-dirs]
      (let [dir (str *test-dir* "/" dir-name)]
        (fs/create-dirs dir)
        (spit (str dir "/state.json") "{}")))
    ;; Clean
    (let [removed (atom 0)]
      (doseq [dir-name runtime-dirs]
        (let [dir (str *test-dir* "/" dir-name)]
          (when (fs/exists? dir)
            (fs/delete-tree dir)
            (swap! removed inc))))
      (is (= 2 @removed))
      ;; Verify gone
      (doseq [dir-name runtime-dirs]
        (is (not (fs/exists? (str *test-dir* "/" dir-name))))))))

(deftest clean-runtime-dirs-none-exist-test
  (testing "Cleaning when no runtime dirs exist removes zero dirs"
    (let [removed (atom 0)]
      (doseq [dir-name runtime-dirs]
        (let [dir (str *test-dir* "/" dir-name)]
          (when (fs/exists? dir)
            (fs/delete-tree dir)
            (swap! removed inc))))
      (is (zero? @removed)))))

(deftest clean-log-files-test
  (testing "Log files matching patterns are removed"
    (let [log-dir (str *test-dir* "/logs")]
      (fs/create-dirs log-dir)
      ;; Create matching logs
      (spit (str log-dir "/demo-2026-01-20.log") "log")
      (spit (str log-dir "/workflow-abc123.log") "log")
      ;; Create non-matching log
      (spit (str log-dir "/app.log") "log")
      ;; Clean matching patterns
      (let [removed (atom 0)]
        (doseq [pattern log-patterns]
          (doseq [f (fs/glob log-dir pattern)]
            (fs/delete f)
            (swap! removed inc)))
        (is (= 2 @removed)))
      ;; Non-matching preserved
      (is (fs/exists? (str log-dir "/app.log"))))))

;; ============================================================================
;; Layer 2 — Verification logic simulation
;; ============================================================================

(deftest verify-all-valid-fixtures-test
  (testing "Verification succeeds when all expected fixtures exist and parse"
    ;; Write valid EDN for each expected fixture
    (doseq [f expected-fixtures]
      (spit (str *test-dir* "/" f)
            (pr-str {:fixture f :valid true})))
    ;; Verify
    (let [errors (atom [])]
      (doseq [f (sort expected-fixtures)]
        (let [path (str *test-dir* "/" f)]
          (if (fs/exists? path)
            (try
              (let [data (edn/read-string (slurp path))]
                (when (nil? data)
                  (swap! errors conj f)))
              (catch Exception _
                (swap! errors conj f)))
            (swap! errors conj f))))
      (is (empty? @errors)))))

(deftest verify-detects-missing-fixture-test
  (testing "Verification detects missing fixture files"
    ;; Write all but one
    (let [missing "demo-ids.edn"]
      (doseq [f (disj expected-fixtures missing)]
        (spit (str *test-dir* "/" f)
              (pr-str {:fixture f})))
      (let [errors (atom [])]
        (doseq [f (sort expected-fixtures)]
          (let [path (str *test-dir* "/" f)]
            (when-not (fs/exists? path)
              (swap! errors conj f))))
        (is (= [missing] @errors))))))

(deftest verify-detects-corrupt-fixture-test
  (testing "Verification detects a file with unparseable EDN"
    (doseq [f expected-fixtures]
      (spit (str *test-dir* "/" f)
            (pr-str {:fixture f})))
    ;; Corrupt one file
    (spit (str *test-dir* "/fleet-dag.edn") "{:unclosed [")
    (let [errors (atom [])]
      (doseq [f (sort expected-fixtures)]
        (let [path (str *test-dir* "/" f)]
          (try
            (edn/read-string (slurp path))
            (catch Exception _
              (swap! errors conj f)))))
      (is (= ["fleet-dag.edn"] @errors)))))

(deftest verify-detects-nil-parse-test
  (testing "Verification detects a fixture that parses to nil"
    (doseq [f expected-fixtures]
      (spit (str *test-dir* "/" f)
            (pr-str {:fixture f})))
    ;; Write empty content that parses to nil
    (spit (str *test-dir* "/demo-ids.edn") "nil")
    (let [errors (atom [])]
      (doseq [f (sort expected-fixtures)]
        (let [path (str *test-dir* "/" f)
              data (edn/read-string (slurp path))]
          (when (nil? data)
            (swap! errors conj f))))
      (is (= ["demo-ids.edn"] @errors)))))
