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

(ns ai.miniforge.event-stream.operator-requests-test
  "Writer side of the operator control channel (Phase D D-4).

   The vendored fixture under contracts/operator-events/golden is the
   cross-language contract — Rust-produced, consumed by the Clojure
   operator consumer. These tests pin that the Clojure writer emits the
   SAME field set, so the two producers cannot drift into two
   encodings."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.operator-requests :as sut]
   [ai.miniforge.event-stream.reader :as reader]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(def ^:const max-workspace-walk-hops
  "Upper bound on parent-directory hops when locating workspace.edn."
  8)

(defn- find-workspace-root
  []
  (loop [dir (io/file (System/getProperty "user.dir")) hops 0]
    (cond
      (.exists (io/file dir "workspace.edn")) dir
      (or (nil? (.getParentFile dir)) (>= hops max-workspace-walk-hops))
      (throw (ex-info "workspace.edn not found walking up from user.dir"
                      {:user-dir (System/getProperty "user.dir")}))
      :else (recur (.getParentFile dir) (inc hops)))))

(defn- temp-events-dir
  ^java.io.File []
  (.toFile (Files/createTempDirectory "operator-requests-test"
                                      (make-array FileAttribute 0))))

(defn- operator-files
  [events-dir]
  (let [d (io/file events-dir "operator")]
    (vec (or (.listFiles d) []))))

(defn- pause-request
  [events-dir target-id]
  {:intervention/type :pause
   :intervention/target-type :workflow
   :intervention/target-id target-id
   :intervention/requested-by "operator@example.invalid"
   :intervention/request-source :tui
   :events-dir events-dir})

;------------------------------------------------------------------------------ Layer 1
;; Wire shape

(deftest request-carries-the-golden-fixture-field-set
  (testing "the Clojure writer emits the same fields as the Rust client"
    (let [golden (reader/read-event-file
                  (io/file (find-workspace-root)
                           "contracts" "operator-events" "golden"
                           "pause.transit.json"))
          written (sut/intervention-request-event
                   (dissoc (pause-request nil (random-uuid)) :events-dir))]
      (is (= :supervisory/intervention-requested (:event/type written)))
      ;; `:intervention/approval-required?` is optional on the schema and
      ;; server-derived by the consumer, so the writer omits it; every
      ;; other field the contract fixture carries must be present.
      (is (empty? (remove #(contains? written %)
                          (disj (set (keys golden))
                                :intervention/approval-required?)))))))

(deftest workflow-targets-route-on-workflow-id
  (testing "a workflow-targeted request stamps :workflow/id from the target"
    (let [wid (random-uuid)
          event (sut/intervention-request-event
                 (dissoc (pause-request nil wid) :events-dir))]
      (is (= wid (:workflow/id event)))
      (is (= (str wid) (:intervention/target-id event)))))
  (testing "a process-global target carries no :workflow/id"
    (let [event (sut/intervention-request-event
                 {:intervention/type :force-safe-mode
                  :intervention/target-type :degradation
                  :intervention/target-id "process"
                  :intervention/requested-by "operator@example.invalid"
                  :intervention/request-source :dashboard})]
      (is (nil? (:workflow/id event))))))

(deftest missing-fields-are-an-anomaly-not-a-write
  (testing "an incomplete request is rejected at the call site"
    (let [events-dir (temp-events-dir)
          result (sut/request-intervention!
                  {:intervention/type :pause
                   :intervention/target-type :workflow
                   :events-dir events-dir})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= #{:intervention/target-id
               :intervention/requested-by
               :intervention/request-source}
             (set (get-in result [:anomaly/data :missing-fields]))))
      (is (empty? (operator-files events-dir))))))

;------------------------------------------------------------------------------ Layer 2
;; Publication

(deftest request-lands-one-readable-file-in-the-operator-dir
  (testing "the written file parses back through the event reader"
    (let [events-dir (temp-events-dir)
          wid (random-uuid)
          written (sut/request-intervention! (pause-request events-dir wid))
          files (operator-files events-dir)]
      (is (= 1 (count files)) "exactly one request file, no leftover .tmp")
      (is (str/ends-with? (.getName (first files)) ".json"))
      (is (= (str (first files)) (:source/file written)))
      (let [parsed (reader/read-event-file (first files))]
        (is (= :supervisory/intervention-requested (:event/type parsed)))
        (is (= :pause (:intervention/type parsed)))
        (is (= (str wid) (:intervention/target-id parsed)))
        (is (= :tui (:intervention/request-source parsed)))))))

(deftest each-request-gets-its-own-intervention-id
  (testing "two gestures are two auditable interventions, not one"
    (let [events-dir (temp-events-dir)
          wid (random-uuid)
          a (sut/request-intervention! (pause-request events-dir wid))
          b (sut/request-intervention! (pause-request events-dir wid))]
      (is (not= (:intervention/id a) (:intervention/id b)))
      (is (= 2 (count (operator-files events-dir)))))))
