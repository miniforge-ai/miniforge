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

(ns ai.miniforge.phase.review-artifact-resolution-test
  "Tests for resolve-implement-artifact in the review phase.

   Validates the three-strategy resolution:
   1. Serialized :artifact key on implement result
   2. Result itself when it contains :code/files (IS the artifact)
   3. Worktree fallback via agent/collect-written-files"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.phase.review]))

;------------------------------------------------------------------------------ Layer 0
;; Factories

(def ^:private resolve-implement-artifact
  "Direct var reference to the private function under test."
  #'ai.miniforge.phase.review/resolve-implement-artifact)

(defn- make-serialized-artifact
  "Factory: implement result with an explicit :artifact key (strategy 1)."
  []
  {:code/files [{:path "src/core.clj"
                 :content "(ns core)"
                 :action :create}]
   :code/summary "serialized artifact"})

(defn- make-implement-result-with-artifact
  "Factory: implement result carrying a serialized :artifact."
  []
  {:artifact (make-serialized-artifact)
   :some-other-key "metadata"})

(defn- make-code-files-result
  "Factory: implement result that IS the artifact (has :code/files, strategy 2)."
  []
  {:code/files [{:path "src/widget.clj"
                 :content "(ns widget)"
                 :action :create}]
   :code/summary "code files result"})

(defn- make-bare-result
  "Factory: implement result with no artifact and no :code/files (strategy 3)."
  []
  {:status :success
   :metrics {:tokens 500}})

(defn- make-ctx
  "Factory: minimal execution context.
   Accepts optional overrides for :execution/worktree-path and :worktree-path."
  ([]
   (make-ctx {}))
  ([overrides]
   (merge {:execution/worktree-path "/tmp/test-worktree"} overrides)))

;------------------------------------------------------------------------------ Layer 1
;; Tests

(deftest resolve-serialized-artifact-test
  (testing "Strategy 1: when implement-result has :artifact key, returns that artifact"
    (let [impl-result (make-implement-result-with-artifact)
          ctx (make-ctx)
          resolved (resolve-implement-artifact impl-result ctx)]
      (is (some? resolved)
          "Should resolve an artifact")
      (is (= (make-serialized-artifact) resolved)
          "Should return the value of the :artifact key")
      (is (= "serialized artifact" (:code/summary resolved))
          "Should preserve the artifact's summary"))))

(deftest resolve-code-files-artifact-test
  (testing "Strategy 2: when implement-result has :code/files, returns the result itself"
    (let [impl-result (make-code-files-result)
          ctx (make-ctx)
          resolved (resolve-implement-artifact impl-result ctx)]
      (is (some? resolved)
          "Should resolve an artifact")
      (is (identical? impl-result resolved)
          "Should return the implement-result itself (not a copy)")
      (is (= "code files result" (:code/summary resolved))
          "Should preserve the original summary"))))

(deftest resolve-strategy-1-takes-precedence-over-strategy-2-test
  (testing "Strategy 1 wins over strategy 2 when both :artifact and :code/files present"
    (let [inner-artifact {:code/files [{:path "inner.clj" :content "inner" :action :create}]
                          :code/summary "inner"}
          impl-result {:artifact inner-artifact
                       :code/files [{:path "outer.clj" :content "outer" :action :create}]
                       :code/summary "outer"}
          ctx (make-ctx)
          resolved (resolve-implement-artifact impl-result ctx)]
      (is (= inner-artifact resolved)
          ":artifact key should take precedence over :code/files on result"))))

(deftest resolve-worktree-fallback-test
  (testing "Strategy 3: when no :artifact and no :code/files, falls back to worktree diff"
    (let [fake-artifact {:code/files [{:path "src/new.clj"
                                       :content "(ns new)"
                                       :action :create}]
                         :code/summary "1 files collected from agent working directory (no MCP submit)"}
          impl-result (make-bare-result)
          ctx (make-ctx)]
      (with-redefs [agent/collect-written-files
                    (fn [_snapshot working-dir]
                      (is (= "/tmp/test-worktree" working-dir)
                          "Should pass worktree-path to collect-written-files")
                      fake-artifact)]
        (let [resolved (resolve-implement-artifact impl-result ctx)]
          (is (some? resolved)
              "Should resolve via worktree fallback")
          (is (= fake-artifact resolved)
              "Should return the artifact from collect-written-files"))))))

(deftest resolve-worktree-fallback-uses-worktree-path-key-test
  (testing "Strategy 3 falls back to :worktree-path when :execution/worktree-path is nil"
    (let [fake-artifact {:code/files [] :code/summary "alt path"}
          impl-result (make-bare-result)
          ctx (make-ctx {:execution/worktree-path nil
                         :worktree-path "/tmp/alt-worktree"})]
      (with-redefs [agent/collect-written-files
                    (fn [_snapshot working-dir]
                      (is (= "/tmp/alt-worktree" working-dir)
                          "Should use :worktree-path as fallback")
                      fake-artifact)]
        (let [resolved (resolve-implement-artifact impl-result ctx)]
          (is (some? resolved)
              "Should resolve via :worktree-path fallback"))))))

(deftest resolve-nil-everything-test
  (testing "Returns nil when no strategy succeeds"
    ;; No worktree path means strategy 3 won't even call collect-written-files
    (let [impl-result (make-bare-result)
          ctx (make-ctx {:execution/worktree-path nil
                         :worktree-path nil})
          resolved (resolve-implement-artifact impl-result ctx)]
      (is (nil? resolved)
          "Should return nil when no artifact source is available"))))

(deftest resolve-nil-implement-result-test
  (testing "Handles nil implement-result gracefully"
    (let [ctx (make-ctx {:execution/worktree-path nil})
          resolved (resolve-implement-artifact nil ctx)]
      (is (nil? resolved)
          "Should return nil when implement-result is nil"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.phase.review-artifact-resolution-test)
  :leave-this-here)
