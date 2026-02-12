;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.state.core
  "Pure utilities and state atom creation.")

;------------------------------------------------------------------------------ Layer 0
;; Pure utilities

(defn ttl-memoize
  "Memoize a function with TTL in milliseconds.
  Cache is keyed by all arguments."
  [ttl-ms f]
  (let [cache (atom {})]
    (fn [& args]
      (let [now (System/currentTimeMillis)
            cached (get @cache args)]
        (if (and cached (< (- now (:time cached)) ttl-ms))
          (:value cached)
          (let [result (apply f args)]
            (swap! cache assoc args {:value result :time now})
            result))))))

(defn safe-call
  "Safely call a function from a namespace, returning default on error."
  [ns-sym fn-sym & args]
  (try
    (when-let [ns (find-ns ns-sym)]
      (when-let [f (ns-resolve ns fn-sym)]
        (apply f args)))
    (catch Exception e
      (println "Error calling" fn-sym ":" (.getMessage e))
      nil)))

;------------------------------------------------------------------------------ Layer 1
;; State atom creation and access

(defn create-state
  "Create dashboard state atom."
  [opts]
  (atom (merge {:event-stream nil
                :pr-train-manager nil
                :repo-dag-manager nil
                :workflow-commands {}
                :archived-workflows (atom {})
                :archive-loading? (atom true)
                :start-time (System/currentTimeMillis)}
               opts)))

(defn get-uptime
  "Get server uptime in milliseconds."
  [state]
  (- (System/currentTimeMillis) (:start-time @state)))
