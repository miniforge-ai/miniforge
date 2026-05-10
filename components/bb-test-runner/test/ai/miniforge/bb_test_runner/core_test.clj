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

(ns ai.miniforge.bb-test-runner.core-test
  "Unit tests for bb-test-runner. Only the pure helpers are tested here;
   `run-all` is a Babashka-only entry point that calls `System/exit`
   and is exercised by the bb.edn `test` task rather than clojure.test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [ai.miniforge.bb-test-runner.core :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Factories

(defn- with-test-tree
  "Build a scratch `/test` root containing `paths` (each a relative
   string that will have empty contents), call `f` with the root, then
   tear it down."
  [paths f]
  (let [root (str (fs/create-temp-dir {:prefix "bb-test-runner-"}))]
    (try
      (doseq [p paths]
        (let [full (str root "/" p)]
          (fs/create-dirs (str (fs/parent full)))
          (fs/create-file full)))
      (f root)
      (finally
        (fs/delete-tree root)))))

;------------------------------------------------------------------------------ Layer 1
;; Unit tests

(deftest test-path->ns-symbol-underscores-become-hyphens
  (testing "given a path with underscores → symbol has hyphens"
    (is (= 'ai.miniforge.bb-paths.core-test
           (sut/path->ns-symbol "ai/miniforge/bb_paths/core_test.clj")))))

(deftest test-path->ns-symbol-strips-clj-suffix
  (testing "given a .clj path → suffix is stripped"
    (is (= 'foo.bar-test (sut/path->ns-symbol "foo/bar_test.clj")))))

(deftest test-path->ns-symbol-returns-symbol-type
  (testing "given any path → result is a symbol, not a string"
    (is (symbol? (sut/path->ns-symbol "x/y_test.clj")))))

(deftest test-discover-finds-only-test-files
  (testing "given a tree with test and non-test files → only *_test.clj is returned"
    (with-test-tree
      ["a/b/core_test.clj"
       "a/b/core.clj"
       "a/helpers.clj"]
      (fn [root]
        (let [discovered (sut/discover-test-namespaces [root])
              nses       (set (map :ns discovered))]
          (is (= 1 (count discovered)))
          (is (contains? nses 'a.b.core-test)))))))

(deftest test-discover-walks-all-given-roots
  (testing "given multiple roots → every matching file is discovered"
    (with-test-tree
      ["r1/a_test.clj"
       "r2/b_test.clj"]
      (fn [root]
        (let [discovered (sut/discover-test-namespaces
                          [(str root "/r1") (str root "/r2")])
              nses       (set (map :ns discovered))]
          (is (contains? nses 'a-test))
          (is (contains? nses 'b-test)))))))

(deftest test-discover-empty-for-no-roots
  (testing "given no roots → empty seq"
    (is (empty? (sut/discover-test-namespaces [])))))

(deftest test-classify-coverage-paths-excludes-resources
  (testing "merged classpath paths split into source and test roots"
    (let [classified (sut/classify-coverage-paths
                      ["tasks"
                       "test"
                       "mvp/src"
                       "components/agent/src"
                       "components/agent/resources"
                       "components/agent/test"])]
      (is (= ["test" "components/agent/test"] (:test-paths classified)))
      (is (= ["tasks" "mvp/src" "components/agent/src"] (:source-paths classified))))))

(deftest test-merge-deps-config-includes-test-alias-paths-and-deps
  (testing "deps root and alias classpath merge into one runnable config"
    (let [deps-config {:paths ["tasks"]
                       :deps '{org.clojure/clojure {:mvn/version "1.12.0"}}
                       :aliases {:test {:extra-paths ["test" "mvp/src"]
                                        :extra-deps '{io.github.cognitect-labs/test-runner
                                                      {:git/tag "v0.5.1"}}}}}
          merged (sut/merge-deps-config deps-config {:alias-key :test})]
      (is (= ["tasks" "test" "mvp/src"] (:paths merged)))
      (is (= '{org.clojure/clojure {:mvn/version "1.12.0"}
               io.github.cognitect-labs/test-runner {:git/tag "v0.5.1"}}
             (:deps merged))))))

(deftest test-coverage-args-builds-cloverage-command
  (testing "coverage argv includes cloverage invocation and derived roots"
    (let [deps-config {:paths ["tasks"]
                       :deps '{org.clojure/clojure {:mvn/version "1.12.0"}}
                       :aliases {:test {:extra-paths ["test" "mvp/src" "resources"]}}}
          args (sut/coverage-args deps-config {:alias-key :test
                                               :output-dir "target/custom-coverage"
                                               :fail-threshold 75})
          command-string (str/join " " args)]
      (is (some #{"-Sdeps"} args))
      (is (some #{"cloverage.coverage"} args))
      (is (some #{"--src-ns-path"} args))
      (is (some #{"--test-ns-path"} args))
      (is (str/includes? command-string "target/custom-coverage"))
      (is (str/includes? command-string "--fail-threshold 75"))
      (is (str/includes? command-string "--src-ns-path tasks"))
      (is (str/includes? command-string "--src-ns-path mvp/src"))
      (is (str/includes? command-string "--test-ns-path test"))
      (is (not (str/includes? command-string "--src-ns-path resources"))))))

(deftest test-coverage-install-args-prefetches-cloverage
  (testing "install argv prefetches the cloverage dependency"
    (let [args (sut/coverage-install-args)
          command-string (str/join " " args)]
      (is (= "-P" (first args)))
      (is (str/includes? command-string "cloverage"))
      (is (str/includes? command-string "1.2.4")))))

(deftest test-merge-deps-config-supports-multiple-aliases
  (testing "multiple alias keys compose source and test classpaths"
    (let [deps-config {:paths []
                       :deps '{org.clojure/clojure {:mvn/version "1.12.0"}}
                       :aliases {:dev {:extra-paths ["components/foo/src" "components/foo/resources"]}
                                 :test {:extra-paths ["components/foo/test"]}}}
          merged (sut/merge-deps-config deps-config {:alias-keys [:dev :test]})]
      (is (= ["components/foo/src"
              "components/foo/resources"
              "components/foo/test"]
             (:paths merged))))))

(deftest test-stable-tag-globs-covers-supported-history
  (testing "stable tag globs cover both historical naming schemes"
    (is (= ["stable-*" "stable/*"]
           (sut/stable-tag-globs)))))

(deftest test-stable-tags-present-when-tag-seq-non-empty
  (testing "any stable tag list enables since-stable scope"
    (is (true? (sut/stable-tags-present? ["stable-20260506"])))
    (is (true? (sut/stable-tags-present? ["stable/main-2026-02-27"])))))

(deftest test-stable-tags-absent-when-tag-seq-empty
  (testing "empty stable-tag list forces full-suite fallback"
    (is (false? (sut/stable-tags-present? [])))))

(deftest test-stable-tags-absent-when-input-has-no-recognized-stable-tags
  (testing "non-stable and blank tag entries do not enable since-stable scope"
    (is (false? (sut/stable-tags-present? ["release-2026-05-10" "feature/foo"])))
    (is (false? (sut/stable-tags-present? ["" "   " nil])))))

(deftest test-parse-project-selector-supports-poly-and-env-shapes
  (testing "project selectors accept poly syntax and env-friendly delimiters"
    (is (= ["miniforge" "miniforge-core"]
           (sut/parse-project-selector "project:miniforge:miniforge-core")))
    (is (= ["miniforge" "miniforge-core"]
           (sut/parse-project-selector "miniforge:miniforge-core")))
    (is (= ["miniforge" "miniforge-core"]
           (sut/parse-project-selector "miniforge,miniforge-core")))))

(deftest test-format-project-selector-renders-poly-project-arg
  (testing "project vectors render as a Polylith project selector"
    (is (= "project:miniforge:miniforge-core"
           (sut/format-project-selector ["miniforge" "miniforge-core"])))))

(deftest test-changed-projects-command-matches-polylith-ws-query
  (testing "stable-derived diagnostics query the native changed project set"
    (is (= ["clojure" "-M:poly" "ws" "get:changes:changed-or-affected-projects"
            "skip:dev" "color-mode:none"]
           (sut/changed-projects-command)))))

(deftest test-changed-projects-since-stable-command-inserts-after-change-marker
  (testing "stable-derived diagnostics insert since:stable next to the Polylith change query"
    (is (= ["clojure" "-M:poly" "ws" "get:changes:changed-or-affected-projects"
            "since:stable" "skip:dev" "color-mode:none"]
           (sut/changed-projects-since-stable-command)))))

(deftest test-parse-project-list-output-reads-edn-vectors
  (testing "Polylith ws output parses into project names"
    (is (= ["miniforge" "miniforge-core"]
           (sut/parse-project-list-output "[\"miniforge\" \"miniforge-core\"]")))))

(deftest test-sanitize-git-worktree-env-strips-worktree-vars
  (testing "git worktree vars do not leak into child test processes"
    (is (= {"PATH" "/usr/bin" "FOO" "bar"}
           (sut/sanitize-git-worktree-env
            {"PATH" "/usr/bin"
             "FOO" "bar"
             "GIT_INDEX_FILE" "/tmp/index"
             "GIT_DIR" "/tmp/git"
             "GIT_WORK_TREE" "/tmp/worktree"
             "GIT_COMMON_DIR" "/tmp/common"})))))

(deftest test-heartbeat-seconds-defaults-on-missing-env
  (testing "missing env key falls back to the default heartbeat"
    (is (= 30 (sut/heartbeat-seconds {})))))

(deftest test-heartbeat-seconds-accepts-positive-env-value
  (testing "positive configured heartbeat is honored"
    (is (= 45 (sut/heartbeat-seconds {"MINIFORGE_TEST_HEARTBEAT_SECONDS" "45"})))))

(deftest test-heartbeat-seconds-rejects-invalid-env-value
  (testing "invalid heartbeat values fall back to the default"
    (is (= 30 (sut/heartbeat-seconds {"MINIFORGE_TEST_HEARTBEAT_SECONDS" "abc"})))
    (is (= 30 (sut/heartbeat-seconds {"MINIFORGE_TEST_HEARTBEAT_SECONDS" "0"})))
    (is (= 30 (sut/heartbeat-seconds {"MINIFORGE_TEST_HEARTBEAT_SECONDS" "-5"})))))

(deftest test-parse-diagnostic-args-reads-supported-options
  (testing "diagnostic CLI args parse into a stable-derived plan request"
    (is (= {:mode :expand
            :projects ["miniforge" "miniforge-core"]
            :start-size 2
            :direction :back
            :order :random
            :seed 17}
           (sut/parse-diagnostic-args
            ["mode:expand"
             "project:miniforge:miniforge-core"
             "start-size:2"
             "direction:back"
             "order:random"
             "seed:17"])))))

(deftest test-parse-diagnostic-args-rejects-invalid-numeric-values
  (testing "numeric diagnostic args fail with contextual ex-info"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid stable-derived diagnostic argument"
         (sut/parse-diagnostic-args ["start-size:abc"])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid stable-derived diagnostic argument"
         (sut/parse-diagnostic-args ["seed:not-a-number"])))))

(deftest test-order-projects-supports-declared-and-backward-order
  (testing "diagnostic ordering keeps stable sequences controllable"
    (is (= ["a" "b" "c"]
           (sut/order-projects ["a" "b" "c"] {:order :declared})))
    (is (= ["c" "b" "a"]
           (sut/order-projects ["a" "b" "c"] {:direction :back})))))

(deftest test-order-projects-random-order-is-deterministic-for-a-seed
  (testing "random ordering is stable for the same input seed"
    (is (= ["a" "c" "e" "d" "b"]
           (sut/order-projects
            ["a" "b" "c" "d" "e"]
            {:order :random
             :seed 17})))))

(deftest test-expand-project-groups-doubles-prefix-size
  (testing "additive expansion doubles until the full set is included"
    (is (= [["a"] ["a" "b"] ["a" "b" "c" "d"] ["a" "b" "c" "d" "e"]]
           (sut/expand-project-groups ["a" "b" "c" "d" "e"] 1))))
  (testing "empty project input yields an empty expansion plan"
    (is (= []
           (sut/expand-project-groups [] 1)))))

(deftest test-bisect-project-groups-partitions-breadth-first
  (testing "bisect produces contiguous binary groups"
    (is (= [["a" "b"] ["c" "d"] ["a"] ["b"] ["c"] ["d"]]
           (sut/bisect-project-groups ["a" "b" "c" "d"]))))
  (testing "larger project sets keep a true breadth-first traversal order"
    (is (= [["a" "b" "c" "d"]
            ["e" "f" "g" "h"]
            ["a" "b"]
            ["c" "d"]
            ["e" "f"]
            ["g" "h"]
            ["a"]
            ["b"]
            ["c"]
            ["d"]
            ["e"]
            ["f"]
            ["g"]
            ["h"]]
           (sut/bisect-project-groups ["a" "b" "c" "d" "e" "f" "g" "h"])))))

(deftest test-diagnostic-test-plan-builds-expand-steps
  (testing "diagnostic plans render executable Poly test steps"
    (is (= {:mode :expand
            :summary "Running expand diagnostics across 3 stable-derived projects."
            :projects ["a" "b" "c"]
            :steps [{:label "expand project subset 1/3 (1 projects)"
                     :argv ["clojure" "-M:poly" "test" "project:a"]}
                    {:label "expand project subset 2/3 (2 projects)"
                     :argv ["clojure" "-M:poly" "test" "project:a:b"]}
                    {:label "expand project subset 3/3 (3 projects)"
                     :argv ["clojure" "-M:poly" "test" "project:a:b:c"]}]}
           (sut/diagnostic-test-plan {:mode :expand
                                      :projects ["a" "b" "c"]
                                      :start-size 1}))))
  (testing "missing mode defaults to subset"
    (is (= {:mode :subset
            :summary "Running subset diagnostics across 2 stable-derived projects."
            :projects ["a" "b"]
            :steps [{:label "subset project subset 1/1 (2 projects)"
                     :argv ["clojure" "-M:poly" "test" "project:a:b"]}]}
           (sut/diagnostic-test-plan {:projects ["a" "b"]}))))
  (testing "empty project sets yield an empty step plan"
    (is (= {:mode :expand
            :summary "Running expand diagnostics across 0 stable-derived projects."
            :projects []
            :steps []}
           (sut/diagnostic-test-plan {:mode :expand
                                      :projects []
                                      :start-size 1})))))


;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-test-runner.core-test)

  :leave-this-here)
