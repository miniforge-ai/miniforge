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

(ns ai.miniforge.dag-executor.protocols.impl.runtime.selector-test
  "Tests for the runtime selector — N11-delta §3 selection algorithm."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.selector :as selector]
   [ai.miniforge.dag-executor.result :as result]))

;; Per-kind probe stub. Tests pass a map of kind -> probe-result and stub
;; descriptor/runtime-info to look up by descriptor kind.
(defn- stub-runtime-info
  [kind->result]
  (fn [d]
    (get kind->result (descriptor/kind d)
         {:available? false :reason "not stubbed"})))

(def ^:private all-available
  {:docker {:available? true :runtime-version "28.0.4"}
   :podman {:available? true :runtime-version "5.4.0"}})

(def ^:private only-docker
  {:docker {:available? true :runtime-version "28.0.4"}
   :podman {:available? false :reason "podman not installed"}})

(def ^:private only-podman
  {:docker {:available? false :reason "Cannot connect to docker daemon"}
   :podman {:available? true :runtime-version "5.4.0"}})

(def ^:private nothing-available
  {:docker {:available? false :reason "Cannot connect to docker daemon"}
   :podman {:available? false :reason "podman not installed"}})

;; ============================================================================
;; Auto-probe — N11-delta §3 step 2
;; ============================================================================

(deftest auto-probe-prefers-podman-when-both-available-test
  (testing "auto-probe selects Podman when both Podman and Docker are available"
    (with-redefs [descriptor/runtime-info (stub-runtime-info all-available)]
      (let [result (selector/select-runtime {})]
        (is (result/ok? result))
        (is (= :podman (-> result :data :kind)))
        (is (= :auto-probe (-> result :data :selection)))
        (is (= "5.4.0" (-> result :data :runtime-version)))))))

(deftest auto-probe-falls-through-to-docker-test
  (testing "auto-probe selects Docker when Podman is not available"
    (with-redefs [descriptor/runtime-info (stub-runtime-info only-docker)]
      (let [result (selector/select-runtime {})]
        (is (result/ok? result))
        (is (= :docker (-> result :data :kind)))
        (is (= "28.0.4" (-> result :data :runtime-version)))))))

(deftest auto-probe-selects-podman-when-only-podman-test
  (testing "auto-probe selects Podman when only Podman is available"
    (with-redefs [descriptor/runtime-info (stub-runtime-info only-podman)]
      (let [result (selector/select-runtime {})]
        (is (result/ok? result))
        (is (= :podman (-> result :data :kind)))))))

(deftest auto-probe-reports-every-kind-it-tried-test
  (testing "auto-probe :probed list contains a summary for every supported kind"
    (with-redefs [descriptor/runtime-info (stub-runtime-info all-available)]
      (let [probed (-> (selector/select-runtime {}) :data :probed)
            kinds  (set (map :kind probed))]
        (is (contains? kinds :docker))
        (is (contains? kinds :podman))
        ;; Each summary names its availability so the doctor can render
        ;; "available: x, y" / "unavailable: z" without re-probing.
        (is (every? #(contains? % :available?) probed))))))

(deftest auto-probe-fails-loud-when-nothing-available-test
  (testing "auto-probe returns :runtime/none-available when no runtime probes"
    (with-redefs [descriptor/runtime-info (stub-runtime-info nothing-available)]
      (let [result (selector/select-runtime {})]
        (is (result/err? result))
        (is (= :runtime/none-available (-> result :error :code)))
        (is (seq (-> result :error :data :probed)))))))

;; ============================================================================
;; Explicit configuration — N11-delta §3 step 1
;; ============================================================================

(deftest explicit-kind-overrides-probe-order-test
  (testing "explicit :runtime-kind :docker selects Docker even when Podman is available"
    (with-redefs [descriptor/runtime-info (stub-runtime-info all-available)]
      (let [result (selector/select-runtime {:runtime-kind :docker})]
        (is (result/ok? result))
        (is (= :docker (-> result :data :kind)))
        (is (= :explicit (-> result :data :selection)))))))

(deftest explicit-kind-fails-loud-when-unavailable-test
  (testing "explicit :runtime-kind :docker fails loud when Docker is unavailable"
    (with-redefs [descriptor/runtime-info (stub-runtime-info only-podman)]
      (let [result (selector/select-runtime {:runtime-kind :docker})]
        (is (result/err? result))
        (is (= :runtime/explicit-unavailable (-> result :error :code)))
        (is (= :docker (-> result :error :data :kind)))
        ;; The whole point of explicit config is honoring the user's
        ;; choice; falling through to Podman would defeat that.
        (is (not (contains? (:error result) :descriptor)))))))

(deftest explicit-kind-rejects-unsupported-kind-test
  (testing "explicit :runtime-kind :nerdctl returns :runtime/explicit-unsupported"
    ;; Nothing to stub — the registry rejects :nerdctl before any probe runs.
    (let [result (selector/select-runtime {:runtime-kind :nerdctl})]
      (is (result/err? result))
      (is (= :runtime/explicit-unsupported (-> result :error :code)))
      (is (contains? (-> result :error :data :supported) :docker))
      (is (contains? (-> result :error :data :supported) :podman)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.dag-executor.protocols.impl.runtime.selector-test)
  :leave-this-here)
