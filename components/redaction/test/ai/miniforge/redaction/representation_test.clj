;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.redaction.representation-test
  (:require [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defrecord ^{:stratum 0} OpaqueRecord [value])

(defn- ^{:stratum 0} opaque-map [hidden]
  (reify clojure.lang.IPersistentMap
    (seq [_] nil)
    (count [_] 0)
    (cons [this _] this)
    (empty [this] this)
    (equiv [_ _] false)
    (assoc [this _ _] this)
    (assocEx [this _ _] this)
    (without [this _] this)
    (containsKey [_ _] false)
    (entryAt [_ _] nil)
    (valAt [_ _] hidden)
    (valAt [_ _ _] hidden)))

(deftest ^{:stratum 0} supported-values-include-keys-and-metadata
  (doseq [value [nil true false "text" \a :field 'field
                (byte 1) (short 2) (int 3) (long 4) (float 2.5) (double 3.5)
                42N 2.5M (biginteger 42) 1/2
                (random-uuid) #inst "2026-10-05" (java.time.Instant/now)
                [] {} #{} '() '(1 2) {:nested [nil #{'field}]}
                (hash-map :key :value) (sorted-map :key :value)
                (sorted-set 1 2) (subvec [1 2 3] 1) (first {:key :value})]]
    (is (redaction/supported? value))
    (is (redaction/supported? (redaction/redact value)))
    (is (redaction/supported? (with-meta {value :value} {:nested value})))))

(deftest ^{:stratum 0} structural-bounds-include-keys-and-metadata
  (let [deep (nth (iterate vector nil) 4096)]
    (doseq [value [deep (array-map deep :key) (with-meta [] {:nested deep})
                  (vec (repeat 100001 nil))]]
      (is (false? (redaction/supported? value))))
    (is (redaction/supported? (nth (iterate vector nil) 16)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} custom-map-hidden-state-is-rejected
  (let [value (opaque-map "AKIAIOSFODNN7EXAMPLE")]
    (is (map? value))
    (is (nil? (seq value)))
    (is (= "AKIAIOSFODNN7EXAMPLE" (get value :hidden)))
    (doseq [nested [value [value] {value :key} (with-meta [] {:nested value})]]
      (is (false? (redaction/supported? nested))))))

(deftest ^{:stratum 1} unsupported-representations-remain-opaque
  (let [realized? (atom false)
        deferred (delay (reset! realized? true))]
    (doseq [value [(->OpaqueRecord :value) deferred (atom :value)
                  (object-array [:value]) (java.util.ArrayList. [:value])
                  (java.util.HashMap. {:key :value}) (map identity [:value])
                  (java.util.concurrent.atomic.AtomicLong. 4111111111111111)
                  (java.util.concurrent.atomic.AtomicInteger. 42)]
            nested [value [value] {value :key} (with-meta [] {:nested value})
                    (with-meta 'field {:nested value})]]
      (is (false? (redaction/supported? nested))))
    (is (false? @realized?))))

(comment
  (redaction/supported? {:nested [1 2]}))
