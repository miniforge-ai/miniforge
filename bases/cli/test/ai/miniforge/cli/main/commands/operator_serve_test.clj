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
