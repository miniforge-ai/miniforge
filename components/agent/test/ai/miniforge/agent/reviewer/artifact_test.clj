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

(ns ai.miniforge.agent.reviewer.artifact-test
  "Tests for `ai.miniforge.agent.reviewer.artifact` — extracted from
   `ai.miniforge.agent.reviewer-test` as part of the PR-G decomposition.

   Covers artifact assembly + validation + repair + the public-API
   accessors used by phase-software-factory."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.reviewer.artifact :as artifact]))

;------------------------------------------------------------------------------ Factories

(defn- canonical-review
  "Minimal valid `ReviewArtifact` map; overrides via keyword args."
  [& {:keys [decision blocking-issues warnings issues strengths
             gates-passed gates-failed gates-total
             out-of-scope-observations]
      :or   {decision        :approved
             blocking-issues []
             warnings        []
             gates-passed    3
             gates-failed    0
             gates-total     3}}]
  (cond-> {:review/id (random-uuid)
           :review/decision decision
           :review/gate-results []
           :review/summary "All checks passed"
           :review/gates-passed gates-passed
           :review/gates-failed gates-failed
           :review/gates-total gates-total
           :review/blocking-issues blocking-issues
           :review/warnings warnings}
    issues       (assoc :review/issues issues)
    strengths    (assoc :review/strengths strengths)
    out-of-scope-observations
    (assoc :review/out-of-scope-observations out-of-scope-observations)))

;------------------------------------------------------------------------------ extract-artifact-and-id

(deftest extract-artifact-and-id-test
  (testing "prefers :task/artifact, then :artifact, then the input itself"
    (let [inner {:artifact/id #uuid "00000000-0000-0000-0000-000000000001"}
          [a id] (artifact/extract-artifact-and-id {:task/artifact inner})]
      (is (= inner a))
      (is (= #uuid "00000000-0000-0000-0000-000000000001" id))))

  (testing ":code/id wins over a synthetic uuid when it's already a uuid"
    (let [inner {:code/id #uuid "00000000-0000-0000-0000-000000000002"}
          [_ id] (artifact/extract-artifact-and-id {:artifact inner})]
      (is (= #uuid "00000000-0000-0000-0000-000000000002" id))))

  (testing "non-uuid :code/id (e.g. a string) is coerced to a fresh uuid"
    ;; :review/artifact-id is schema-typed `uuid?` on ReviewArtifact —
    ;; passing a string straight through would silently fail the malli
    ;; check downstream. Pin the boundary so test scaffolding using
    ;; string ids (or production input from a non-curated artifact)
    ;; can't break the artifact validator.
    (let [[_ id] (artifact/extract-artifact-and-id {:artifact {:code/id "code-1"}})]
      (is (uuid? id) ":code/id \"code-1\" must NOT pass through as a string"))
    (let [[_ id] (artifact/extract-artifact-and-id {:artifact {:artifact/id 42}})]
      (is (uuid? id) "any non-uuid :artifact/id is replaced with a fresh uuid")))

  (testing "synthesizes an id when neither :artifact/id nor :code/id is present"
    (let [[_ id] (artifact/extract-artifact-and-id {})]
      (is (uuid? id)))))

;------------------------------------------------------------------------------ validate-review-artifact + repair-review-artifact

(deftest validate-review-artifact-test
  (testing "valid review passes"
    (is (:valid? (artifact/validate-review-artifact (canonical-review)))))

  (testing "missing required fields fails"
    (is (not (:valid? (artifact/validate-review-artifact
                        {:review/decision :approved})))))

  (testing "mismatched gate counts fail"
    (is (not (:valid? (artifact/validate-review-artifact
                        (canonical-review :gates-passed 2 :gates-total 5))))
        "passed+failed must equal total")))

(deftest repair-review-artifact-test
  (testing "missing :review/id is filled with a fresh uuid"
    (let [repaired (:output (artifact/repair-review-artifact
                              {:review/decision :approved}
                              nil nil))]
      (is (uuid? (:review/id repaired)))))

  (testing "missing :review/decision defaults to :rejected (fail-safe)"
    (let [repaired (:output (artifact/repair-review-artifact {} nil nil))]
      (is (= :rejected (:review/decision repaired)))))

  (testing "gate counts are recalculated from :review/gate-results"
    (let [repaired (:output (artifact/repair-review-artifact
                              {:review/gate-results [{:passed? true}
                                                     {:passed? false}
                                                     {:passed? true}]}
                              nil nil))]
      (is (= 2 (:review/gates-passed repaired)))
      (is (= 1 (:review/gates-failed repaired)))
      (is (= 3 (:review/gates-total repaired))))))

;------------------------------------------------------------------------------ build-review-artifact + build-review-result

(deftest build-review-artifact-test
  (testing "shape includes all canonical keys"
    (let [r (artifact/build-review-artifact
              []   ; gate-feedbacks
              :approved
              []   ; blocking-issues
              []   ; warnings
              (random-uuid)
              {:passed 1 :failed 0 :total 1}
              :summary "ok")]
      (is (= :approved (:review/decision r)))
      (is (string? (:review/summary r)))
      (is (inst? (:review/created-at r)))))

  (testing "issues / strengths / out-of-scope-observations only attached when non-empty"
    (let [r (artifact/build-review-artifact [] :approved [] [] (random-uuid)
                                            {:passed 0 :failed 0 :total 0}
                                            :issues []
                                            :strengths []
                                            :out-of-scope-observations [])]
      (is (not (contains? r :review/issues)))
      (is (not (contains? r :review/strengths)))
      (is (not (contains? r :review/out-of-scope-observations))))
    (let [r (artifact/build-review-artifact [] :approved [] [] (random-uuid)
                                            {:passed 0 :failed 0 :total 0}
                                            :issues [{:severity :nit :description "minor"}]
                                            :out-of-scope-observations [{:severity :warning
                                                                         :description "adjacent file"}])]
      (is (contains? r :review/issues))
      (is (contains? r :review/out-of-scope-observations)))))

(deftest build-review-result-test
  (testing "wraps the review under :status :success with metrics"
    (let [review (canonical-review)
          res    (artifact/build-review-result review
                                               {:passed 3 :failed 0 :total 3}
                                               123 4567
                                               :cost-usd 0.12)]
      (is (= :success (:status res)))
      (is (= review (:output res)))
      (is (= review (:artifact res)))
      (is (= 4567 (-> res :metrics :tokens)))
      (is (= 0.12 (-> res :metrics :cost-usd))))))

;------------------------------------------------------------------------------ Public-API accessors

(deftest review-summary-test
  (let [r (canonical-review :blocking-issues ["b1" "b2"]
                            :warnings ["w1"]
                            :issues [{:severity :nit :description "x"}])
        s (artifact/review-summary r)]
    (is (= :approved (:decision s)))
    (is (= 3 (:gates-passed s)))
    (is (= 0 (:gates-failed s)))
    (is (= 2 (:blocking-issues-count s)))
    (is (= 1 (:warnings-count s)))
    (is (= 1 (:llm-issues-count s)))))

(deftest decision-predicates-test
  (let [approved    {:review/decision :approved}
        rejected    {:review/decision :rejected}
        conditional {:review/decision :conditionally-approved}
        changes     {:review/decision :changes-requested}]
    (testing "approved?"
      (is (artifact/approved? approved))
      (is (not (artifact/approved? rejected)))
      (is (not (artifact/approved? conditional)))
      (is (not (artifact/approved? changes))))
    (testing "rejected?"
      (is (artifact/rejected? rejected))
      (is (not (artifact/rejected? approved))))
    (testing "conditionally-approved?"
      (is (artifact/conditionally-approved? conditional))
      (is (not (artifact/conditionally-approved? approved))))
    (testing "changes-requested?"
      (is (artifact/changes-requested? changes))
      (is (not (artifact/changes-requested? approved))))))

(deftest accessor-defaults-test
  (testing "missing collection keys default to []"
    (is (= [] (artifact/get-blocking-issues {})))
    (is (= [] (artifact/get-warnings {})))
    (is (= [] (artifact/get-recommendations {})))
    (is (= [] (artifact/get-issues {})))
    (is (= [] (artifact/get-strengths {}))))
  (testing "present keys round-trip"
    (let [r {:review/blocking-issues ["b1" "b2"]
             :review/warnings ["w1"]
             :review/recommendations ["fix a"]
             :review/issues [{:severity :nit :description "x"}]
             :review/strengths ["good API"]}]
      (is (= ["b1" "b2"] (artifact/get-blocking-issues r)))
      (is (= ["w1"]      (artifact/get-warnings r)))
      (is (= ["fix a"]   (artifact/get-recommendations r)))
      (is (= [{:severity :nit :description "x"}] (artifact/get-issues r)))
      (is (= ["good API"] (artifact/get-strengths r))))))

;------------------------------------------------------------------------------ rejection-warnings-only?

(deftest rejection-warnings-only-test
  (testing "true when rejection-class + no blockers + warnings present"
    (is (artifact/rejection-warnings-only?
         (canonical-review :decision :rejected
                           :blocking-issues []
                           :warnings ["style nit"])))
    (is (artifact/rejection-warnings-only?
         (canonical-review :decision :changes-requested
                           :blocking-issues []
                           :warnings ["trailing whitespace"]))))

  (testing "false when the decision is approval-class"
    (is (not (artifact/rejection-warnings-only?
              (canonical-review :decision :approved
                                :blocking-issues []
                                :warnings ["advisory nit"]))))
    (is (not (artifact/rejection-warnings-only?
              (canonical-review :decision :conditionally-approved
                                :blocking-issues []
                                :warnings ["advisory nit"])))))

  (testing "false when blocking issues are present alongside warnings"
    (is (not (artifact/rejection-warnings-only?
              (canonical-review :decision :rejected
                                :blocking-issues ["real defect"]
                                :warnings ["advisory nit"])))))

  (testing "false when warnings are absent (empty)"
    (is (not (artifact/rejection-warnings-only?
              (canonical-review :decision :rejected
                                :blocking-issues []
                                :warnings [])))))

  (testing "false when warnings key is missing entirely"
    (let [r (dissoc (canonical-review :decision :rejected :blocking-issues [])
                    :review/warnings)]
      (is (not (artifact/rejection-warnings-only? r)))))

  (testing "false when both blockers and warnings are absent"
    (is (not (artifact/rejection-warnings-only?
              (canonical-review :decision :rejected
                                :blocking-issues []
                                :warnings []))))))
