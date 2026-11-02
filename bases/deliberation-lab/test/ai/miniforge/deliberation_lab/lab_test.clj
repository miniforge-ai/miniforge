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
(ns ai.miniforge.deliberation-lab.lab-test
  "Integration tests: a run's contract is a durable journal, so these write
   a temporary directory. The mock call makes no model calls."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.interface :as harness]
   [ai.miniforge.deliberation-lab.adapter :as adapter]
   [ai.miniforge.deliberation-lab.lab :as lab]
   [ai.miniforge.deliberation-lab.main :as main]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private manifest
  {:schema-version 1 :mode :development :model "test-model"
   :conditions [:workspace :workspace-ablated] :replicates 1 :max-input-bytes 48000 :adapter {:call-cap-usd 0.2}
   :price-table {:input-per-million 3 :output-per-million 15}
   :tiers [{:id :tiny :activations 4 :max-normalized-usd 1 :wall-seconds 30}]})

(defn- ^{:stratum 0} with-output [f]
  (let [parent (.toFile (Files/createTempDirectory "deliberation-lab-" (make-array FileAttribute 0)))]
    (try (f (str (io/file parent "run")))
         (finally (doseq [file (reverse (file-seq parent))] (io/delete-file file true))))))

(defn- ^{:stratum 0} smoke! [output]
  (lab/execute! manifest output (fn [_] (lab/mock-call (harness/tasks)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1 :integration true} a-smoke-run-journals-and-replays
  (with-output
    (fn [output]
      (let [summary (smoke! output)]
        (is (= :not-evaluated (:G0 summary)))
        (is (true? (:verified? (lab/verify! output))))
        (is (.exists (io/file output "report.md")))
        (is (.exists (io/file output "journal" "000000.edn")))
        (testing "a run never reuses its directory"
          (is (anomaly/anomaly? (smoke! output))))
        (testing "an edited record breaks verification"
          (spit (io/file output "journal" "000001.edn") (pr-str {:sequence 1 :payload {:turn 9000}}))
          (is (anomaly/anomaly? (lab/verify! output))))))))

(deftest ^{:stratum 1} an-invalid-manifest-is-refused-before-any-call
  (doseq [invalid [(assoc manifest :mode :G0) 42 []]]
    (let [built (atom 0)
          result (lab/execute! invalid "unused" (fn [_] (swap! built inc) nil))]
      (is (anomaly/anomaly? result))
      (is (zero? @built) "no call is built for a refused manifest"))))

(deftest ^{:stratum 1} a-response-without-provider-usage-is-a-failure
  (let [ok {:success true :content "not json"}]
    (is (anomaly/anomaly? (adapter/call-result (assoc ok :usage {:input-tokens 0 :output-tokens 0}))))
    (is (anomaly/anomaly? (adapter/call-result ok)))
    (is (= {:input 12 :output 3 :provider-usd 0}
           (:usage (adapter/call-result (assoc ok :usage {:input-tokens 10 :output-tokens 3
                                                          :cache-read-input-tokens 2})))))))

(deftest ^{:stratum 1} model-output-cannot-forge-metering
  (testing "an envelope carrying usage is invalid, not trusted"
    (is (:invalid? (adapter/decode
                    "{\"operations\":[],\"inspect\":[],\"solution\":{},\"rationale\":\"x\",\"usage\":{}}"))))
  (testing "a valid envelope decodes operation enums"
    (is (= :assert-claim
           (get-in (adapter/decode
                    "{\"operations\":[{\"op\":\"assert-claim\"}],\"inspect\":[],\"solution\":{},\"rationale\":\"x\"}")
                   [:operations 0 :op]))))
  (testing "unparseable text is an invalid payload"
    (is (:invalid? (adapter/decode "not json")))))

(deftest ^{:stratum 1} a-wrong-argument-count-is-a-usage-error
  (is (nil? (main/dispatch ["smoke" "manifest.edn"])))
  (is (nil? (main/dispatch ["verify"])))
  (is (nil? (main/dispatch ["unknown" "x"]))))
