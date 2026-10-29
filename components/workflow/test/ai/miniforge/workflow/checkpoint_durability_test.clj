;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-durability-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.workflow.checkpoint-temporary-support :as support]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capture [action]
  (try+ (action) (catch Object failure failure)))

(defn- ^{:stratum 0} record-write! [original! calls target file bytes]
  (is (not= (str file) target))
  (is (= :previous (edn/read-string (slurp target))))
  (swap! calls conj :write)
  (original! file bytes))

(defn- ^{:stratum 0} record-confirm! [original! calls file]
  (is (= :candidate (edn/read-string (slurp file))))
  (swap! calls conj :confirm)
  (original! file))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-refusal [port expected root]
  (let [target (str (fs/path root "snapshot.edn"))
        failure (anomaly/anomaly :fault "injected durability failure" {})
        replace! (partial support/write! target :candidate)]
    (support/write! target :previous)
    (with-redefs-fn {port (constantly failure)}
      #(is (= failure (capture replace!))))
    (is (= expected (edn/read-string (slurp target))))
    (is (= ["snapshot.edn"] (mapv fs/file-name (fs/list-dir root))))))

(defn- ^{:stratum 1} verify-ordering [root]
  (let [target (str (fs/path root "snapshot.edn"))
        calls (atom [])
        write! (partial record-write! durability/write-temporary-bytes! calls target)
        confirm! (partial record-confirm! durability/confirm! calls)]
    (support/write! target :previous)
    (with-redefs [durability/write-temporary-bytes! write!
                  durability/confirm! confirm!]
      (is (= target (support/write! target :candidate))))
    (is (= [:write :confirm] @calls))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} force-precedes-rename-and-confirmation-follows-publication
  (fixture/call-with-temp-checkpoint-root verify-ordering))

(deftest ^{:stratum 2} failed-force-preserves-prior-checkpoint-and-refuses-success
  (fixture/call-with-temp-checkpoint-root
   (partial verify-refusal #'durability/write-temporary-bytes! :previous)))

(deftest ^{:stratum 2} failed-confirmation-does-not-acknowledge-published-bytes
  (fixture/call-with-temp-checkpoint-root
   (partial verify-refusal #'durability/confirm! :candidate)))

(comment ::checkpoint-durability)
