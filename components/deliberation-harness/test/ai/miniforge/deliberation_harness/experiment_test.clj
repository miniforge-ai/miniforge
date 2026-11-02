;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.deliberation-harness.experiment-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.experiment :as experiment]
   [ai.miniforge.deliberation-harness.fixtures :as fixtures]
   [ai.miniforge.deliberation-harness.report :as report]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private tier
  "A four-turn allowance: one full proposer/skeptic/synthesizer cycle plus
   the closing turn."
  {:id :tiny :activations 4 :max-normalized-usd 1 :wall-seconds 30})

(def ^{:stratum 0} ^:private manifest
  "A minimal development manifest pairing the shared and ablated arms."
  {:schema-version 1 :mode :development :model "test-model"
   :conditions [:workspace :workspace-ablated] :replicates 1 :max-input-bytes 48000
   :price-table {:input-per-million 3 :output-per-million 15}
   :tiers [tier]})

(def ^{:stratum 0} ^:private peer-marker
  "Text only the proposer writes, so its presence elsewhere shows relay."
  "peer-exclusive-observation")

(def ^{:stratum 0} ^:private call-usage
  "Per-call usage: 0.0006 normalized USD under the manifest's price table."
  {:input 100 :output 20 :provider-usd 0.001})

(defn- ^{:stratum 0} task [] (first (fixtures/tasks)))

(defn- ^{:stratum 0} scripted-call
  "A call that records each request and answers with the reference solution;
   only the proposer's claim carries the peer marker."
  [seen]
  (let [counter (atom 0)]
    (fn [request]
      (swap! seen conj request)
      (let [n (swap! counter inc)
            statement (if (= :proposer (:role request)) peer-marker "own-observation")]
        {:payload {:operations [{:op :assert-claim
                                 :creates [{:id (str "claim-" n) :type :claim
                                            :statement statement}]}]
                   :inspect (:available-experiments request)
                   :solution (:reference (task))
                   :rationale "SYNTHETIC test record"}
         :usage call-usage}))))

(defn- ^{:stratum 0} answering
  "A call answering the reference with `operations` and `inspect` computed
   from each request."
  [operations inspect]
  (fn [request]
    {:payload {:operations (operations request) :inspect (inspect request)
               :solution (:reference (task)) :rationale "SYNTHETIC test record"}
     :usage call-usage}))

(defn- ^{:stratum 0} run [condition call & {:as tier-overrides}]
  (experiment/run-cell manifest (task) condition (merge tier tier-overrides) 0 call
                       (constantly "hash")))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} shared-and-blind-differ-only-in-information
  (let [shared (atom []) blind (atom [])
        shared-result (run :workspace (scripted-call shared))
        blind-result (run :workspace-ablated (scripted-call blind))]
    (testing "identical allowance, schedule and closing turn"
      (is (= 4 (:activations shared-result) (:activations blind-result)))
      (is (= (map :role @shared) (map :role @blind)))
      (is (= 1 (count (filter :closing? @shared)))))
    (testing "both complete and score"
      (is (= 1 (get-in shared-result [:score :success])))
      (is (= 1 (get-in blind-result [:score :success]))))
    (testing "only the shared board relays a peer's contribution"
      (is (str/includes? (pr-str (last @shared)) peer-marker))
      (is (not (str/includes? (pr-str (last @blind)) peer-marker))))
    (testing "no request carries the sealed answer or runtime internals"
      (doseq [request (concat @shared @blind)
              forbidden [":reference" ":workspace/" ":object/version" ":object/activation"]]
        (is (not (str/includes? (pr-str request) forbidden)))))))

