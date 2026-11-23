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
(ns ai.miniforge.workflow.runner-environment-runtime-config-test
  "A governed run's runtime selection config (the CLI's MINIFORGE_RUNTIME
   override) reaches the executor registry's :docker slot."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.runner-environment :as env]
   [ai.miniforge.workflow.runner-preparation :as prep])
  (:import
   (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} registry-config-seen
  "Run governed acquisition with `runtime-config` and return the config
   handed to the executor registry. No executor is selected, so the
   governed acquisition throws after the registry is built."
  [runtime-config]
  (let [seen (atom nil)
        fns  {:create-registry (fn [config] (reset! seen config) :registry)
              :select-exec     (constantly nil)
              :acquire-env!    (constantly nil)
              :executor-type   (constantly nil)
              :result-ok?      (constantly false)
              :result-unwrap   (constantly nil)}]
    (with-redefs [env/dag-executor-fns (constantly fns)]
      (try
        (env/acquire-execution-environment! :wf {:execution-mode :governed
                                                 :runtime-config runtime-config})
        (catch ExceptionInfo _ nil)))
    @seen))

(deftest ^{:stratum 0} with-runtime-config-test
  (testing "merged into the :docker slot, over the slot's own keys"
    (is (= {:docker {:image "img" :runtime-kind :podman}}
           (env/with-runtime-config {:docker {:image "img" :runtime-kind :docker}}
                                    {:runtime-kind :podman}))))
  (testing "no :docker slot (local worktree, kubernetes only): unchanged"
    (is (= {:worktree {}} (env/with-runtime-config {:worktree {}} {:runtime-kind :podman})))
    (is (= {:kubernetes {}} (env/with-runtime-config {:kubernetes {}} {:runtime-kind :podman}))))
  (testing "empty runtime config: unchanged"
    (is (= {:docker {:image "img"}} (env/with-runtime-config {:docker {:image "img"}} {})))))

(deftest ^{:stratum 0} preparation-forwards-runtime-config-test
  (testing "acquire-environment hands the run's :runtime-config to acquisition"
    (let [seen (atom nil)]
      (with-redefs [env/acquire-execution-environment! (fn [_ env-opts] (reset! seen env-opts) nil)]
        (prep/acquire-environment {:workflow/id :wf} {} {:execution-mode :governed
                                                          :runtime-config {:runtime-kind :podman}}))
      (is (= {:runtime-kind :podman} (:runtime-config @seen))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} governed-acquisition-passes-runtime-config-test
  (testing "the registry's :docker slot carries the runtime kind"
    (is (= :podman (get-in (registry-config-seen {:runtime-kind :podman})
                           [:docker :runtime-kind]))))
  (testing "without one the slot has no kind, so the dag-executor auto-probes"
    (is (not (contains? (:docker (registry-config-seen {})) :runtime-kind)))))
