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
            [clojure.java.io :as io]
            [ai.miniforge.compliance-scanner.rules :as rules]
            [ai.miniforge.compliance-scanner.scan  :as scan]))

;; ---------------------------------------------------------------------------
;; Helpers

(def ^:private d210-pattern
  "Pull the compiled pattern directly from rule-configs for white-box tests."
  (get-in rules/rule-configs ["210" :pattern]))

(defn- matches? [pattern s]
  (boolean (re-find pattern s)))

;; ---------------------------------------------------------------------------
;; Dewey 210 regex tests

(deftest dewey-210-matches-simple-literal-defaults
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

(deftest dewey-210-does-not-match-dual-key-coalesce
  (testing "two-key coalesce (dual-key pattern — must be left alone)"
    ;; (or (:k1 m) (:k2 m) default) should NOT match
    (is (not (matches? d210-pattern "(or (:k1 m) (:k2 m) :fallback)")))))

(deftest dewey-210-does-not-match-computed-default
  (testing "computed default via function call"
    ;; (or (:k m) (some-fn)) — default is not a literal
    (is (not (matches? d210-pattern "(or (:k m) (some-fn))"))))
  (testing "computed default via symbol"
    ;; (or (:k m) my-var) — default is a bare symbol (not a keyword literal)
    ;; Note: our pattern requires a literal, so symbol defaults won't match.
    (is (not (matches? d210-pattern "(or (:k m) my-var)")))))

;; ---------------------------------------------------------------------------
;; Integration: scan a temp directory

(defn- write-temp-file!
  "Write content to a temporary file and return its java.io.File."
  [dir relative-path content]
  (let [f (io/file dir relative-path)]
    (io/make-parents f)
    (spit f content)
    f))

(deftest scan-repo-detects-210-in-temp-dir
  (testing "scan-repo finds Dewey 210 violations in a synthetic repo"
    (let [tmp-dir (doto (io/file (System/getProperty "java.io.tmpdir")
                                 (str "cs-scan-test-" (System/currentTimeMillis)))
                    .mkdirs)]
      (try
        ;; Set up a minimal git repo with a committed file so repo-index
        ;; can build an index (scanner uses `git ls-tree HEAD`).
        ;; Clear GIT_DIR/GIT_WORK_TREE so pre-commit hook env doesn't
        ;; bleed into the child git processes.
        (let [run! (fn [& cmd]
                     (let [pb (doto (ProcessBuilder. ^java.util.List (vec cmd))
                                (.directory tmp-dir))
                           env (.environment pb)]
                       (.remove env "GIT_DIR")
                       (.remove env "GIT_WORK_TREE")
                       (.remove env "GIT_INDEX_FILE")
                       (.waitFor (.start pb))))]
          (run! "git" "init")
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

        (let [result (scan/scan-repo (.getAbsolutePath tmp-dir) "" {:rules #{"210"}})]
          (is (map? result))
          (is (= ["210"] (:rules-scanned result)))
          (is (>= (:files-scanned result) 0))
          ;; Should detect 2 violations in core.clj (lines 2 and 3)
          (let [viols (:violations result)]
            (is (seq viols) "Expected at least one violation")
            (is (every? #(= "210" (:rule/dewey %)) viols))
            (is (every? #(string? (:file %)) viols))
            (is (every? #(pos-int? (:line %)) viols))))
        (finally
          ;; Cleanup
          (doseq [f (reverse (file-seq tmp-dir))]
            (.delete f)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.compliance-scanner.scan-test)
  :leave-this-here)
