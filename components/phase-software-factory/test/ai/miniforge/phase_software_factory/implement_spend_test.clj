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
(ns ai.miniforge.phase-software-factory.implement-spend-test
  "The implement phase reports the implementer turn's tokens and cost when
   the curator's result stands in for the implementer's: files collected
   from the worktree (success), or no files at all (terminal failure)."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [ai.miniforge.phase-software-factory.implement]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "hello.py" :content "print('hello')\n" :action :create}]
   :code/language "python"
   :code/summary "Add hello.py"})

(defn ^{:stratum 0} curator-no-files
  "What `curate :implement` returns for an empty diff (`empty-diff-error`)."
  [_input]
  (response/error "No files written to environment"
                  {:data {:code :curator/no-files-written}}))

(defn ^{:stratum 0} run-implement
  "Run the implement phase's enter and leave with `impl-result` as the
   implementer's answer and `curate` as the curator."
  [impl-result curate]
  (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                agent/invoke (fn [_ _ _] impl-result)
                agent/curate-implement-output curate]
    (let [interceptor (phase/get-phase-interceptor {:phase :implement})
          ctx {:execution/id (random-uuid)
               :execution/environment-id (random-uuid)
               :execution/worktree-path "/tmp/test-worktree"
               :execution/input {:description "Add hello.py" :title "hello"}
               :execution/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}
               :phase-config {:phase :implement}}]
      ((:leave interceptor) ((:enter interceptor) ctx)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} curator-collected-files
  "What `curate :implement` returns when it finds the files the session
   wrote: a response/success whose metrics are the curator's file counts
   (`artifact-metrics`). The response builder adds `:tokens 0`."
  [_input]
  (response/success code-artifact
                    {:metrics {:files-total 1 :files-created 1 :files-modified 0
                               :files-deleted 0 :scope-deviations 0
                               :tests-added? false :curator-source :deterministic}}))

(deftest ^{:stratum 1} empty-diff-failure-keeps-the-implementer-spend-test
  (testing "a turn that wrote no files still reports what it consumed"
    (let [impl-result (response/error "parse failed"
                                      {:tokens 900 :metrics {:cost-usd 0.0135}})
          out (run-implement impl-result curator-no-files)]
      (is (= :failed (get-in out [:phase :status])))
      (is (= :implement/empty-diff (get-in out [:phase :verdict])))
      (is (= 900 (get-in out [:phase :metrics :tokens])))
      (is (= 0.0135 (get-in out [:phase :metrics :cost-usd])))
      (is (= 900 (get-in out [:execution/metrics :tokens]))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} curated-files-keep-the-implementer-spend-test
  (testing "files the curator collected do not zero the implementer's tokens"
    (let [impl-result (response/success code-artifact
                                        {:tokens 1500
                                         :metrics {:tokens 1500 :cost-usd 0.0225}})
          out (run-implement impl-result curator-collected-files)]
      (is (= :completed (get-in out [:phase :status])))
      (is (= 1500 (get-in out [:phase :metrics :tokens])))
      (is (= 0.0225 (get-in out [:phase :metrics :cost-usd])))
      (is (= 1 (get-in out [:phase :metrics :files-total]))
          "the curator's file counts are still merged")
      (is (= 1500 (get-in out [:execution/metrics :tokens]))))))

;------------------------------------------------------------------------------ Fixtures
(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (try
      (binding [loader/phase-loader-config-resource "config/phase/test-support-namespaces.edn"]
        (f))
      (finally
        (phase/reset-phase-loader!)))))