(deftest ^{:stratum 1} an-adapter-failure-is-a-scored-cell-without-retry
  (let [calls (atom 0)
        records (atom [])
        result (experiment/run-cell manifest (task) :workspace tier 0
                                    (fn [_] (swap! calls inc)
                                      (anomaly/anomaly :timeout "timed out" {}))
                                    #(do (swap! records conj %) "hash"))]
    (is (= 1 @calls))
    (is (= [:activation-started :activation :cell-completed] (mapv :kind @records)))
    (is (= :failed (:status result)))
    (is (:usage-unknown? result))
    (is (zero? (get-in result [:score :success])))))

(deftest ^{:stratum 1} an-over-budget-closing-call-cannot-succeed
  (let [result (run :workspace (scripted-call (atom [])) :activations 2
                    :max-normalized-usd 0.0009)]
    (is (= 2 (:activations result)))
    (is (= :budget-boundary (:status result)))
    (is (zero? (get-in result [:score :success])))))

(deftest ^{:stratum 1} a-journal-failure-stops-the-cell
  (let [failure (anomaly/anomaly :fault "disk" {})]
    (is (= failure (experiment/run-cell manifest (task) :workspace tier 0
                                        (scripted-call (atom [])) (constantly failure))))))

(deftest ^{:stratum 1} the-report-never-claims-a-gate
  (let [results [(run :workspace (scripted-call (atom [])))
                 (run :workspace-ablated (scripted-call (atom [])))]
        text (report/render manifest results)]
    (is (str/includes? text "G0: not evaluated"))
    (is (str/includes? text "- retry-contract/tiny/0: 0"))))

(deftest ^{:stratum 1} observations-are-credited-only-when-the-closing-turn-saw-them
  (let [late (run :workspace (answering (constantly [])
                                        #(when (:closing? %) (:available-experiments %))))
        early (run :workspace (answering (constantly []) :available-experiments))]
    (is (false? (get-in late [:score :evidence-complete?])))
    (is (zero? (get-in late [:score :success])))
    (is (= 1 (get-in early [:score :success])))))

(deftest ^{:stratum 1} a-model-object-cannot-squat-on-an-observation
  (let [squat (fn [request]
                (for [id (:available-experiments request)]
                  {:op :assert-claim :creates [{:id (str "evidence-" id) :type :claim
                                                :statement "squatting claim"}]}))
        result (run :workspace (answering squat :available-experiments))
        objects (vals (get-in result [:boards :shared :workspace/objects]))]
    (is (= 1 (get-in result [:score :success])))
    (testing "each observation supports its experiment through admission"
      (doseq [id (keys (:experiments (task)))]
        (is (seq (get-in result [:boards :shared :workspace/objects
                                 (str "experiment-" id) :object/links :supports])))))
    (is (some #(= :execution (get-in % [:object/attrs :source-class])) objects))))

(deftest ^{:stratum 1} a-model-cannot-forge-execution-evidence
  (let [id (first (keys (:experiments (task))))
        forge (fn [_]
                [{:op :attach-evidence :targets [(str "experiment-" id)]
                  :creates [{:id "forged" :type :evidence
                             :statement (get-in (task) [:experiments id])
                             :attrs {:source-class :execution :receipt (str "receipt-" id)}}]
                  :links {:supports ["forged"]}}])
        result (run :workspace (answering forge (constantly [])))]
    (is (nil? (get-in result [:boards :shared :workspace/objects "forged"])))
    (is (zero? (get-in result [:score :success])))))

(deftest ^{:stratum 1} an-unknown-condition-is-refused-before-any-call
  (let [calls (atom 0) records (atom 0)
        result (experiment/run-cell manifest (task) :workpace tier 0
                                    (fn [_] (swap! calls inc) nil)
                                    (fn [_] (swap! records inc) "hash"))]
    (is (= :invalid-input (:anomaly/type result)))
    (is (zero? @calls))
    (is (zero? @records))))

(deftest ^{:stratum 1} malformed-operations-are-rejected-not-thrown
  (let [result (run :workspace (answering (constantly 1) :available-experiments))]
    (is (= 4 (:activations result)))
    (is (= :completed (:status result)))))

(deftest ^{:stratum 1} a-malformed-response-is-a-failed-cell-not-an-exception
  (doseq [response [{:payload {:operations [] :inspect 1 :solution {} :rationale "r"} :usage call-usage}
                    {:payload {:operations [] :inspect [] :solution {} :rationale 7} :usage call-usage}
                    {:payload {:operations [] :inspect [] :solution {} :rationale "r"} :usage {:input "x"}}
                    nil]]
    (let [records (atom [])
          result (experiment/run-cell manifest (task) :workspace tier 0 (constantly response)
                                      #(do (swap! records conj %) "hash"))]
      (is (= :failed (:status result)))
      (is (:usage-unknown? result))
      (is (= :cell-completed (:kind (last @records)))))))
