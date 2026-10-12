;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.supervisory-spec-test
  (:require [ai.miniforge.event-stream.supervisory-spec :as spec]
            [ai.miniforge.event-stream.supervisory-test-support :as support]
            [ai.miniforge.schema.interface :as schema]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:private true :stratum 0} snapshot support/snapshot)

(def ^{:private true :stratum 0} request support/request)

(def ^{:private true :stratum 0} change support/change)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} spec-snapshots-use-complete-records-and-semantic-schema-versions
  (let [event (snapshot)]
    (doseq [version [nil 2 "2" "02.0.0" "2.0.0-01" "2.0.0\n"]]
      (is (not (schema/valid? spec/SpecSnapshot (assoc event :supervisory/schema-version version)))))
    (doseq [version ["0.0.0" "2.0.0-rc.1+build.1"]]
      (is (schema/valid? spec/SpecSnapshot (assoc event :supervisory/schema-version version))))
    (doseq [field (keys (:supervisory/entity event))]
      (is (not (schema/valid? spec/SpecSnapshot (update event :supervisory/entity dissoc field)))))
    (doseq [title [" " "\u2003" "\u00a0" "\u3000"]]
      (is (not (schema/valid? spec/SpecSnapshot (assoc-in event [:supervisory/entity :spec/title] title)))))))

(deftest ^{:stratum 1} complete-payloads-preserve-open-extensions-and-required-fields
  (doseq [[contract event] [[spec/InterventionPayload (request)]
                            [spec/InterventionPayload (change)]
                            [spec/SpecSnapshot (snapshot)]]]
    (is (schema/valid? contract event))
    (is (schema/valid? contract (assoc event :extension/unknown :preserved)))
    (is (schema/valid? contract (assoc event :workflow/id (random-uuid))))
    (doseq [field (keys event)]
      (is (not (schema/valid? contract (dissoc event field)))))
    (doseq [field (remove #{:intervention/target-id} (keys event))]
      (is (not (schema/valid? contract (assoc event field nil)))))
    (doseq [invalid [nil false [] :event]]
      (is (not (schema/valid? contract invalid))))
    (doseq [[field value] [[:supervisory/entity-key (random-uuid)]
                          [:supervisory/entity-key :label] [:workflow/id :label]
                          [:scope/type :workflow] [:event/type :supervisory/unknown]]]
      (is (not (schema/valid? contract (assoc event field value)))))))

(deftest ^{:stratum 1} intervention-facts-require-current-profile-and-initial-state
  (doseq [event [(request) (change)]]
    (doseq [version [nil 2 "1.0.0" "2.1.0"]]
      (is (not (schema/valid? spec/InterventionPayload (assoc event :event/version version)))))
    (is (not (schema/valid? spec/InterventionPayload (assoc event :intervention/state :unknown)))))
  (doseq [state [:pending-human :approved :rejected :dispatched :applied :verified :failed]]
    (is (not (schema/valid? spec/InterventionPayload (assoc (request) :intervention/state state)))))
  (is (not (schema/valid? spec/InterventionPayload (assoc (change) :intervention/from-state :unknown)))))

(deftest ^{:stratum 1} intervention-identities-and-times-retain-wire-types
  (doseq [event [(request) (change)]]
    (doseq [field [:intervention/id :intervention/updated-at]]
      (is (not (schema/valid? spec/InterventionPayload (assoc event field "invalid"))))))
  (doseq [field [:intervention/type :intervention/target-type :intervention/request-source
                :intervention/requested-at]]
    (is (not (schema/valid? spec/InterventionPayload (assoc (request) field "invalid")))))
  (is (not (schema/valid? spec/InterventionPayload (assoc (request) :intervention/justification nil)))))

(comment
  (schema/valid? spec/InterventionPayload (request)))
