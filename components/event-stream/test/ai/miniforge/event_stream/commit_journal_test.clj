;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.commit-journal-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.commit-journal :as journal]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.commit-test-support :as f]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} acknowledgment-and-retry-write-once
  (let [receipts (atom [])
        store (journal/create (model/empty-state) (partial f/record! receipts))
        scope [:workflow (random-uuid)]
        draft (f/draft)
        committed (journal/commit! store scope draft)]
    (is (= 0 (:event/sequence-number committed)))
    (is (= [[scope committed]] @receipts))
    (is (= committed (journal/commit! store scope draft)))
    (is (anomaly/anomaly? (journal/commit! store scope (assoc draft :message "changed"))))
    (is (= 1 (count @receipts)))
    (is (= 1 (:event/sequence-number (journal/commit! store scope (f/draft)))))))

(deftest ^{:stratum 0} uncertain-receipts-fence-without-advancing
  (doseq [receipt [nil false {} (f/draft) (anomaly/anomaly :unavailable "test" {})]]
    (let [store (journal/create (model/empty-state) (constantly receipt))
          scope [:workflow (random-uuid)]]
      (is (anomaly/any-anomaly? (journal/commit! store scope (f/draft))))
      (is (= {} (:next-sequences @(:state store))))
      (is (= {} (:committed @(:state store))))
      (is (= :unavailable (:anomaly/type (journal/commit! store scope (f/draft)))))
      (is (not (contains? @(:state store) :writing?))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.commit-journal-test))
