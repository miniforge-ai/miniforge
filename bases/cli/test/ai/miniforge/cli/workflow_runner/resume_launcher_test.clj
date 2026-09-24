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
(ns ai.miniforge.cli.workflow-runner.resume-launcher-test
  (:require
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.workflow-runner.resume-launcher :as sut]
   [ai.miniforge.cli.workflow-runner.resume-records :as records]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.interface :as operator]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-temp-home
  [f]
  (let [home (.toFile (java.nio.file.Files/createTempDirectory
                       "mf-launcher" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs [es/default-events-dir (constantly (io/file home "events"))
                  app-config/logs-dir (constantly (str (io/file home "logs")))]
      (f))))

(defn- ^{:stratum 0} retry-plan
  [workflow-id]
  {:resume/workflow-id workflow-id :resume/intervention-id (random-uuid)})

(defn- ^{:stratum 0} deps
  "Launcher deps whose spawn records its call and reports this JVM's pid
   (a live process)."
  [{:keys [spawned] :or {spawned (atom [])}}]
  {:command ["mf"]
   :spawn! (fn [argv _log-file dir] (swap! spawned conj [argv dir]) (.pid (java.lang.ProcessHandle/current)))})

(defn- ^{:stratum 0} failure-code
  [result]
  (get-in result [:anomaly/data :failure/code]))

(deftest ^{:stratum 0} self-command-test
  (testing "MINIFORGE_CMD wins"
    (is (= ["/opt/mf"] (sut/self-command "/opt/mf" "bb" ["serve"] ["serve"]))))
  (testing "this process's command line minus the CLI arguments it was given"
    (is (= ["/usr/bin/bb" "--jar" "mf.jar" "-m" "ai.miniforge.cli.main"]
           (sut/self-command nil "/usr/bin/bb"
                             ["--jar" "mf.jar" "-m" "ai.miniforge.cli.main" "operator" "serve"]
                             '("operator" "serve")))))
  (testing "no command is guessed when the arguments cannot be split"
    (is (nil? (sut/self-command nil "bb" ["a" "b"] '("c"))))
    (is (nil? (sut/self-command nil "bb" ["a"] nil)))))

(deftest ^{:stratum 0} resume-argv-test
  (let [intervention-id (random-uuid)
        plan {:resume/workflow-id "wf-1" :resume/intervention-id intervention-id}]
    (is (= ["mf" "resume" "wf-1" "--run-id" "r1" "--correlation-id" (str intervention-id)]
           (sut/resume-argv ["mf"] plan "r1")))
    (is (= ["--from-phase" "implement"]
           (take-last 2 (sut/resume-argv ["mf"] (assoc plan :resume/from-phase :implement) "r1"))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-retry-is-launched-once-where-the-run-started-test
  (with-temp-home
    (fn []
      (let [workflow-id (str (random-uuid))
            plan (retry-plan workflow-id)
            spawned (atom [])]
        (records/record-origin! workflow-id)
        (let [launch (sut/launch! (deps {:spawned spawned}) plan)]
          (is (= (System/getProperty "user.dir") (second (first @spawned)))
              "the child runs in the directory the run was started from")
          (is (= (.pid (java.lang.ProcessHandle/current)) (:resume/pid launch)))
          (testing "a redelivered intervention gets its launch back, not a second child"
            (is (= launch (sut/launch! (deps {:spawned spawned}) plan)))
            (is (= 1 (count @spawned))))
          (testing "another retry of the workflow is refused while that launch runs"
            (is (= :resume-in-flight (failure-code (sut/launch! (deps {:spawned spawned})
                                                                (retry-plan workflow-id)))))
            (is (= 1 (count @spawned)))))))))

(deftest ^{:stratum 1} a-retry-is-refused-over-a-live-run-or-an-unknown-origin-test
  (with-temp-home
    (fn []
      (let [workflow-id (str (random-uuid))]
        (is (= :resume-origin-unknown (failure-code (sut/launch! (deps {}) (retry-plan workflow-id)))))
        (records/record-origin! workflow-id)
        (with-redefs [operator/live-runner? (constantly true)]
          (is (= :resume-target-live
                 (failure-code (sut/launch! (deps {}) (retry-plan workflow-id))))))))))

(deftest ^{:stratum 1} the-child-is-detached-and-gets-its-argv-verbatim-test
  (with-temp-home
    (fn []
      (let [log (io/file (app-config/logs-dir) "spawn.log")
            echo-pid (#'sut/spawn-detached! ["/bin/echo" "two words" "it's \"quoted\""] log "/")
            read-log #(do (Thread/sleep 10) (when (.exists log) (slurp log)))
            sleeper (#'sut/spawn-detached! ["/bin/sleep" "5"] (io/file (app-config/logs-dir) "s.log") "/")]
        (is (pos-int? echo-pid))
        (is (some #{"two words it's \"quoted\"\n"} (repeatedly 200 read-log)))
        (.waitFor (.exec (Runtime/getRuntime) (into-array String ["kill" "-HUP" (str sleeper)])))
        (Thread/sleep 200)
        (is (records/process-running? sleeper nil) "a hangup does not stop it")
        (records/destroy-process! sleeper)))))
