;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.canonical-validation-test
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.evidence-bundle.collectors :as collectors]
            [ai.miniforge.evidence-bundle.collector :as collector]
            [ai.miniforge.evidence-bundle.collector-projection :as projection]
            [ai.miniforge.evidence-bundle.producer-roundtrips :as roundtrips]
            [ai.miniforge.evidence-bundle.control-fixtures :as control-fixtures]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.phases :as phases]
            [ai.miniforge.evidence-bundle.schema.compliance :as compliance]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} valid? [bundle]
  (:valid? (evidence/validate-canonical-bundle bundle)))

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

(defn- ^{:stratum 0} publication-fields [bundle]
  (-> bundle
      (assoc :evidence/event-links [{:event-links/scope-type :workflow
                                     :event-links/scope-id (:evidence-bundle/workflow-id bundle)
                                     :event-links/from-sequence 0 :event-links/to-sequence 2
                                     :event-links/event-count 3}])
      (assoc-in [:evidence/outcome :outcome/tier] :standard)
      (assoc-in [:evidence/outcome :outcome/sli-measurements]
                [{:sli/name :SLI-1 :sli/value 1.0}])))

(deftest ^{:stratum 0} schema-validation-reports-malformed-records-without-throwing-test
  (doseq [value [nil 42 :invalid [] "record"]]
    (is (false? (:valid? (validation/validate-schema {:field string?} value))))
    (is (false? (compliance/valid-access-log-entry? value))))
  (is (false? (:valid? (validation/validate-schema {:field seq} {:field 42}))))
  (is (true? (:valid? (validation/validate-schema {:field nil?} {:field nil}))))
  (is (false? (:valid? (validation/validate-schema {:field nil?} {})))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} collected-optional-producer-fields-pass-canonical-validation
  (doseq [bundle (roundtrips/collected-bundles)]
    (is (valid? bundle))))

(deftest ^{:stratum 1} collected-semantic-and-policy-evidence-use-canonical-fields-test
  (let [workflow-id (random-uuid)
        material {:artifact/type :terraform-plan :artifact/content "resource will be created"
                  :artifact/provenance {:provenance/workflow-id workflow-id :provenance/phase :implement}}
        violation {:rule-id "example" :severity :high :message "Policy failed."}
        canonical {:violation/rule-id :policy/example :violation/severity :major
                   :violation/message (:message violation)}
        wrapped {:rule {:rule/id "example" :rule/severity :high}
                 :violation {:message "Policy failed."}}
        state {:workflow/spec {:intent/type :import} :workflow/status :failed
               :workflow/gate-results [{:passed? false :violations [violation wrapped canonical]}]}]
    (doseq [field [:violation/rule-id :violation/severity] invalid [nil false]]
      (let [record (assoc canonical field invalid :rule {:rule/id :fallback :rule/severity :high})]
        (is (= invalid (get (projection/violation record) field)))))
    (is (= ":policy/example" (:violation/rule-id (projection/violation (assoc canonical :rule-id :legacy)))))
    (with-redefs [artifact/query (constantly [material])]
      (let [bundle (dissoc (collector/assemble-evidence-bundle workflow-id state :test)
                           :evidence/content-hash)
            semantic (:evidence/semantic-validation bundle)]
        (is (false? (:semantic-validation/passed? semantic)))
        (is (seq (:semantic-validation/violations semantic)))
        (is (false? (get-in bundle [:evidence/policy-checks 0 :policy-check/passed?])))
        (is (= ":policy/example" (get-in bundle [:evidence/policy-checks 0 :policy-check/violations 2 :violation/rule-id])))
        (is (= :high (get-in bundle [:evidence/policy-checks 0 :policy-check/violations 2 :violation/severity])))
        (is (valid? bundle))))))

(deftest ^{:stratum 1} canonical-validation-checks-required-types-and-domain-values-test
  (let [bundle (base-bundle)]
    (is (valid? bundle))
    (doseq [key (keys bundle)]
      (is (false? (valid? (dissoc bundle key)))))
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
      (is (false? (valid? (assoc-in bundle path value)))))))

