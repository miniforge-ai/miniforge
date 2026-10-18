;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.opsv-current-publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.opsv-constructor-cases :as cases]
            [ai.miniforge.event-stream.opsv-fixtures :as fixtures]
            [ai.miniforge.redaction.interface :as redaction]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} draft [stream workflow-id event-type payload]
  (merge (events/create-event-draft stream event-type workflow-id "Fixture OPSV event")
         payload {:scope/type :workflow :opsv/evidence-bundle-id (random-uuid)}))

(defn- ^{:stratum 0} with-stream [operation]
  (fs/with-temp-dir [directory {:prefix "opsv-current-publication-"}]
    (let [options {:journal-directory (str (fs/real-path directory))}
          stream (events/create-current-event-stream options)]
      (is (not (anomaly/anomaly? stream)) (pr-str stream))
      (when-not (anomaly/anomaly? stream)
        (try+ (operation stream options)
          (finally (events/close-current-event-stream! stream)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} all-opsv-families-commit-and-reopen-with-authoritative-positions
  (with-stream
    (fn [stream options]
      (let [workflow-id (random-uuid)
            drafts (mapv (fn [[_ _ type payload]] (draft stream workflow-id type payload)) cases/constructors)
            receipts (mapv #(events/publish! stream %) drafts)]
        (is (every? #(not (contains? % :event/sequence-number)) drafts))
        (is (= (range (count cases/constructors)) (map :event/sequence-number receipts)))
        (doseq [[[_ schema _ _] receipt] (map vector cases/constructors receipts)]
          (is (m/validate schema receipt)))
        (is (= receipts (events/get-events stream {:scope [:workflow workflow-id]})))
        (is (= (first receipts) (events/publish! stream (first drafts))))
        (let [[_ _ type payload] (first cases/constructors)
              separate (events/publish! stream (draft stream (random-uuid) type payload))]
          (is (= 0 (:event/sequence-number separate))))
        (events/close-current-event-stream! stream)
        (let [reopened (events/create-current-event-stream options)]
          (try+ (is (= receipts (events/get-events reopened {:scope [:workflow workflow-id]})))
            (finally (events/close-current-event-stream! reopened))))))))

(deftest ^{:stratum 1} malformed-opsv-drafts-are-refused-without-publication
  (with-stream
    (fn [stream _]
      (doseq [[_ _ type payload] cases/constructors]
        (let [event (draft stream (random-uuid) type payload)]
          (doseq [key (keys payload)]
            (is (anomaly/anomaly? (events/publish! stream (dissoc event key)))))
          (doseq [changed [{:event/version "2.0.0"} {:scope/type :chain}
                          {:event/sequence-number 0} {:opsv/evidence-bundle-id nil} {:workflow/id nil}]]
            (is (anomaly/anomaly? (events/publish! stream (merge event changed)))))))
      (is (empty? (events/get-events stream))))))

(deftest ^{:stratum 1} governed-disposition-cross-field-checks-survive-draft-projection
  (with-stream
    (fn [stream _]
      (let [event (draft stream (random-uuid) :opsv.actuation/disposition fixtures/disposition-payload)]
        (is (anomaly/anomaly? (events/publish! stream
                                             (assoc-in event [:opsv/governed-effect :evidence/envelope-id] (random-uuid)))))
        (is (anomaly/anomaly? (events/publish! stream
                                             (assoc event :opsv/effect-state :proposed :opsv/effect-observed {:unexpected true}))))
        (is (empty? (events/get-events stream)))))))

(deftest ^{:stratum 1} provenance-cannot-be-redacted-into-a-different-valid-event
  (with-stream
    (fn [stream _]
      (let [secret "AKIAIOSFODNN7EXAMPLE"
            protected-paths {:opsv.experiment/planned [[:opsv/experiment-pack-hash] [:opsv/targets :services 0]]
                             :opsv.experiment/started [[:opsv/environment-fingerprint :cluster]]
                             :opsv/load-step [[:opsv/step-id]]
                             :opsv.convergence/iteration [[:opsv/iteration-id]]
                             :opsv.policy/proposed [[:opsv/policy-hash]]
                             :opsv.actuation/emitted [[:opsv/pr-refs 0] [:opsv/apply-refs 0]]
                             :opsv.actuation/disposition [[:opsv/decision-envelope :envelope/pins :pins/pack-revision]
                                                         [:opsv/effect-observed :pr/url]]}]
        (doseq [[_ schema type payload] cases/constructors
                path (get protected-paths type)]
          (let [event (assoc-in (draft stream (random-uuid) type payload) path secret)
                refused (events/publish! stream event)]
            (is (m/validate schema (assoc event :event/sequence-number 0)))
            (is (anomaly/anomaly? refused))
            (is (not (str/includes? (pr-str refused) secret)))))
        (is (empty? (events/get-events stream)))))))

(deftest ^{:stratum 1} ordinary-observations-remain-redactable-without-changing-authority
  (with-stream
    (fn [stream _]
      (let [payload (some (fn [[_ _ type payload]] (when (= :opsv/load-step type) payload)) cases/constructors)
            event (draft stream (random-uuid) :opsv/load-step
                         (assoc-in payload [:opsv/observed-load :password] "fixture-password"))
            receipt (events/publish! stream event)]
        (is (= 0 (:event/sequence-number receipt)))
        (is (= (:opsv/evidence-bundle-id event) (:opsv/evidence-bundle-id receipt)))
        (is (= (:opsv/step-id event) (:opsv/step-id receipt)))
        (is (= (redaction/marker) (get-in receipt [:opsv/observed-load :password])))))))

(comment
  ::typed-opsv-publication)
