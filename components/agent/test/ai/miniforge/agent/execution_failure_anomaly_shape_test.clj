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

(ns ai.miniforge.agent.execution-failure-anomaly-shape-test
  "Lock the canonical anomaly shape produced by `core/execution-failure`
   after the W2 anomaly convergence flip.

   Pre-flip the embedded `:anomaly` was constructed via
   `response/from-exception`, emitting `:anomaly/category :anomalies/fault`
   alongside exception provenance keys (`:anomaly/ex-message`,
   `:anomaly/ex-class`, `:anomaly/ex-data`) at the anomaly map's top
   level.

   Post-flip the anomaly is built via `anomaly/exception-anomaly`:
   `:anomaly/type :fault` (no subtype — the runbook maps the generic
   `:anomalies/fault` 1:1 to a bare type), with provenance carried
   under `:anomaly/data` per the closed canonical schema."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.agent.core :as agent-core]))

(deftest execution-failure-embeds-canonical-fault-anomaly
  (testing "ordinary exceptions yield a canonical :fault anomaly"
    (let [ex   (Exception. "agent blew up")
          resp (agent-core/execution-failure ex)
          a    (:anomaly resp)]
      (is (anomaly/anomaly? a))
      (is (= :fault (:anomaly/type a))
          "the runbook maps generic :anomalies/fault to :fault with no subtype")
      (is (nil? (:anomaly/subtype a)))
      (is (= "agent blew up" (:anomaly/message a)))))

  (testing "exception-info provenance is preserved under :anomaly/data"
    (let [ex   (ex-info "implementer failed" {:role :implementer :iteration 3})
          resp (agent-core/execution-failure ex)
          data (:anomaly/data (:anomaly resp))]
      (is (= "implementer failed" (:anomaly/ex-message data)))
      (is (= "clojure.lang.ExceptionInfo" (:anomaly/ex-class data)))
      (is (= {:role :implementer :iteration 3} (:anomaly/ex-data data))
          "ex-data carried through under :anomaly/data")))

  (testing "the outer execution-failure shape is unchanged"
    (let [ex   (Exception. "boom")
          resp (agent-core/execution-failure ex)]
      (is (= [] (:outputs resp)))
      (is (= [:execution-error] (:decisions resp)))
      (is (= [:task-failed] (:signals resp)))
      (is (map? (:metrics resp))
          "metrics map still attached for downstream telemetry")
      (is (false? (:success resp))
          "response/failure base is preserved by the merge"))))

(deftest execution-failure-no-top-level-domain-keys-on-anomaly
  (testing "exception provenance keys do NOT leak onto the anomaly's top level"
    (let [a (:anomaly (agent-core/execution-failure
                       (ex-info "boom" {:role :reviewer})))]
      (is (nil? (:anomaly/ex-message a))
          "legacy :anomaly/ex-message stays under :anomaly/data")
      (is (nil? (:anomaly/ex-class a)))
      (is (nil? (:anomaly/ex-data a))))))
