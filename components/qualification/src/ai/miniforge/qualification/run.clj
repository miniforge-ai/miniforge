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
(ns ai.miniforge.qualification.run
  "Run one gate's scenario and write its report. The scenario is caller
   code, so this is the boundary where its throw or malformed output
   becomes a recorded failure: every criterion is then unrun, and the
   verdict is never a pass."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.qualification.build :as build]
   [ai.miniforge.qualification.messages :as msg]
   [ai.miniforge.qualification.schema :as schema]
   [ai.miniforge.qualification.verdict :as verdict]
   [ai.miniforge.qualification.vocabulary :as vocab]
   [ai.miniforge.qualification.writer :as writer]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me]
   [slingshot.slingshot :refer [try+]])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} thrown-failure
  "The failure for a scenario that threw `throwable`."
  [throwable]
  {:error/kind    :scenario/threw
   :error/message (msg/t :criterion/scenario-threw
                         {:type    (.getName (class throwable))
                          :message (or (ex-message throwable) "")})})

(defn- ^{:stratum 0} report-rows
  "Rows for a run: unrun for a failed scenario, else the joined results."
  [gate {:keys [output failure]}]
  (if failure
    (verdict/unrun-rows gate (:error/message failure))
    (verdict/rows gate (:scenario/results output))))

(defn- ^{:stratum 0} now [] (str (Instant/now)))

(defn ^{:stratum 0} exit-code
  "0 when the run passed and its report was written, 2 for a gate that is
   not a valid gate, 1 otherwise."
  [{:keys [report paths invalid-gate]}]
  (cond
    invalid-gate                                               2
    (and paths (= :verdict/pass (:report/verdict report)))     0
    :else                                                      1))

(defn ^{:stratum 0} summary-lines
  "Human-readable lines for a run result."
  [{:keys [report paths write-failure invalid-gate]}]
  (if invalid-gate
    [(msg/t :summary/invalid-gate {:problems invalid-gate})]
    (let [rows   (:report/criteria report)
          passed (count (filter #(= :status/pass (:criterion/status %)) rows))]
      (-> [(msg/t :summary/verdict {:gate    (:report/gate-id report)
                                    :verdict (name (:report/verdict report))
                                    :passed  passed
                                    :total   (count rows)})]
          (into (map #(msg/t :summary/row {:status      (name (:criterion/status %))
                                           :id          (:criterion/id %)
                                           :description (:criterion/description %)})
                     rows))
          (conj (if paths
                  (msg/t :summary/wrote {:path (:json paths)})
                  (msg/t :summary/write-failed {:message write-failure})))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} scenario-outcome
  "{:output o} for usable scenario output, else {:failure f}."
  [gate scenario]
  (try+
   (let [output   (scenario)
         problems (verdict/output-problems gate output)]
     (if problems
       {:failure {:error/kind    :scenario/invalid-output
                  :error/message (msg/t :criterion/scenario-invalid
                                        {:problems (str/join "; " problems)})}}
       {:output output}))
   (catch map? _
     {:failure (thrown-failure (:throwable &throw-context))})
   (catch Exception e
     {:failure (thrown-failure e)})
   (catch AssertionError e
     {:failure (thrown-failure e)})))

(defn- ^{:stratum 1} assemble-report
  [gate {:keys [output failure] :as outcome} {:keys [command root started-at]}]
  (let [rows (report-rows gate outcome)]
    (cond-> {:report/version      vocab/report-version
             :report/gate-id      (:gate/id gate)
             :report/gate-title   (:gate/title gate)
             :report/command      (vec command)
             :report/build        (build/build-identity root)
             :report/fixture-hash (when output (content-hash/content-hash (:scenario/fixtures output)))
             :report/config-hash  (when output (content-hash/content-hash (:scenario/config output)))
             :report/started-at   started-at
             :report/finished-at  (now)
             :report/criteria     rows
             :report/verdict      (verdict/verdict rows)}
      (:gate/issue gate) (assoc :report/issue (:gate/issue gate))
      failure            (assoc :report/failure failure))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-gate!
  "Run `scenario`, a no-argument fn returning a ScenarioOutput, for `gate`,
   and write the report under `out-dir`. `command` is the argv that repeats
   the run; `root` is the workspace whose build is recorded.

   Returns {:report r :paths {:edn :json}}, or {:report r :write-failure
   message} when the report could not be written, or {:invalid-gate
   problems} when `gate` is not a valid gate."
  [gate scenario {:keys [out-dir command root] :or {root "."}}]
  (if-not (m/validate schema/Gate gate)
    {:invalid-gate (pr-str (me/humanize (m/explain schema/Gate gate)))}
    (let [started-at (now)
          outcome    (scenario-outcome gate scenario)
          report     (assemble-report gate outcome {:command    command
                                                    :root       root
                                                    :started-at started-at})]
      (try+
       {:report report :paths (writer/write-report! out-dir report)}
       (catch java.io.IOException e
         {:report report :write-failure (ex-message e)})))))

(comment
  (run-gate! {:gate/id :fixture/gate :gate/title "Fixture"
              :gate/criteria [{:criterion/id :c :criterion/description "C"}]}
             (fn [] {:scenario/fixtures {} :scenario/config {}
                     :scenario/results [{:criterion/id :c :criterion/status :status/pass}]})
             {:out-dir "target/qualification" :command ["bb" "qualify" "fixture/gate"]}))
