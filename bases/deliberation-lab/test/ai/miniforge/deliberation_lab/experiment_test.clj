;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-lab.experiment-test
  (:require [ai.miniforge.deliberation-lab.adapter :as adapter]
            [ai.miniforge.deliberation-lab.codec :as codec]
            [ai.miniforge.deliberation-lab.experiment :as experiment]
            [ai.miniforge.deliberation-lab.fixtures :as fixtures]
            [ai.miniforge.deliberation-lab.journal :as journal]
            [ai.miniforge.deliberation-lab.main :as main]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(def manifest {:schema-version 1 :mode :development :model "test-model" :conditions [:workspace :workspace-ablated]
               :replicates 1 :max-input-bytes 48000
               :price-table {:input-per-million 3 :output-per-million 15}
               :adapter {:timeout-seconds 1 :call-cap-usd 0.1}
               :tiers [{:id :tiny :activations 4 :max-normalized-usd 1 :wall-seconds 30}]})
(def task (first fixtures/tasks))
(def tier (first (:tiers manifest)))
(defn call-with [seen]
  (let [counter (atom 0)]
    (fn [request]
      (swap! seen conj request)
      (let [n (swap! counter inc)]
        {:payload {:operations [{:op :assert-claim :creates [{:id (str "claim-" n) :type :claim
                                                             :statement (if (= :proposer (:role request))
                                                                          "peer-exclusive-observation" "own-observation")}]}]
                   :inspect (:available-experiments request) :solution (:reference task)
                   :rationale "SYNTHETIC test record"}
         :usage {:input 100 :output 20 :provider-usd 0.001}}))))

(deftest shared-and-blind-have-identical-allowances-but-different-information
  (let [shared (atom []) blind (atom [])]
    (doseq [[condition requests] [[:workspace shared] [:workspace-ablated blind]]]
      (let [result (experiment/run-cell manifest task condition tier 0 (call-with requests) (constantly nil))]
        (is (= 4 (:activations result)))
        (is (= 1 (get-in result [:score :success])))
        (is (= 1 (count (filter :closing? @requests))))))
    (is (= (map :role @shared) (map :role @blind)))
    (is (.contains (pr-str (last @shared)) "peer-exclusive-observation"))
    (is (not (.contains (pr-str (last @blind)) "peer-exclusive-observation")))
    (doseq [request (concat @shared @blind)]
      (is (not (.contains (pr-str request) ":reference")))
      (is (not (.contains (pr-str request) ":workspace/")))
      (is (not (.contains (pr-str request) ":object/version")))
      (is (not (.contains (pr-str request) ":object/activation"))))))

(deftest adapter-failures-remain-scored-cells-with-no-retry
  (let [calls (atom 0) records (atom [])
        result (experiment/run-cell manifest task :workspace tier 0
                 (fn [_] (swap! calls inc) (throw (ex-info "timeout" {:category :timeout})))
                 #(swap! records conj %))]
    (is (= 1 @calls))
    (is (= :activation-started (:kind (first @records))))
    (is (= :cell-completed (:kind (last @records))))
    (is (= :failed (:status result)))
    (is (= :timeout (:failure result)))
    (is (:usage-unknown? result))
    (is (= 0 (get-in result [:score :success])))))

(deftest an-over-budget-final-call-cannot-score-success
  (let [requests (atom [])
        result (experiment/run-cell manifest task :workspace
                 (assoc tier :activations 2 :max-normalized-usd 0.0009)
                 0 (call-with requests) (constantly nil))]
    (is (= 2 (:activations result)))
    (is (= 0 (get-in result [:score :success])))
    (is (= :budget-boundary (:status result)))))

(deftest metering-cannot-be-forged-in-model-output
  (is (thrown? Exception (adapter/decode "{\"operations\":[],\"inspect\":[],\"solution\":{},\"rationale\":\"x\",\"usage\":{}}")))
  (is (= :assert-claim (get-in (adapter/decode "{\"operations\":[{\"op\":\"assert-claim\"}],\"inspect\":[],\"solution\":{},\"rationale\":\"x\"}") [:operations 0 :op]))))

(deftest journal-and-every-graph-replay-and-tampering-is-detected
  (let [parent (.toFile (Files/createTempDirectory "deliberation-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        output (str (io/file parent "run"))]
    (try
      (binding [*out* (java.io.StringWriter.)]
        (main/execute! manifest output (main/mock-call)))
      (is (:verified? (main/verify! output)))
      (is (thrown? Exception (main/execute! manifest output (main/mock-call))))
      (let [file (io/file output "000001.edn")
            record (codec/read-file file)]
        (spit file (pr-str (assoc-in record [:payload :turn] 9000)))
        (is (thrown? Exception (journal/read! output))))
      (finally (doseq [file (reverse (file-seq parent))] (.delete file))))))

(deftest invalid-manifests-fail-before-calling-a-provider
  (doseq [bad [(assoc manifest :mode :G0) (assoc manifest :replicates 0)
               (assoc manifest :conditions [:workspace :workspace])
               (assoc-in manifest [:tiers 0 :activations] 1)]]
    (is (thrown? Exception (main/validate-manifest! bad)))))
