;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.commit-model-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.commit-model :as model]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} draft []
  {:event/id (random-uuid)
   :event/type :workflow/started
   :event/timestamp (java.util.Date.)
   :event/version "1.0.0"
   :message "started"})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} preparation-does-not-consume-a-sequence
  (let [state (model/empty-state)
        scope [:workflow (random-uuid)]
        first-event (model/candidate state scope (draft))
        abandoned (model/candidate state scope (draft))
        accepted (model/accept state scope first-event)]
    (is (= 0 (:event/sequence-number first-event) (:event/sequence-number abandoned)))
    (is (= (model/empty-state) state))
    (is (= 1 (:event/sequence-number (model/candidate accepted scope (draft)))))
    (is (= first-event (:event (model/recorded accepted (:event/id first-event)))))))

(deftest ^{:stratum 1} scope-type-and-identity-both-partition-sequences
  (let [id (random-uuid)
        workflow [:workflow id]
        other-workflow [:workflow (random-uuid)]
        pr [:pr id]
        state (model/empty-state)
        event (model/candidate state workflow (draft))
        accepted (model/accept state workflow event)]
    (is (= 1 (:event/sequence-number (model/candidate accepted workflow (draft)))))
    (doseq [scope [pr other-workflow]]
      (is (= 0 (:event/sequence-number (model/candidate accepted scope (draft))))))))

(deftest ^{:stratum 1} retries-return-the-original-acknowledgment
  (let [scope [:workflow (random-uuid)]
        state (model/empty-state)
        event (model/candidate state scope (draft))
        accepted (model/accept state scope event)
        retry (assoc event :event/timestamp (java.util.Date. 0) :event/sequence-number 99)]
    (is (= event (model/candidate accepted scope retry)))
    (is (anomaly/anomaly? (model/candidate accepted scope (assoc retry :message "changed"))))
    (is (anomaly/anomaly? (model/candidate accepted [:pr (random-uuid)] retry)))))

(deftest ^{:stratum 1} sequence-overflow-is-explicit
  (let [scope [:workflow (random-uuid)]
        state (assoc-in (model/empty-state) [:next-sequences scope] Long/MAX_VALUE)
        event (model/candidate state scope (draft))
        accepted (model/accept state scope event)]
    (is (= Long/MAX_VALUE (:event/sequence-number event)))
    (is (= :exhausted (:anomaly/type (model/candidate accepted scope (draft)))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.commit-model-test))
