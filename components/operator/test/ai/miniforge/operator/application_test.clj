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

(ns ai.miniforge.operator.application-test
  "Intervention application layer (Phase D D-3) tests, including the
   Phase D verification-bar sequence: injected request file → consumer
   → applier → control-state flip → observable
   proposed→approved→dispatched→applied→verified event trail, and the
   no-live-runner failure path."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.application :as application]
   [ai.miniforge.operator.consumer :as consumer]
   [ai.miniforge.operator.intervention :as intervention]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Helpers

(def ^:const golden-pause-target-id
  "The workflow target id stamped by the Rust golden-fixture generator."
  "00000000-0000-4000-8000-0000000000aa")

(defn- find-workspace-root
  []
  (loop [dir (io/file (System/getProperty "user.dir")) hops 0]
    (cond
      (.exists (io/file dir "workspace.edn")) dir
      (or (nil? (.getParentFile dir)) (>= hops 8))
      (throw (ex-info "workspace.edn not found" {}))
      :else (recur (.getParentFile dir) (inc hops)))))

(defn- temp-events-dir
  ^java.io.File []
  (.toFile (Files/createTempDirectory "operator-application-test"
                                      (make-array FileAttribute 0))))

(defn- stage-golden-pause!
  [events-dir]
  (let [target (io/file events-dir "operator" "pause.transit.json")]
    (io/make-parents target)
    (spit target
          (slurp (io/file (find-workspace-root)
                          "contracts" "operator-events" "golden"
                          "pause.transit.json")
                 :encoding "UTF-8")
          :encoding "UTF-8")))

(defn- memory-stream []
  (es/create-event-stream {:sinks []}))

(defn- approved
  "Build an :approved intervention of `type` against `target-id`."
  [type target-id]
  (:intervention
   (intervention/approve
    (intervention/create-intervention
     {:intervention/type type
      :intervention/target-id target-id
      :intervention/requested-by "test@example.invalid"
      :intervention/request-source :tui}))))

(defn- state-trail
  [stream]
  (->> (es/get-events stream)
       (filter #(= consumer/state-changed-event-type (:event/type %)))
       (mapv :intervention/state)))

(defn- with-runner
  "Register a control-state for a fresh workflow id, run `f`, always
   deregister. Returns [workflow-id control-state result]."
  [f]
  (let [wid (str (random-uuid))
        cs (es/create-control-state)]
    (application/register-runner! wid {:control-state cs})
    (try
      [wid cs (f wid cs)]
      (finally
        (application/deregister-runner! wid)))))

;------------------------------------------------------------------------------ Phase D verification bar — request file → paused workflow

(deftest injected-request-file-pauses-the-registered-runner
  (let [events-dir (temp-events-dir)
        stream (memory-stream)
        cs (es/create-control-state)]
    (application/register-runner! golden-pause-target-id {:control-state cs})
    (try
      (stage-golden-pause! events-dir)
      (is (= {:routed 1 :skipped 0 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir
                                      :stream stream
                                      :apply! application/apply-intervention!})))
      (is (true? (es/paused? cs)) "the runner's control-state actually flipped")
      (is (= [:approved :dispatched :applied :verified] (state-trail stream))
          "the full lifecycle trail is observable on the stream")
      (finally
        (application/deregister-runner! golden-pause-target-id)))))

(deftest no-live-runner-fails-typed
  (let [events-dir (temp-events-dir)
        stream (memory-stream)]
    (stage-golden-pause! events-dir)
    ;; Nothing registered for the fixture's target id.
    (consumer/consume-pass! {:events-dir events-dir
                             :stream stream
                             :apply! application/apply-intervention!})
    (is (= [:approved :dispatched :failed] (state-trail stream)))
    (let [failed (->> (es/get-events stream)
                      (filter #(= :failed (:intervention/state %)))
                      first)]
      (is (= :no-live-runner (:intervention/reason failed))
          "the reason keyword travels verbatim — the UI renders it"))))

;------------------------------------------------------------------------------ Control-state verbs verify by readback

(deftest resume-flips-paused-off
  (with-runner
    (fn [wid cs]
      (es/pause! cs)
      (let [stream (memory-stream)
            result (application/apply-intervention! stream (approved :resume wid))]
        (is (false? (es/paused? cs)))
        (is (= :verified (:intervention/state result)))))))

(deftest cancel-sets-stopped
  (with-runner
    (fn [wid cs]
      (let [stream (memory-stream)
            result (application/apply-intervention! stream (approved :cancel wid))]
        (is (true? (es/cancelled? cs)))
        (is (= :verified (:intervention/state result)))))))

(deftest register-runner-rejects-invalid-control-state
  (testing "runner wiring fails early instead of becoming an application error"
    (doseq [handles [{} {:control-state :not-an-atom} :not-a-map]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"requires an atom :control-state"
           (application/register-runner! (random-uuid) handles))))))

;------------------------------------------------------------------------------ No-effect and unwired verbs

(deftest acknowledge-verifies-without-a-runner
  (let [stream (memory-stream)
        result (application/apply-intervention!
                stream (approved :acknowledge (str (random-uuid))))]
    (is (= :verified (:intervention/state result)))
    (is (= [:dispatched :applied :verified] (state-trail stream)))))

(deftest retry-fails-not-implemented
  (with-runner
    (fn [wid _cs]
      (let [stream (memory-stream)
            result (application/apply-intervention! stream (approved :retry wid))]
        (is (= :failed (:intervention/state result)))
        (is (= :not-implemented (:intervention/reason result)))))))

(deftest safe-mode-without-manager-fails-typed
  (with-runner
    (fn [wid _cs]
      (let [stream (memory-stream)
            result (application/apply-intervention!
                    stream (approved :force-safe-mode wid))]
        (is (= :failed (:intervention/state result)))
        (is (= :no-degradation-manager (:intervention/reason result)))))))

;------------------------------------------------------------------------------ Registry

(deftest registry-round-trip
  (let [wid (str (random-uuid))]
    (is (false? (application/live-runner? wid)))
    (application/register-runner! wid {:control-state (es/create-control-state)})
    (is (true? (application/live-runner? wid)))
    (application/deregister-runner! wid)
    (application/deregister-runner! wid)
    (is (false? (application/live-runner? wid)))))
