;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.canonical-validation-test
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.evidence-bundle.collectors :as collectors]
            [ai.miniforge.evidence-bundle.phases :as phases]
            [ai.miniforge.evidence-bundle.schema.compliance :as compliance]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} base-bundle []
  {:evidence-bundle/id (random-uuid)
   :evidence-bundle/workflow-id (random-uuid)
   :evidence-bundle/created-at #inst "2026-09-30T00:00:00Z"
   :evidence-bundle/version "1.0.0"
   :evidence/intent {:intent/type :update
                     :intent/description "Adjust capacity."
                     :intent/business-reason "Meet latency objectives."
                     :intent/constraints []
                     :intent/declared-at #inst "2026-09-30T00:00:00Z"}
   :evidence/policy-checks []
   :evidence/outcome {:outcome/success true}})

(defn- ^{:stratum 0} policy-check []
  {:policy-check/pack-id "opsv"
   :policy-check/pack-version "1.0.0"
   :policy-check/phase :verify
   :policy-check/checked-at #inst "2026-09-30T00:00:00Z"
   :policy-check/violations []
   :policy-check/passed? true
   :policy-check/duration-ms 0
   :policy-check/envelope nil})

(deftest ^{:stratum 0} schema-validation-reports-malformed-records-without-throwing-test
  (doseq [value [nil 42 :invalid [] "record"]]
    (is (false? (:valid? (validation/validate-schema {:field string?} value))))
    (is (false? (compliance/valid-access-log-entry? value))))
  (is (false? (:valid? (validation/validate-schema {:field seq} {:field 42}))))
  (is (true? (:valid? (validation/validate-schema {:field nil?} {:field nil}))))
  (is (false? (:valid? (validation/validate-schema {:field nil?} {})))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} canonical-validation-checks-required-types-and-domain-values-test
  (let [bundle (base-bundle)]
    (is (:valid? (evidence/validate-canonical-bundle bundle)))
    (doseq [key (keys bundle)]
      (is (false? (:valid? (evidence/validate-canonical-bundle (dissoc bundle key))))))
    (doseq [[path value] [[[:evidence-bundle/id] "not-a-uuid"]
                          [[:evidence-bundle/created-at] "not-an-instant"]
                          [[:evidence-bundle/version] 1]
                          [[:evidence/intent :intent/type] :unknown]
                          [[:evidence/intent :intent/constraints] 42]
                          [[:evidence/intent :intent/constraints] nil]
                          [[:evidence/intent :intent/constraints] {}]
                          [[:evidence/outcome :outcome/success] "true"]
                          [[:evidence/policy-checks] [{}]]
                          [[:compliance/created-at] "yesterday"]
                          [[:compliance/pii-handling] :unknown]
                          [[:evidence/opsv] {}]]]
      (is (false? (:valid? (evidence/validate-canonical-bundle (assoc-in bundle path value))))))))

