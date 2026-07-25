;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.compliance-scanner.scan-test
  "Tests for the scan phase.

   Covers Dewey 210 regex correctness and integration with a temp repo."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn  :as edn]
            [clojure.java.io :as io]
            [ai.miniforge.compliance-scanner.scan :as scan]))

;------------------------------------------------------------------------------ Layer 0

;; ---------------------------------------------------------------------------
;; Helpers
(def ^{:stratum 0} ^:private d210-pattern
  "Canonical Dewey 210 detection pattern from .standards/languages/clojure.mdc."
  (re-pattern "\\(or \\((:[a-zA-Z][a-zA-Z0-9?!_*+<>-]*)\\s+([a-zA-Z][a-zA-Z0-9_-]*)\\)\\s+((?:nil|true|false|\"[^\"]*\"|-?\\d+(?:\\.\\d+)?|\\[\\]|\\{\\}|:[a-zA-Z][a-zA-Z0-9_-]*))\\)"))

(defn- ^{:stratum 0} matches? [pattern s]
  (boolean (re-find pattern s)))

(def ^{:stratum 0} ^:private test-pack
  "Pre-compiled pack fixture for integration tests."
  (edn/read-string (slurp (io/resource "test-fixtures/clojure-210-pack.edn"))))

;; ---------------------------------------------------------------------------
;; Integration: scan a temp directory
(defn- ^{:stratum 0} write-temp-file!
  "Write content to a temporary file and return its java.io.File."
  [dir relative-path content]
  (let [f (io/file dir relative-path)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- ^{:stratum 0} run-in-dir!
  "Run a subprocess in `dir`, clearing inherited Git hook env vars."
  [dir & cmd]
  (let [pb       (doto (ProcessBuilder. ^java.util.List (vec cmd))
                   (.directory dir)
                   (.inheritIO))
        env      (.environment pb)
        git-keys (filterv #(re-find #"^GIT_" %) (keys env))]
    (doseq [k git-keys] (.remove env k))
    (let [exit (.waitFor (.start pb))]
      (when-not (zero? exit)
        (throw (ex-info "Test subprocess failed"
                        {:cmd cmd :dir (.getAbsolutePath dir) :exit exit})))
      exit)))

;; ---------------------------------------------------------------------------
;; safe-git-ref? validation
(deftest ^{:stratum 0} safe-git-ref-allows-well-formed-refs
  (testing "common git ref forms are accepted"
    (is (#'scan/safe-git-ref? "origin/main"))
    (is (#'scan/safe-git-ref? "HEAD~1"))
    (is (#'scan/safe-git-ref? "abc123def456"))
    (is (#'scan/safe-git-ref? "v1.0.0-rc1"))
    (is (#'scan/safe-git-ref? "refs/heads/feature-branch"))))

(deftest ^{:stratum 0} safe-git-ref-rejects-unsafe-values
  (testing "values that could inject git options or shell constructs are rejected"
    (is (not (#'scan/safe-git-ref? "; rm -rf /")))
    (is (not (#'scan/safe-git-ref? "--exec=cmd")))
    (is (not (#'scan/safe-git-ref? "-C /malicious/path")))
    (is (not (#'scan/safe-git-ref? "ref with spaces")))
    (is (not (#'scan/safe-git-ref? "")))
    (is (not (#'scan/safe-git-ref? nil)))))

;------------------------------------------------------------------------------ Layer 1

;; ---------------------------------------------------------------------------
;; Dewey 210 regex tests
(deftest ^{:stratum 1} dewey-210-matches-simple-literal-defaults
  (testing "integer default"
    (is (matches? d210-pattern "(or (:timeout m) 5000)")))
  (testing "nil default"
    (is (matches? d210-pattern "(or (:k m) nil)")))
  (testing "string default"
    (is (matches? d210-pattern "(or (:label m) \"default\")")))
  (testing "boolean default"
    (is (matches? d210-pattern "(or (:flag m) false)")))
  (testing "keyword default"
    (is (matches? d210-pattern "(or (:mode m) :passive)")))
  (testing "empty vector default"
    (is (matches? d210-pattern "(or (:items m) [])")))
  (testing "empty map default"
    (is (matches? d210-pattern "(or (:opts m) {})"))))

(deftest ^{:stratum 1} dewey-210-does-not-match-dual-key-coalesce
  (testing "two-key coalesce (dual-key pattern — must be left alone)"
    ;; (or (:k1 m) (:k2 m) default) should NOT match
    (is (not (matches? d210-pattern "(or (:k1 m) (:k2 m) :fallback)")))))

(deftest ^{:stratum 1} dewey-210-does-not-match-computed-default
  (testing "computed default via function call"
    ;; (or (:k m) (some-fn)) — default is not a literal
    (is (not (matches? d210-pattern "(or (:k m) (some-fn))"))))
  (testing "computed default via symbol"
    ;; (or (:k m) my-var) — default is a bare symbol (not a keyword literal)
    ;; Note: our pattern requires a literal, so symbol defaults won't match.
    (is (not (matches? d210-pattern "(or (:k m) my-var)")))))

(deftest ^{:stratum 1} scan-repo-detects-210-in-temp-dir
  (testing "scan-repo finds Dewey 210 violations in a synthetic repo"
    (let [tmp-dir (doto (io/file (System/getProperty "java.io.tmpdir")
                                 (str "cs-scan-test-" (System/currentTimeMillis)))
                    .mkdirs)]
      (try
        ;; Set up a minimal git repo with a committed file so repo-index
        ;; can build an index (scanner uses `git ls-tree HEAD`).
        ;; Clear ALL GIT_* env vars so pre-commit hook env doesn't
        ;; bleed into the child git processes.
        (let [run! (partial run-in-dir! tmp-dir)]
          (run! "git" "init")
          ;; Disable GPG/SSH signing — 1Password agent blocks in subprocess contexts
          (run! "git" "config" "commit.gpgsign" "false")
          (run! "git" "-c" "user.email=test@test.com" "-c" "user.name=Test"
                "commit" "--allow-empty" "-m" "init")

          (write-temp-file! tmp-dir
            "components/foo/src/ai/miniforge/foo/core.clj"
            (str "(ns ai.miniforge.foo.core)\n"
                 "(defn timeout [m] (or (:timeout m) 5000))\n"
                 "(defn label [m] (or (:label m) \"default\"))\n"))

          ;; Commit the src file so git ls-tree sees it
          (run! "git" "add" ".")
          (run! "git" "-c" "user.email=test@test.com" "-c" "user.name=Test"
                "commit" "-m" "add src"))

        ;; Write a file that should NOT be scanned — not under src/, not committed
        (write-temp-file! tmp-dir
          "components/foo/test/core_test.clj"
          "(ns core-test)\n(or (:k m) nil)\n")

        ;; Pass pre-compiled pack via opts — detection config lives in
        ;; resources/test-fixtures/clojure-210-pack.edn
        (let [result (scan/scan-repo (.getAbsolutePath tmp-dir)
                                     (.getAbsolutePath tmp-dir)
                                     {:rules #{:std/clojure}
                                      :pack  test-pack})]
          (is (map? result))
          (is (= [:std/clojure] (:rules-scanned result)))
          (is (>= (:files-scanned result) 0))
          ;; Should detect 2 violations in core.clj (lines 2 and 3)
          (let [viols (:violations result)]
            (is (seq viols) "Expected at least one violation")
            (is (every? #(= :std/clojure (:rule/id %)) viols))
            (is (every? #(string? (:file %)) viols))
            (is (every? #(pos-int? (:line %)) viols))))
        (finally
          ;; Cleanup
          (doseq [f (reverse (file-seq tmp-dir))]
            (.delete f)))))))

(deftest ^{:stratum 1} scan-repo-excludes-fatal-only-exceptions-as-data-rows
  (testing "top-level review output keeps cleanup-needed rows actionable"
    (let [tmp-dir (doto (io/file (System/getProperty "java.io.tmpdir")
                                 (str "cs-exc-filter-test-" (System/currentTimeMillis)))
                    .mkdirs)]
      (try
        (let [run! (partial run-in-dir! tmp-dir)]
          (run! "git" "init")
          (run! "git" "config" "commit.gpgsign" "false")
          (run! "git" "-c" "user.email=test@test.com" "-c" "user.name=Test"
                "commit" "--allow-empty" "-m" "init")
          (write-temp-file! tmp-dir
            "components/foo/src/ai/miniforge/foo/core.clj"
            (str "(ns ai.miniforge.foo.core)\n"
                 "(defn cleanup-site []\n"
                 "  (throw (ex-info \"GET failed\" {})))\n"
                 "(defn fatal-site []\n"
                 "  (throw (ex-info \"Unknown kind\" {})))\n"
                 "(defn boundary-site!\n"
                 "  \"Boundary wrapper around `boundary-site`. Calls the canonical\n"
                 "   anomaly-returning fn and throws only for legacy callers.\"\n"
                 "  []\n"
                 "  (throw (ex-info \"legacy boundary\" {})))\n"))
          (run! "git" "add" ".")
          (run! "git" "-c" "user.email=test@test.com" "-c" "user.name=Test"
                "commit" "-m" "add exceptions"))
        (let [result (scan/scan-repo (.getAbsolutePath tmp-dir)
                                     (.getAbsolutePath tmp-dir)
                                     {:rules :std/exceptions-as-data})
              viols  (:violations result)]
          (is (= [:std/exceptions-as-data] (:rules-scanned result)))
          (is (= 1 (count viols)))
          (is (= :cleanup-needed (:classification (first viols))))
          (is (re-find #"GET failed" (:current (first viols)))))
        (finally
          (doseq [f (reverse (file-seq tmp-dir))]
            (.delete f)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.compliance-scanner.scan-test)
  :leave-this-here)
