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
(ns ai.miniforge.cli.main.commands.webhook-triggers
  "Validation of the trigger-rule file `mf webhook serve` runs."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} intake-event-types
  "Event types the webhook intake publishes; a rule :on anything else
   would never fire."
  #{:pr/merged :pr/opened :pr/ci-failed})

(defn ^{:stratum 0} valid-pattern?
  "True when `pattern` is absent or compiles as a regex."
  [pattern]
  (or (nil? pattern)
      (try+ (boolean (re-pattern pattern)) (catch Exception _ false))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} valid-rule?
  "True when a trigger rule is :on an event type the intake publishes,
   names the :repo it applies to (a rule without one would act on every
   repository delivering to the hook), has a :branch-pattern that
   compiles if any, and names the :run :workflow-id."
  [{:keys [on repo branch-pattern run]}]
  (boolean (and (contains? intake-event-types on)
                (string? repo)
                (not (str/blank? repo))
                (valid-pattern? branch-pattern)
                (some? (:workflow-id run)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} trigger-config
  "The {:triggers [...]} map in `text`, or nil unless it has rules and
   every one is `valid-rule?`."
  [text]
  (let [config (try+ (edn/read-string (str text)) (catch Exception _ nil))
        triggers (:triggers config)]
    (when (and (sequential? triggers) (seq triggers) (every? valid-rule? triggers))
      config)))
