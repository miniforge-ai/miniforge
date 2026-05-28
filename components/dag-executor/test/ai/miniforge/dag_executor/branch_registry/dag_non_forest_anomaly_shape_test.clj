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

(ns ai.miniforge.dag-executor.branch-registry.dag-non-forest-anomaly-shape-test
  "Lock the canonical anomaly shape produced by `branch-registry`'s
   `resolve-base-branch` (multi-parent path) and `validate-forest` after
   the W2 anomaly convergence flip.

   Pre-flip both helpers emitted a hand-rolled map carrying
   `:anomaly/category :anomalies/dag-non-forest` alongside the
   multi-parent payload at the anomaly's top level
   (`:task/dependencies` / `:multi-parent-tasks`).

   Post-flip the anomaly is built via `anomaly/sub-anomaly` with
   `:anomaly/type :conflict` (per the runbook's custom-subtype map for
   `:anomalies/dag-non-forest`) and the original category preserved
   verbatim as `:anomaly/subtype`. The multi-parent domain payload moves
   under `:anomaly/data` — same key names so telemetry survives once
   consumers migrate their reads."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.branch-registry :as br]))

;------------------------------------------------------------------------------ Layer 0
;; Test factories and named literals.

(def ^:private non-forest-subtype
  "The legacy `:anomalies/*` category keyword that survives verbatim as
   `:anomaly/subtype` post-flip."
  :anomalies/dag-non-forest)

(def ^:private non-forest-type
  "Generic `:anomaly/type` for the dag-non-forest anomaly per runbook."
  :conflict)

(def ^:private diamond-plan
  "A four-task plan whose terminal task `:d` declares two parents `[:b
   :c]` — the smallest plan that fails `validate-forest`. Reused across
   shape assertions so the violations vector is stable."
  [{:task/id :a :task/dependencies []}
   {:task/id :b :task/dependencies [:a]}
   {:task/id :c :task/dependencies [:a]}
   {:task/id :d :task/dependencies [:b :c]}])

(def ^:private multi-parent-deps
  "Task-dep vector that triggers the multi-parent path of
   `resolve-base-branch`."
  [:a :b])

;------------------------------------------------------------------------------ Layer 1
;; Shape assertions.

(deftest resolve-base-branch-emits-canonical-conflict-anomaly
  (testing "multi-parent resolve produces a canonical :conflict anomaly"
    (let [a (br/resolve-base-branch (br/create-registry) multi-parent-deps "main")]
      (is (anomaly/anomaly? a)
          "result satisfies the canonical anomaly schema")
      (is (= non-forest-type (:anomaly/type a))
          "type is :conflict per the runbook's dag-* custom-subtype map")
      (is (= non-forest-subtype (:anomaly/subtype a))
          "legacy category survives verbatim as :anomaly/subtype")))

  (testing "multi-parent payload moves under :anomaly/data"
    (let [a (br/resolve-base-branch (br/create-registry) multi-parent-deps "main")]
      (is (= multi-parent-deps (get-in a [:anomaly/data :task/dependencies]))
          "task deps flattened under :anomaly/data with the same key")
      (is (nil? (:task/dependencies a))
          "no top-level :task/dependencies leakage onto the anomaly"))))

(deftest validate-forest-emits-canonical-conflict-anomaly
  (testing "diamond plan produces a canonical :conflict anomaly"
    (let [a (br/validate-forest diamond-plan)]
      (is (anomaly/anomaly? a))
      (is (= non-forest-type    (:anomaly/type a)))
      (is (= non-forest-subtype (:anomaly/subtype a)))))

  (testing "multi-parent violations move under :anomaly/data"
    (let [a (br/validate-forest diamond-plan)
          violations (get-in a [:anomaly/data :multi-parent-tasks])]
      (is (= 1 (count violations)))
      (is (= :d (:task/id (first violations)))
          "violation entry shape preserved (same keys as pre-flip)")
      (is (nil? (:multi-parent-tasks a))
          "no top-level :multi-parent-tasks leakage onto the anomaly"))))

(deftest resolve-error-predicate-reads-canonical-subtype
  (testing "resolve-error? matches the post-flip :anomaly/subtype shape"
    (let [a (br/resolve-base-branch (br/create-registry) multi-parent-deps "main")]
      (is (true? (br/resolve-error? a)))))
  (testing "legacy :anomaly/category-only maps no longer satisfy resolve-error?"
    ;; Once the producer is flipped, the predicate is single-shape — the
    ;; only emitter of multi-parent anomalies is own-brick so a legacy
    ;; shape arriving here would indicate a stale caller, not a real
    ;; upstream variation.
    (is (false? (br/resolve-error? {:anomaly/category :anomalies/dag-non-forest})))))
