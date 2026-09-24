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
(ns ai.miniforge.workflow-resume.rewind-test
  (:require
   [ai.miniforge.workflow-resume.interface :as wr]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} checkpointed
  {:completed-phases [:plan :implement :verify]
   :phase-results {:plan {:status :completed} :implement {:status :completed}
                   :verify {:status :completed}}
   :machine-snapshot {:execution/id (random-uuid)}})

(def ^{:stratum 0} events-only
  {:completed-phases [:plan :implement]
   :phase-results {:plan {:outcome :success} :implement {:outcome :success}}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} rewind-kept-phases-test
  (testing "a rewind keeps the completed phases before the rewind point"
    (is (= [:plan] (wr/rewind-kept-phases checkpointed :implement)))
    (is (= [] (wr/rewind-kept-phases checkpointed :plan)))))

(deftest ^{:stratum 1} checkpointed-phase-results-test
  (testing "results read from a checkpoint are the phases' output"
    (is (= (:phase-results checkpointed) (wr/checkpointed-phase-results checkpointed))))
  (testing "results rebuilt from events are telemetry, not output"
    (is (nil? (wr/checkpointed-phase-results events-only)))))

(deftest ^{:stratum 1} rewind-refusal-test
  (testing "a rewind whose kept phases all have checkpointed results can run"
    (is (nil? (wr/rewind-refusal checkpointed :verify))))
  (testing "a rewind keeping phases with no checkpointed result is refused, naming them"
    (is (= {:resume/reason :phase-results-not-checkpointed :resume/phases [:plan]}
           (wr/rewind-refusal events-only :implement)))
    (is (= {:resume/reason :phase-results-not-checkpointed :resume/phases [:implement]}
           (wr/rewind-refusal (update checkpointed :phase-results dissoc :implement) :verify))))
  (testing "a rewind to the first phase keeps nothing, so needs nothing"
    (is (nil? (wr/rewind-refusal events-only :plan)))))
