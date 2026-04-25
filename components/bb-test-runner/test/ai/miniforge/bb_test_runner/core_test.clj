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

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-test-runner.core-test)

  :leave-this-here)
