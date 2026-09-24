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
(ns ai.miniforge.cli.main.commands.operator-serve-test
  (:require
   [ai.miniforge.cli.main.commands.operator-serve :as sut]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.event-stream.interface :as es]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-home
  []
  (.toFile (java.nio.file.Files/createTempDirectory
            "mf-operator-serve" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- ^{:stratum 0} eventually
  [pred]
  (some true? (repeatedly 500 #(do (Thread/sleep 10) (boolean (pred))))))

(def ^{:stratum 0} acknowledge-request
  (str "{\"~:event/type\":\"~:supervisory/intervention-requested\","
       "\"~:intervention/id\":\"~u" (random-uuid) "\","
       "\"~:intervention/type\":\"~:acknowledge\","
       "\"~:intervention/target-type\":\"~:attention\","
       "\"~:intervention/target-id\":\"attention-1\","
       "\"~:intervention/requested-by\":\"op@example.invalid\","
       "\"~:intervention/request-source\":\"~:tui\"}"))

(defn- ^{:stratum 0} intervention-states
  [stream]
  (keep :intervention/state (filter #(= :supervisory/intervention-state-changed (:event/type %))
                                    (es/get-events stream))))

(defn- ^{:stratum 0} with-fresh-process-control
  "Fresh control singletons for `f`, events under `events-dir`, streams
   without file sinks, and no exit hook left behind."
  [events-dir f]
  (let [context-state (var-get #'control/meta-loop-ctx)
        consumer-state (var-get #'control/operator-consumer-handle)
        originals [@context-state @consumer-state]
        create-stream es/create-event-stream]
    (reset! context-state nil)
    (reset! consumer-state nil)
    (try
      (with-redefs [es/default-events-dir (constantly events-dir)
                    es/create-event-stream (fn [& _] (create-stream {:sinks []}))
                    control/stop-at-exit! (constantly nil)]
        (f consumer-state))
      (finally
        (reset! context-state (first originals))
        (reset! consumer-state (second originals))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} serve-lifecycle-test
  (let [home (temp-home)
        discovery (io/file home sut/discovery-file-name)
        calls (atom [])
        release (promise)
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (with-redefs [control/start-process-control! #(swap! calls conj :start)
                  control/stop-process-control! #(swap! calls conj :stop)]
      (let [first-server (future (binding [*out* out] (sut/serve-cmd home #(deref release))))]
        (testing "start: the consumer runs, then one JSON ready line and the discovery file"
          (is (eventually #(str/includes? (str out) "ready")))
          (let [ready (json/parse-string (str/trim (str out)) true)]
            (is (true? (:ready ready)))
            (is (= (.pid (java.lang.ProcessHandle/current)) (:pid ready)))
            (is (= (dissoc ready :ready) (json/parse-string (slurp discovery) true))))
          (is (= [:start] @calls)))
        (testing "a second server for the same home is refused with the running pid"
          (is (= 1 (binding [*err* err] (sut/serve-cmd home #(throw (ex-info "ran" {}))))))
          (is (str/includes? (str err) (str (.pid (java.lang.ProcessHandle/current)))))
          (is (= [:start] @calls)))
        (testing "clean stop: consumer stopped, discovery file gone, lock released"
          (deliver release :stop)
          (is (= 0 (deref first-server 5000 :timeout)))
          (is (= [:start :stop] @calls))
          (is (not (.exists discovery)))
          (is (= 0 (binding [*out* (java.io.StringWriter.)]
                     (sut/serve-cmd home (constantly nil))))))))))

(deftest ^{:stratum 1} serve-consumes-with-its-real-consumer-test
  (let [home (temp-home)
        events-dir (io/file home "events")
        release (promise)]
    (with-fresh-process-control
      events-dir
      (fn [consumer-state]
        (let [server (future (binding [*out* (java.io.StringWriter.)]
                               (sut/serve-cmd home #(deref release))))]
          (is (eventually #(some? @consumer-state)))
          (spit (doto (io/file events-dir "operator" "ack.json") io/make-parents) acknowledge-request)
          (testing "an intervention written with no run active is carried to verified"
            (is (eventually #(= :verified (last (intervention-states
                                                 (:event-stream (control/meta-loop-context!)))))))
            (is (= [:approved :dispatched :applied :verified]
                   (intervention-states (:event-stream (control/meta-loop-context!))))))
          (testing "the same stop the shutdown hook runs stops the real consumer"
            (deliver release :stop)
            (is (= 0 (deref server 30000 :timeout)))
            (is (nil? @consumer-state))
            (is (not (.exists (io/file home sut/discovery-file-name))))))))))
