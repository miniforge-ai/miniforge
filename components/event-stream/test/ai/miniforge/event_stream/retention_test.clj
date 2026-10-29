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
(ns ai.miniforge.event-stream.retention-test
  "Pure retention policy: which events a bounded history keeps, and when
   it compacts."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.retention :as retention]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} small-policy
  {:max-workflows 2 :max-events-per-workflow 2})

(defn- ^{:stratum 0} event [event-type workflow-id]
  {:event/type event-type :event/id (random-uuid) :workflow/id workflow-id})

(defn- ^{:stratum 0} health-event
  "A dependency-health update as the constructor emits it: no workflow id."
  [dependency-id status]
  {:event/type :dependency/health-updated :event/id (random-uuid) :workflow/id nil
   :dependency/id dependency-id :dependency/status status})

(deftest ^{:stratum 0} init-test
  (testing "no policy means an unbounded history"
    (is (nil? (retention/init nil))))
  (testing "limits a caller leaves out come from the bounded policy"
    (is (= 500 (:max-events-per-workflow (retention/init {:max-workflows 3}))))
    (is (= (:lifecycle-types retention/bounded-policy)
           (:lifecycle-types (retention/init {:max-workflows 3})))))
  (testing "a limit that is not a positive integer is a programmer error"
    (doseq [limits [{:max-workflows 0} {:max-events-per-workflow -1} {:max-workflows 1.5}]]
      (is (thrown? IllegalArgumentException (retention/init limits)) (pr-str limits)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} compact-test
  (let [policy (retention/init small-policy)
        [a b c] [(random-uuid) (random-uuid) (random-uuid)]]
    (testing "workflows rank by their newest event; a dropped one leaves entirely"
      (let [events [(event :workflow/started a) (event :workflow/started b)
                    (event :workflow/started c) (event :agent/chunk a)]]
        (is (= [(events 0) (events 2) (events 3)] (retention/compact events policy)))))
    (testing "lifecycle events do not count against the per-workflow cap"
      (let [started (event :workflow/started a)
            chunks  (vec (repeatedly 3 (partial event :agent/chunk a)))
            done    (event :workflow/completed a)]
        (is (= (into [started] (conj (subvec chunks 1) done))
               (retention/compact (conj (into [started] chunks) done) policy)))))
    (testing "only the newest health event per dependency escapes the cap"
      (let [events [(health-event :anthropic :degraded) (health-event :anthropic :unavailable)
                    (health-event :openai :degraded) (event :agent/chunk nil) (event :agent/chunk nil)]]
        (is (= [(events 1) (events 2) (events 3) (events 4)]
               (retention/compact events policy)))))
    (testing "events without a workflow id share one capped bucket"
      (let [orphans (vec (repeatedly 3 (partial event :pr-train/updated nil)))]
        (is (= (subvec orphans 1) (retention/compact orphans policy)))))))

(deftest ^{:stratum 1} record-test
  (let [wf     (random-uuid)
        state  {:events [] :retention (retention/init small-policy)}
        states (reductions retention/record state (repeatedly 9 (partial event :agent/chunk wf)))]
    (testing "compaction runs once the history passes the mark, then every cap + 1 records"
      (is (= [0 1 2 2 3 4 2 3 4 2] (mapv (comp count :events) states))))
    (testing "an unbounded state only appends"
      (is (= 9 (count (:events (reduce retention/record {:events []}
                                       (repeatedly 9 (partial event :agent/chunk wf))))))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.retention-test))
