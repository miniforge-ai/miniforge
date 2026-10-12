;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-retention-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.workflow.chain-composition :as composition]
            [ai.miniforge.workflow.chain-loader :as loader]
            [ai.miniforge.workflow.chain-retention-record :as retention]
            [ai.miniforge.workflow.chain-test-support :as support]
            [ai.miniforge.workflow.interface :as workflow]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-record []
  (composition/run-record (support/definition :retention-test "1.0.0")))

(defn- ^{:stratum 0} publish [calls _directory value]
  (swap! calls conj value)
  value)

(defn- ^{:stratum 0} read-record [values _directory id]
  (get values id))

(defn- ^{:stratum 0} stored [artifacts]
  (into {} (map (juxt :artifact/id identity)) artifacts))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} publishes-definition-before-invocation-and-reuses-version-key
  (let [value (run-record) calls (atom [])]
    (with-redefs [artifact/publish! (partial publish calls)]
      (is (= value (workflow/persist-chain-run! "/controlled" value)))
      (is (= (retention/artifacts value) @calls))
      (let [[definition invocation] @calls
            next-run (run-record)]
        (is (= [(:artifact/id definition)] (:artifact/parents invocation)))
        (is (= (:chain/run-id value) (:artifact/id invocation)))
        (is (= value (get-in invocation [:artifact/content :chain/record])))
        (is (= definition (first (retention/artifacts next-run))))
        (is (not= invocation (second (retention/artifacts next-run))))))))

(deftest ^{:stratum 1} no-invocation-publication-after-unconfirmed-definition
  (let [calls (atom []) failure (anomaly/anomaly :unavailable "test" {})]
    (doseq [receipt [nil {} failure]]
      (reset! calls [])
      (with-redefs [artifact/publish! (fn [& args] (swap! calls conj args) receipt)]
        (is (anomaly/anomaly? (workflow/persist-chain-run! "/controlled" (run-record))))
        (is (= 1 (count @calls)))))))

(deftest ^{:stratum 1} unconfirmed-invocation-is-not-reported-as-retained
  (let [calls (atom []) value (run-record)]
    (with-redefs [artifact/publish! (fn [_directory artifact]
                                     (swap! calls conj artifact)
                                     (when (= 1 (count @calls)) artifact))]
      (is (= :unavailable (:anomaly/type (workflow/persist-chain-run! "/controlled" value))))
      (is (= 2 (count @calls))))))

(deftest ^{:stratum 1} record-copy-drops-comparators-and-benign-metadata
  (let [value (run-record) calls (atom [])
        marked (with-meta (into (sorted-map) value) {:note "caller-local"})]
    (with-redefs [artifact/publish! (partial publish calls)]
      (let [retained (workflow/persist-chain-run! "/controlled" marked)]
        (is (= value retained))
        (is (not (sorted? retained)))
        (is (nil? (meta retained)))))))

(deftest ^{:stratum 1} malformed-or-unsafe-admissions-never-reach-storage
  (let [value (run-record) calls (atom [])
        secret "Bearer abcdefghijklmnopqrstuvwxyz0123456789"
        invalid [nil {} (assoc value :chain/run-id :not-a-uuid)
                 (assoc value :chain/definition-version "2.0.0")
                 (with-meta value {:password secret})
                 (assoc-in value [:chain/definition-snapshot :chain/description] secret)
                 (assoc value :opaque (atom :hidden))
                 (sorted-map "unexpected" true)]]
    (with-redefs [artifact/publish! (partial publish calls)]
      (doseq [input invalid]
        (is (anomaly/anomaly? (workflow/persist-chain-run! "/controlled" input))))
      (is (empty? @calls)))))

(deftest ^{:stratum 1} restores-original-run-without-resolving-or-allocating
  (let [value (run-record) records (stored (retention/artifacts value))
        unexpected (fn [& _] (is false "Recovery must not resolve or allocate a run."))]
    (with-redefs [artifact/read-published (partial read-record records)
                  composition/run-record unexpected
                  loader/load-chain unexpected]
      (is (= value (workflow/load-chain-run "/controlled" (:chain/run-id value)))))))

(deftest ^{:stratum 1} refuses-missing-conflicting-and-corrupt-recovery
  (let [value (run-record) id (:chain/run-id value)
        [definition invocation] (retention/artifacts value)
        records (stored [definition invocation])
        unavailable (anomaly/anomaly :unavailable "test" {})]
    (doseq [bad-records [{} (dissoc records (:artifact/id definition))
                         (assoc records id unavailable)
                         (assoc-in records [id :artifact/type] :code)
                         (assoc-in records [id :artifact/metadata :password] "do-not-retain")
                         (assoc-in records [id :artifact/content :chain/record :chain/run-id] (random-uuid))
                         (assoc-in records [(:artifact/id definition) :artifact/content :chain/record :chain/steps] [])]]
      (with-redefs [artifact/read-published (partial read-record bad-records)]
        (is (anomaly/anomaly? (workflow/load-chain-run "/controlled" id)))))))

(deftest ^{:stratum 1} recovery-distinguishes-equal-numbers-with-different-wire-kinds
  (let [value (assoc-in (run-record) [:chain/definition-snapshot :chain/steps 0 :step/input-bindings]
                        {:task [0]})
        [definition invocation] (retention/artifacts value)
        altered (assoc-in definition [:artifact/content :chain/record :chain/steps 0 :step/input-bindings :task] [0N])]
    (is (= definition altered))
    (with-redefs [artifact/read-published (partial read-record (stored [altered invocation]))]
      (is (= :conflict (:anomaly/type (workflow/load-chain-run "/controlled" (:chain/run-id value))))))))

(comment
  (run-record))
