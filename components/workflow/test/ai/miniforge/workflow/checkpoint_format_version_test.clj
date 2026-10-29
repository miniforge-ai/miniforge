;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-format-version-test
  "Every checkpoint record carries `:checkpoint/format-version`. A record
   without one is legacy and loads; one written in a newer (or malformed)
   format is refused, naming the file and both versions."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.checkpoint-store-records :as records]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.interface :as workflow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-paths
  "The snapshot, manifest and :plan checkpoint of a run checkpointed
   under `root` after completing :plan. Returns [run-id paths]."
  [root]
  (let [ctx (-> (context/create-context {:workflow/id :format-version
                                         :workflow/version "1.0.0"
                                         :workflow/pipeline [{:phase :plan}]}
                                        {:task "Test"}
                                        {:checkpoint/root root})
                (assoc :execution/current-phase :plan
                       :execution/phase-results {:plan {:status :completed}}))
        run-id (:execution/id ctx)]
    (store/persist-execution-state! ctx)
    [run-id {:snapshot (paths/machine-snapshot-path root run-id)
             :manifest (paths/manifest-path root run-id)
             :plan (paths/phase-checkpoint-path root run-id :plan)}]))

(defn- ^{:stratum 0} rewrite! [path f]
  (spit path (pr-str (f (edn/read-string (slurp path))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} every-record-is-written-with-the-format-version
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [[_ files] (record-paths root)]
       (doseq [[record path] files]
         (is (= records/checkpoint-format-version
                (:checkpoint/format-version (edn/read-string (slurp path))))
             (name record)))))))

(deftest ^{:stratum 1} records-without-a-version-load-as-legacy
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [[run-id files] (record-paths root)]
       (doseq [path (vals files)]
         (rewrite! path #(dissoc % :checkpoint/format-version)))
       (let [loaded (workflow/load-checkpoint-data run-id {:checkpoint/root root})]
         (is (= run-id (get-in loaded [:machine-snapshot :execution/id])))
         (is (contains? (:phase-results loaded) :plan))
         (is (empty? (:checkpoint/read-failures loaded))))))))

(deftest ^{:stratum 1} a-newer-snapshot-format-is-refused
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [[run-id files] (record-paths root)
           newer (inc records/checkpoint-format-version)]
       (rewrite! (:snapshot files) #(assoc % :checkpoint/format-version newer))
       (let [loaded (workflow/load-checkpoint-data run-id {:checkpoint/root root})
             failure (first (get-in loaded [:anomaly/data :checkpoint/read-failures]))]
         (is (anomaly/anomaly? loaded))
         (is (= :unsupported (:anomaly/type failure)))
         (is (= (:snapshot files) (get-in failure [:anomaly/data :checkpoint/path])))
         (is (= newer (get-in failure [:anomaly/data :checkpoint/format-version])))
         (testing "the run-level message carries the file's reason"
           (is (str/includes? (:anomaly/message loaded) (str "format " newer)))))))))

(deftest ^{:stratum 1} a-newer-or-malformed-phase-format-costs-that-phase
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (doseq [version [(inc records/checkpoint-format-version) "1"]]
       (let [[run-id files] (record-paths root)]
         (rewrite! (:plan files) #(assoc % :checkpoint/format-version version))
         (let [loaded (workflow/load-checkpoint-data run-id {:checkpoint/root root})
               failure (first (:checkpoint/read-failures loaded))]
           (is (= run-id (get-in loaded [:machine-snapshot :execution/id])) (pr-str version))
           (is (not (contains? (:phase-results loaded) :plan)) (pr-str version))
           (is (= :unsupported (:anomaly/type failure)) (pr-str version))
           (is (= :plan (get-in failure [:anomaly/data :phase/name])) (pr-str version))))))))

(comment ::checkpoint-format-version)