(deftest ^{:stratum 1} declared-hash-is-verified-without-claiming-authority-test
  (let [bundle (assoc (publication-fields (base-bundle))
                      :compliance/sensitive-data false :compliance/pii-handling :none
                      :compliance/created-at #inst "2026-09-30T00:00:00Z"
                      :evidence/sealed-at #inst "2026-09-30T00:00:00Z")
        sealed (assoc bundle :evidence/content-hash (evidence/content-hash bundle))]
    (is (valid? sealed))
    (doseq [[path value] [[[:evidence/event-links] nil]
                          [[:evidence/event-links] []]
                          [[:evidence/event-links] [{}]]
                          [[:evidence/event-links 0 :event-links/scope-type] :unknown]
                          [[:evidence/event-links 0 :event-links/scope-id] (random-uuid)]
                          [[:evidence/event-links 0 :event-links/from-sequence] -1]
                          [[:evidence/event-links 0 :event-links/to-sequence] 0]
                          [[:evidence/event-links 0 :event-links/event-count] 4]
                          [[:evidence/event-links 0 :event-links/event-count] 1]
                          [[:evidence/outcome :outcome/tier] nil]
                          [[:evidence/outcome :outcome/tier] :unknown]
                          [[:evidence/outcome :outcome/degradation-mode] :unknown]
                          [[:evidence/outcome :outcome/degradation-mode] nil]
                          [[:evidence/outcome :outcome/failure-class] :unknown]
                          [[:evidence/outcome :outcome/failure-class] nil]
                          [[:evidence/outcome :outcome/failure-class] :failure.class/timeout]
                          [[:evidence/outcome :outcome/sli-measurements] 42]
                          [[:evidence/outcome :outcome/sli-measurements] nil]
                          [[:evidence/outcome :outcome/sli-measurements 0 :sli/value] ##NaN]
                          [[:evidence/outcome :outcome/sli-measurements 0 :sli/value] ##Inf]
                          [[:evidence/outcome :outcome/sli-measurements 0 :sli/value] ##-Inf]
                          [[:evidence/outcome :outcome/sli-measurements 0 :sli/target] ##Inf]
                          [[:evidence/outcome :outcome/sli-measurements] [{}]]]]
      (let [changed (assoc-in bundle path value)
            rehashed (assoc changed :evidence/content-hash (evidence/content-hash changed))]
        (is (false? (valid? (dissoc changed :evidence/sealed-at))))
        (is (false? (valid? rehashed)))))
    (doseq [[scope id] [[:pr (random-uuid)] [:pack "pack"] [:repo "repo"] [:deployment "deploy"]
                        [:supervisory-entity ["repo" 1]]]]
      (let [linked (-> (publication-fields (base-bundle))
                       (assoc-in [:evidence/event-links 0 :event-links/scope-type] scope)
                       (assoc-in [:evidence/event-links 0 :event-links/scope-id] id))]
        (is (valid? linked))
        (is (false? (valid?
                     (assoc-in linked [:evidence/event-links 0 :event-links/scope-id] 42))))))
    (is (valid? (assoc sealed :evidence/signature "not-an-authenticity-check")))
    (doseq [altered [(assoc-in sealed [:evidence/outcome :outcome/success] false)
                     (assoc sealed :evidence/content-hash nil)
                     (assoc sealed :evidence/content-hash "wrong")
                     (assoc sealed :evidence/signature 42)
                     (assoc sealed :evidence/signature nil)
                     (dissoc sealed :evidence/sealed-at)
                     (dissoc sealed :compliance/created-at)
                     (dissoc sealed :compliance/sensitive-data)
                     (dissoc sealed :compliance/pii-handling)
                     (dissoc sealed :evidence/content-hash)]]
      (is (false? (valid? altered))))))

(deftest ^{:stratum 1} present-structured-fields-cannot-hide-empty-records-test
  (doseq [field [:evidence/semantic-validation :evidence/plan :evidence/design
                 :evidence/implement :evidence/verify :evidence/review
                 :evidence/release :evidence/observe]]
    (is (false? (valid? (assoc (base-bundle) field {})))))
  (doseq [field [:evidence/tool-invocations :evidence/pack-promotions
                 :evidence/supervision-decisions :evidence/control-actions :evidence/rules-applied]]
    (is (valid? (assoc (base-bundle) field [])))
    (is (false? (valid? (assoc (base-bundle) field [{}]))))))

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
    (is (valid?
         (assoc bundle :evidence/semantic-validation semantic
                :evidence/tool-invocations [tool] :evidence/implement phase)))
    (is (valid?
         (-> bundle
             (assoc-in [:evidence/intent :intent/type] :refactor)
             (assoc :evidence/semantic-validation
                    (assoc semantic :semantic-validation/declared-intent :refactor
                           :semantic-validation/actual-behavior :refactor
                           :semantic-validation/resource-updates 0)))))
    (doseq [invalid [{} 42 nil]]
      (is (false? (valid?
                   (assoc bundle :evidence/semantic-validation
                          (assoc semantic :semantic-validation/violations [invalid]))))))
    (doseq [[field value] [[:semantic-validation/declared-intent :destroy]
                           [:semantic-validation/declared-intent :unknown]
                           [:semantic-validation/actual-behavior :unknown]
                           [:semantic-validation/actual-behavior :destroy]
                           [:semantic-validation/resource-creates 1]
                           [:semantic-validation/resource-updates 0]
                           [:semantic-validation/passed? false]
                           [:semantic-validation/violations [{:violation/rule-id "semantic-creates"
                                                              :violation/severity :critical
                                                              :violation/message "Contradictory violation"}]]]]
      (is (false? (valid?
                   (assoc bundle :evidence/semantic-validation (assoc semantic field value))))))
    (doseq [range [{} {:start-seq "0" :end-seq 1} {:start-seq 0} nil
                   {:start-seq -1 :end-seq 1} {:start-seq 10 :end-seq 1}]]
      (is (false? (valid?
                   (assoc bundle :evidence/implement
                          (assoc phase :phase/event-stream-range range))))))
    (is (valid?
         (assoc bundle :evidence/implement (update phase :phase/output dissoc :status))))
    (doseq [[field value] [[:summary 42] [:status :unknown] [:status nil] [:status false] [:status 42]]]
      (is (false? (valid?
                   (assoc-in (assoc bundle :evidence/implement phase)
                             [:evidence/implement :phase/output field] value)))))))

(deftest ^{:stratum 1} canonical-migration-conclusions-use-producer-balance-rules
  (doseq [content ["old will be destroyed\nnew will be created\nextra will be created"
                   "old will be destroyed\nextra will be destroyed\nnew will be created"]]
    (let [bundle (assoc-in (base-bundle) [:evidence/intent :intent/type] :migrate)
          material {:artifact/type :terraform-plan :artifact/content content}
          semantic (projection/semantic-evidence (:evidence/intent bundle) [material])
          honest (assoc bundle :evidence/semantic-validation semantic)
          false-pass (assoc semantic :semantic-validation/passed? true :semantic-validation/violations [])]
      (is (false? (:semantic-validation/passed? semantic)))
      (is (valid? honest))
      (is (false? (valid? (assoc bundle :evidence/semantic-validation false-pass)))))))

(deftest ^{:stratum 1} collector-phase-projections-pass-canonical-validation-test
  (doseq [phase-name [:plan :design :implement :verify :review :release :observe]
          input [{:output {:summary "Done." :metrics {}}}
                 {:environment-id "test" :summary "Done." :metrics {}}]]
    (let [phase (phases/build-phase-evidence phase-name :test (assoc input :duration-ms 0))
          key (keyword "evidence" (name phase-name))
          bundle (assoc (base-bundle) key phase)]
      (is (zero? (:phase/inner-loop-iterations phase)))
      (is (false? (valid? (assoc-in bundle [key :phase/name] :contradictory-phase))))
      (is (valid? bundle))
      (is (false? (valid? (assoc-in bundle [key :phase/output :metrics] 42))))
      (is (false? (valid? (assoc-in bundle [key :phase/artifacts] nil)))))))

(deftest ^{:stratum 1} collector-projects-nonempty-artifact-records-to-identifiers-test
  (let [id (random-uuid)
        phase (phases/build-phase-evidence :implement :test {:artifacts [id {:artifact/id id}]})]
    (is (= [id id] (:phase/artifacts phase)))
    (is (valid? (assoc (base-bundle) :evidence/implement phase)))))

(deftest ^{:stratum 1} field-presence-is-distinct-from-nullability-test
  (let [check (control-fixtures/policy-check)
        bundle (assoc (base-bundle) :evidence/policy-checks [check])]
    (is (valid? bundle))
    (is (false? (valid?
                 (update-in bundle [:evidence/policy-checks 0]
                            dissoc :policy-check/violations))))
    (is (false? (valid? (assoc-in bundle [:evidence/intent :intent/author] nil))))))

(deftest ^{:stratum 1} nested-domain-values-use-their-canonical-schemas-test
  (let [bundle (assoc (base-bundle) :evidence/policy-checks [(control-fixtures/policy-check)])
        constraint {:constraint/type :latency
                    :constraint/description "Keep latency below the objective."}
        violation {:violation/rule-id "latency"
                   :violation/severity :high
                   :violation/message "Latency exceeded the objective."}]
    (doseq [[path valid] [[[:evidence/intent :intent/constraints] constraint]
                          [[:evidence/policy-checks 0 :policy-check/violations] violation]
                          [[:evidence/control-actions] (control-fixtures/action)]]]
      (is (valid? (assoc-in bundle path [valid])))
      (doseq [invalid [{} 42 nil]]
        (is (false? (valid? (assoc-in bundle path [invalid]))))))))

(deftest ^{:stratum 1} nonportable-or-nonmap-input-is-rejected-before-hashing-test
  (doseq [value [nil [] 42 (assoc (base-bundle) :extension (Object.))
                 (assoc (base-bundle) :extension (iterate inc 0))]]
    (is (false? (valid? value)))))

(deftest ^{:stratum 1} collected-supervision-confidence-accepts-producer-numbers-test
  (doseq [confidence [0 1 0.95 0.95M]]
    (let [event {:tool/name "read" :supervision/decision "allow"
                 :event/timestamp #inst "2026-09-30T00:00:00Z"
                 :supervision/confidence confidence}]
      (with-redefs [collectors/collect-event-stream-events (constantly [event])]
        (let [bundle (base-bundle)
              records (collectors/collect-supervision-decisions :test (:evidence-bundle/workflow-id bundle))]
          (is (= confidence (:supervision/confidence (first records))))
          (is (valid? (assoc bundle :evidence/supervision-decisions records))))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.canonical-validation-test))
