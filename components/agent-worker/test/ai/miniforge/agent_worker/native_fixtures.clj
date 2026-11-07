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
(ns ai.miniforge.agent-worker.native-fixtures
  "A fake environment that answers a native worker's shell lines: tools and
   credentials present unless a check is named missing, and a launch that
   prints a pid."
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-worker.fixtures :as fx]
   [ai.miniforge.agent-worker.interface :as worker]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} completed-exit
  "What the status script prints once a turn has exited cleanly."
  "0\n")

(def ^{:stratum 0} harness-pid "4242")

(def ^{:stratum 0} turn-dir "/work/.miniforge/worker/1-1")

(def ^{:stratum 0} ok {:exec/exit 0 :exec/stdout "" :exec/stderr ""})

(def ^{:stratum 0} not-found {:exec/exit 1 :exec/stdout "" :exec/stderr ""})

(defn ^{:stratum 0} script-of [command] (last (:exec/argv command)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} write-stream!
  [w text]
  (env/write-file! (:ec w) (:acting w) (:lease w) (str turn-dir "/out.jsonl") text))

(defn ^{:stratum 1} respond
  [missing command]
  (let [script (script-of command)]
    (cond
      (str/starts-with? script "command -v") (if (= :cli missing) not-found ok)
      (str/starts-with? script "test -n") (if (= :credential missing) not-found ok)
      (str/includes? script "& echo $!") (assoc ok :exec/stdout (str harness-pid "\n"))
      (str/starts-with? script "if [ -f") (assoc ok :exec/stdout completed-exit)
      :else ok)))

(defn ^{:stratum 1} launched-scripts
  [w]
  (filterv #(str/includes? % "& echo $!") (map script-of (env/fake-commands (:provider w) (:namespace w)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} native-world
  [kind missing]
  (fx/world {kind (worker/native-worker kind)} {} {:fake/exec-fn (partial respond missing)}))
