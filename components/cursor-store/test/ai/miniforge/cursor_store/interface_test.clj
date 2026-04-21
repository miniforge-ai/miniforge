(ns ai.miniforge.cursor-store.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.cursor-store.interface :as sut]
            [ai.miniforge.logging.interface :as log])
  (:import [java.time Instant]))

(defn- tmp-pipeline-path []
  (str (System/getProperty "java.io.tmpdir")
       "/cursor-store-test-"
       (random-uuid)
       "/pipelines/pipeline.edn"))

(def ^:private logger (log/create-logger {:min-level :debug :output :human}))

;; ---------------------------------------------------------------------------
;; load-cursors

(deftest load-cursors-first-run-test
  (testing "Returns empty map when no cursor file exists yet"
    (let [result (sut/load-cursors logger (tmp-pipeline-path))]
      (is (:success? result))
      (is (= {} (:cursors result))))))

;; ---------------------------------------------------------------------------
;; round-trip

(deftest round-trip-test
  (testing "save then load preserves cursor data with stable key"
    (let [path      (tmp-pipeline-path)
          stage-id  (random-uuid)
          conn-ref  :conn/gitlab
          schema    "issues"
          instant   (Instant/now)
          cursor-map {stage-id {:stage/id            stage-id
                                :stage/name          "Ingest Issues"
                                :stage/connector-ref conn-ref
                                :stage/schema-name   schema
                                :cursor              {:cursor/type  :offset
                                                      :cursor/value 42}
                                :cursor/updated-at   instant}}]
      (is (:success? (sut/save-cursors logger path cursor-map)))
      (let [result (sut/load-cursors logger path)]
        (is (:success? result))
        (let [loaded (get (:cursors result) [conn-ref schema])]
          (is (some? loaded))
          (is (= :offset (get-in loaded [:cursor :cursor/type])))
          (is (= 42 (get-in loaded [:cursor :cursor/value])))
          ;; :cursor/updated-at is stored as an ISO-8601 string (Instant not EDN-printable)
          (is (string? (:cursor/updated-at loaded)))
          (is (= (str instant) (:cursor/updated-at loaded))))))))

(deftest multi-stage-round-trip-test
  (testing "Multiple stages produce multiple entries keyed by connector/schema"
    (let [path  (tmp-pipeline-path)
          id-1  (random-uuid)
          id-2  (random-uuid)
          cursor-map {id-1 {:stage/connector-ref :conn/gitlab
                             :stage/schema-name   "issues"
                             :cursor              {:cursor/type :offset :cursor/value 10}
                             :cursor/updated-at   (Instant/now)}
                      id-2 {:stage/connector-ref :conn/gitlab
                             :stage/schema-name   "merge-requests"
                             :cursor              {:cursor/type :offset :cursor/value 5}
                             :cursor/updated-at   (Instant/now)}}]
      (sut/save-cursors logger path cursor-map)
      (let [loaded (:cursors (sut/load-cursors logger path))]
        (is (= 2 (count loaded)))
        (is (= 10 (get-in loaded [[:conn/gitlab "issues"] :cursor :cursor/value])))
        (is (= 5 (get-in loaded [[:conn/gitlab "merge-requests"] :cursor :cursor/value])))))))

;; ---------------------------------------------------------------------------
;; normalization

(deftest normalization-drops-incomplete-entries-test
  (testing "Entries without connector-ref or schema-name are excluded"
    (let [path     (tmp-pipeline-path)
          good-id  (random-uuid)
          bad-id   (random-uuid)
          cursor-map {good-id {:stage/connector-ref :conn/github
                               :stage/schema-name   "pulls"
                               :cursor              {:cursor/type :offset :cursor/value 1}
                               :cursor/updated-at   (Instant/now)}
                      bad-id  {:stage/name        "No-ref stage"
                               :cursor            {:cursor/type :offset :cursor/value 2}
                               :cursor/updated-at (Instant/now)}}]
      (sut/save-cursors logger path cursor-map)
      (let [loaded (:cursors (sut/load-cursors logger path))]
        (is (= 1 (count loaded)))
        (is (contains? loaded [:conn/github "pulls"]))))))

;; ---------------------------------------------------------------------------
;; idempotency — second save overwrites first

(deftest overwrite-test
  (testing "Saving again overwrites prior cursor with updated value"
    (let [path     (tmp-pipeline-path)
          conn-ref :conn/gitlab
          schema   "issues"
          run1 {(random-uuid) {:stage/connector-ref conn-ref :stage/schema-name schema
                               :cursor {:cursor/type :offset :cursor/value 10}
                               :cursor/updated-at (Instant/now)}}
          run2 {(random-uuid) {:stage/connector-ref conn-ref :stage/schema-name schema
                               :cursor {:cursor/type :offset :cursor/value 20}
                               :cursor/updated-at (Instant/now)}}]
      (sut/save-cursors logger path run1)
      (sut/save-cursors logger path run2)
      (let [loaded (:cursors (sut/load-cursors logger path))]
        (is (= 20 (get-in loaded [[conn-ref schema] :cursor :cursor/value])))))))
