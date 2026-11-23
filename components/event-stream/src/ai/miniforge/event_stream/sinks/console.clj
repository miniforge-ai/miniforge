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
(ns ai.miniforge.event-stream.sinks.console
  "Sinks that print events to standard output or standard error."
  (:require
   [cheshire.core :as json]
   [clojure.pprint :as pprint]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} stdout-sink
  "Create a stdout sink that prints events to standard output.

   Useful for containers where stdout is collected by log aggregators.

   Arguments:
     opts - Map with optional:
       :format - :edn or :json (default :edn)
       :compact - Boolean for compact output (default false)

   Returns: Sink function (fn [event] -> nil)"
  [& [opts]]
  (let [format (:format opts :edn)
        compact? (:compact opts false)]
    (fn [event]
      (try
        (let [output (case format
                       :edn (if compact?
                              (pr-str event)
                              (with-out-str (pprint/pprint event)))
                       :json (str (json/generate-string event {:pretty (not compact?)}))
                       (pr-str event))]
          (println output)
          (flush))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "WARNING: Event sink write failed: " (ex-message e)))))))))

(defn ^{:stratum 0} stderr-sink
  "Create a stderr sink that prints events to standard error.

   Useful for error/warning events that should go to stderr stream.

   Arguments:
     opts - Map with optional:
       :filter - Predicate (fn [event] -> bool) to filter events
       :format - :edn or :json (default :edn)

   Returns: Sink function (fn [event] -> nil)"
  [& [opts]]
  (let [filter-fn (:filter opts (constantly true))
        format (:format opts :edn)]
    (fn [event]
      (when (filter-fn event)
        (try
          (let [output (case format
                         :edn (pr-str event)
                         :json (json/generate-string event)
                         (pr-str event))]
            (binding [*out* *err*]
              (println output)))
          (catch Exception e
            (binding [*out* *err*]
              (println (str "WARNING: Event sink write failed: " (ex-message e))))))))))
