;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.content-json-test
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.artifact.publication-codec :as codec]
            [ai.miniforge.anomaly.interface :as anomaly]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} interrupted-result []
  (try (artifact/encode-content-json {})
       (catch InterruptedException interrupted
         {:exception interrupted :interrupted? (.isInterrupted (Thread/currentThread))})
       (finally (Thread/interrupted))))

(deftest ^{:stratum 0} json-preserves-portable-types-without-lossy-key-coercion
  (let [value {:event/id (random-uuid) :event/at (Instant/parse "2026-10-01T00:00:00.123456789Z")
               :event/data {:key "keyword" "key" "string" [:compound] #{:value}}
               :values [(list 1 2) [1 2] nil false]}
        encoded (artifact/encode-content-json value)
        decoded (codec/decode (.getBytes encoded StandardCharsets/UTF_8))]
    (is (some? (json/parse-string encoded)))
    (is (= value decoded))
    (is (list? (get-in decoded [:values 0])))
    (is (vector? (get-in decoded [:values 1])))
    (is (= (artifact/content-digest value) (artifact/content-digest decoded)))))

(deftest ^{:stratum 0} json-refuses-unsupported-and-deferred-content
  (let [realized? (atom false)
        deferred (lazy-seq (reset! realized? true) (repeat :never))]
    (doseq [value [(Object.) deferred]]
      (is (anomaly/anomaly? (artifact/encode-content-json value))))
    (is (false? @realized?))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} json-boundary-preserves-interruption-and-fatal-errors
  (with-redefs [codec/encode (fn [_] (throw (InterruptedException.)))]
    (let [result (interrupted-result)]
      (is (instance? InterruptedException (:exception result)))
      (is (true? (:interrupted? result)))))
  (with-redefs [codec/encode (fn [_] (throw (AssertionError.)))]
    (is (thrown? AssertionError (artifact/encode-content-json {})))))

(comment
  (clojure.test/run-tests 'ai.miniforge.artifact.content-json-test))