(deftest ^{:stratum 1} declared-hash-is-verified-without-claiming-authority-test
  (let [bundle (assoc (base-bundle)
                       :compliance/created-at #inst "2026-09-30T00:00:00Z"
                       :evidence/sealed-at #inst "2026-09-30T00:00:00Z")
        sealed (assoc bundle :evidence/content-hash (evidence/content-hash bundle))]
    (is (:valid? (evidence/validate-canonical-bundle sealed)))
    (is (:valid? (evidence/validate-canonical-bundle
                  (assoc sealed :evidence/signature "not-an-authenticity-check"))))
    (doseq [altered [(assoc-in sealed [:evidence/outcome :outcome/success] false)
                     (assoc sealed :evidence/content-hash nil)
                     (assoc sealed :evidence/content-hash "wrong")
                     (assoc sealed :evidence/signature 42)
                     (assoc sealed :evidence/signature nil)
                     (dissoc sealed :evidence/sealed-at)
                     (dissoc sealed :compliance/created-at)
                     (dissoc sealed :evidence/content-hash)]]
      (is (false? (:valid? (evidence/validate-canonical-bundle altered)))))))

(deftest ^{:stratum 1} present-structured-fields-cannot-hide-empty-records-test
  (doseq [field [:evidence/semantic-validation :evidence/plan :evidence/design
                :evidence/implement :evidence/verify :evidence/review
                :evidence/release :evidence/observe]]
    (is (false? (:valid? (evidence/validate-canonical-bundle (assoc (base-bundle) field {}))))))
  (doseq [field [:evidence/tool-invocations :evidence/pack-promotions
                :evidence/supervision-decisions :evidence/control-actions :evidence/rules-applied]]
    (is (:valid? (evidence/validate-canonical-bundle (assoc (base-bundle) field []))))
    (is (false? (:valid? (evidence/validate-canonical-bundle (assoc (base-bundle) field [{}])))))))

(deftest ^{:stratum 1} structured-records-accept-complete-domain-values-test
  (let [at #inst "2026-09-30T00:00:00Z"
        bundle (base-bundle)
        semantic {:semantic-validation/declared-intent :update
                  :semantic-validation/actual-behavior :update
                  :semantic-validation/resource-creates 0
                  :semantic-validation/resource-updates 1
                  :semantic-validation/resource-destroys 0
                  :semantic-validation/passed? true
                  :semantic-validation/violations []
                  :semantic-validation/checked-at at}
        tool {:tool/id :read :tool/invoked-at at :tool/duration-ms 0 :tool/args {}}
        output {:status :success :environment-id "test" :summary "Done." :metrics {}}
        phase {:phase/name :implement :phase/agent :test :phase/agent-instance-id (random-uuid)
               :phase/started-at at :phase/completed-at at :phase/duration-ms 1
               :phase/output output :phase/artifacts []}]
    (is (:valid? (evidence/validate-canonical-bundle
                  (assoc bundle :evidence/semantic-validation semantic
                                :evidence/tool-invocations [tool] :evidence/implement phase))))
    (doseq [invalid [{} 42 nil]]
      (is (false? (:valid? (evidence/validate-canonical-bundle
                           (assoc bundle :evidence/semantic-validation
                                  (assoc semantic :semantic-validation/violations [invalid])))))))
    (doseq [range [{} {:start-seq "0" :end-seq 1} {:start-seq 0} nil]]
      (is (false? (:valid? (evidence/validate-canonical-bundle
                           (assoc bundle :evidence/implement
                                  (assoc phase :phase/event-stream-range range)))))))
    (is (false? (:valid? (evidence/validate-canonical-bundle
                         (assoc-in (assoc bundle :evidence/implement phase)
                                   [:evidence/implement :phase/output :summary] 42)))))))

(deftest ^{:stratum 1} collector-phase-projections-pass-canonical-validation-test
  (doseq [phase-name [:plan :design :implement :verify :review :release :observe]
          input [{:output {:summary "Done." :metrics {}}}
                 {:environment-id "test" :summary "Done." :metrics {}}]]
    (let [phase (phases/build-phase-evidence phase-name :test (assoc input :duration-ms 0))
          key (keyword "evidence" (name phase-name))
          bundle (assoc (base-bundle) key phase)]
      (is (zero? (:phase/inner-loop-iterations phase)))
      (is (:valid? (evidence/validate-canonical-bundle bundle)))
      (is (false? (:valid? (evidence/validate-canonical-bundle
                           (assoc-in bundle [key :phase/output :metrics] 42)))))
      (is (false? (:valid? (evidence/validate-canonical-bundle
                           (assoc-in bundle [key :phase/artifacts] nil))))))))

(deftest ^{:stratum 1} field-presence-is-distinct-from-nullability-test
  (let [check (policy-check)
        bundle (assoc (base-bundle) :evidence/policy-checks [check])]
    (is (:valid? (evidence/validate-canonical-bundle bundle)))
    (is (false? (:valid? (evidence/validate-canonical-bundle
                         (update-in bundle [:evidence/policy-checks 0]
                                    dissoc :policy-check/envelope)))))
    (is (false? (:valid? (evidence/validate-canonical-bundle
                         (assoc-in bundle [:evidence/intent :intent/author] nil)))))))

(deftest ^{:stratum 1} nested-domain-values-use-their-canonical-schemas-test
  (let [bundle (assoc (base-bundle) :evidence/policy-checks [(policy-check)])
        constraint {:constraint/type :latency
                    :constraint/description "Keep latency below the objective."}
        violation {:violation/rule-id "latency"
                   :violation/severity :high
                   :violation/message "Latency exceeded the objective."}]
    (doseq [[path valid] [[[:evidence/intent :intent/constraints] constraint]
                         [[:evidence/policy-checks 0 :policy-check/violations] violation]]]
      (is (:valid? (evidence/validate-canonical-bundle (assoc-in bundle path [valid]))))
      (doseq [invalid [{} 42 nil]]
        (is (false? (:valid? (evidence/validate-canonical-bundle
                             (assoc-in bundle path [invalid])))))))))

(deftest ^{:stratum 1} nonportable-or-nonmap-input-is-rejected-before-hashing-test
  (doseq [value [nil [] 42 (assoc (base-bundle) :extension (Object.))
                 (assoc (base-bundle) :extension (iterate inc 0))]]
    (is (false? (:valid? (evidence/validate-canonical-bundle value))))))

(deftest ^{:stratum 1} collected-supervision-confidence-accepts-producer-numbers-test
  (doseq [confidence [0 1 0.95 0.95M]]
    (let [event {:tool/name "read" :supervision/decision "allow"
                 :event/timestamp #inst "2026-09-30T00:00:00Z"
                 :supervision/confidence confidence}]
      (with-redefs [collectors/collect-event-stream-events (constantly [event])]
        (let [bundle (base-bundle)
              records (collectors/collect-supervision-decisions :test (:evidence-bundle/workflow-id bundle))]
          (is (= confidence (:supervision/confidence (first records))))
          (is (:valid? (evidence/validate-canonical-bundle
                        (assoc bundle :evidence/supervision-decisions records)))))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.canonical-validation-test))
