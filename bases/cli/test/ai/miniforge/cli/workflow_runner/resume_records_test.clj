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
(ns ai.miniforge.cli.workflow-runner.resume-records-test
  (:require
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.workflow-runner.resume-records :as sut]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.interface :as operator]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-temp-home
  [f]
  (let [home (.toFile (java.nio.file.Files/createTempDirectory
                       "mf-records" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs [es/default-events-dir (constantly (io/file home "events"))
                  app-config/logs-dir (constantly (str (io/file home "logs")))]
      (f))))

(def ^{:stratum 0} this-pid (.pid (java.lang.ProcessHandle/current)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} origin-test
  (with-temp-home
    (fn []
      (let [workflow-id (str (random-uuid))]
        (is (nil? (sut/recorded-origin workflow-id)) "unrecorded")
        (sut/record-origin! workflow-id)
        (is (= (System/getProperty "user.dir") (sut/recorded-origin workflow-id)))
        (testing "the recorded runner is a live target until it lets go"
          (is (sut/target-live? workflow-id))
          (sut/release-origin! workflow-id)
          (is (not (sut/target-live? workflow-id))))
        (testing "an archived run keeps its origin"
          (.renameTo (sut/run-dir workflow-id)
                     (doto (io/file (es/default-events-dir) "archived" workflow-id) io/make-parents))
          (is (= (System/getProperty "user.dir") (sut/recorded-origin workflow-id))))
        (spit (io/file (es/default-events-dir) "archived" workflow-id "origin.edn") (pr-str {:cwd "/no/such/dir"}))
        (is (nil? (sut/recorded-origin workflow-id)) "an origin that no longer exists is unknown")))))

(deftest ^{:stratum 1} a-launch-is-recorded-before-and-after-the-spawn-test
  (with-temp-home
    (fn []
      (let [workflow-id (str (random-uuid))
            intervention-id (random-uuid)
            at-spawn (atom nil)
            launch (sut/start! {:resume/workflow-id workflow-id :resume/intervention-id intervention-id}
                               (fn [_run-id _log] (reset! at-spawn (sut/launch-record workflow-id)) this-pid))]
        (is (= [(str intervention-id) nil] ((juxt :resume/intervention-id :resume/pid) @at-spawn)))
        (is (= launch (sut/launch-record workflow-id)))
        (is (sut/launch-running? launch 60000) "the recorded pid is alive and is that process")
        (is (not (sut/launch-running? (assoc launch :resume/pid-started "1970-01-01T00:00:00Z") 60000)))))))

(deftest ^{:stratum 1} a-launch-without-a-live-pid-test
  (with-temp-home
    (fn []
      (let [now (System/currentTimeMillis)
            dead-pid (let [p (.start (ProcessBuilder. ^java.util.List ["/usr/bin/true"]))] (.waitFor p) (.pid p))
            launch (sut/start! {:resume/workflow-id (str (random-uuid)) :resume/intervention-id (random-uuid)}
                               (constantly dead-pid))]
        (testing "no pid recorded: in flight until the start timeout has passed"
          (is (sut/launch-running? {:resume/launched-at-ms now} 60000))
          (is (not (sut/launch-running? {:resume/launched-at-ms (- now 61000)} 60000))))
        (testing "a child gone before it was recorded is recorded as exited, never running"
          (is (:resume/exited? launch))
          (is (not (sut/launch-running? launch 60000))))))))

(deftest ^{:stratum 1} a-live-target-test
  (with-temp-home
    (fn []
      (let [workflow-id (str (random-uuid))
            manifest! #(spit (doto (io/file (sut/run-dir workflow-id) "manifest.json") io/make-parents)
                             (json/generate-string {:status % :owner {:pid (str this-pid)}}))]
        (is (not (sut/target-live? workflow-id)))
        (with-redefs [operator/live-runner? (constantly true)]
          (is (sut/target-live? workflow-id) "a runner in this process"))
        (manifest! "active")
        (is (sut/target-live? workflow-id) "an active manifest whose owner is alive")
        (manifest! "completed")
        (is (not (sut/target-live? workflow-id)))))))

(deftest ^{:stratum 1} only-an-event-carrying-the-intervention-id-is-evidence-test
  (with-temp-home
    (fn []
      (let [run-id (random-uuid)
            intervention-id (random-uuid)
            since (System/currentTimeMillis)
            event! (fn [correlation-id]
                     (spit (doto (io/file (sut/run-dir run-id) (str (random-uuid) ".json")) io/make-parents)
                           (es/serialize-event {:event/id (random-uuid)
                                                :event/type :workflow/started
                                                :event/timestamp (java.util.Date.)
                                                :event/sequence-number 0
                                                :workflow/id run-id
                                                :workflow-run/correlation-id correlation-id})))]
        (event! (random-uuid))
        (is (not (sut/correlated-event? run-id intervention-id since)) "another child's event")
        (event! intervention-id)
        (is (sut/correlated-event? run-id intervention-id since))))))

(deftest ^{:stratum 1} an-unsettled-launch-is-pending-until-settled-test
  (with-temp-home
    (fn []
      (let [workflow-id (str (random-uuid))
            dispatched {:intervention/id (random-uuid) :intervention/state :dispatched}
            launch (sut/start! {:resume/workflow-id workflow-id
                                :resume/intervention-id (:intervention/id dispatched)
                                :resume/intervention dispatched}
                               (constantly this-pid))]
        (is (= [dispatched] (map :resume/intervention (sut/pending-launches))))
        (sut/settle! (assoc launch :resume/intervention-id "a later launch") {:intervention/state :failed})
        (is (= 1 (count (sut/pending-launches))) "settling another launch leaves this one pending")
        (sut/settle! launch {:intervention/state :verified})
        (is (empty? (sut/pending-launches)))
        (is (= :verified (:resume/settled (sut/launch-record workflow-id))))))))
