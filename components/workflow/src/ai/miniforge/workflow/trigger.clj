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

(ns ai.miniforge.workflow.trigger
  "Event-driven workflow triggers.

   Subscribes to event streams and fires workflows or chains
   when matching events occur."
  (:require [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.runner :as runner]
            [clojure.edn]))

;------------------------------------------------------------------------------ Layer 0
;; Trigger matching

(defn event-value
  [event key]
  (or (get event key)
      (case key
        :repo (:pr/repo event)
        :branch (:pr/branch event)
        nil)))

(defn matches-trigger?
  "Check if an event matches a trigger rule."
  [trigger event]
  (boolean
    (and (= (:on trigger) (:event/type event))
         (or (nil? (:repo trigger))
             (= (:repo trigger) (event-value event :repo)))
         (or (nil? (:branch-pattern trigger))
             (re-matches (re-pattern (:branch-pattern trigger))
                         (or (event-value event :branch) "")))
         (or (nil? (:match trigger))
             (every? (fn [[k v]]
                       (= v (event-value event k)))
                     (:match trigger))))))

(defn extract-input
  "Extract input from event using trigger's :input-from-event mapping."
  [trigger event]
  (when-let [mapping (get-in trigger [:run :input-from-event])]
    (reduce-kv
      (fn [acc k event-key]
        (assoc acc k (get event event-key)))
      {}
      mapping)))

;------------------------------------------------------------------------------ Layer 1
;; Configuration loading

(defn load-trigger-config
  "Load trigger configuration from an EDN file.
   Returns {:triggers [trigger-rule...]}."
  [path]
  (clojure.edn/read-string (slurp path)))

;------------------------------------------------------------------------------ Layer 2
;; Trigger execution

(defn fire-workflow
  "Load and run a workflow from a trigger's run-spec. Returns the future."
  [run-spec input opts load-wf run-pipeline]
  (let [{:keys [workflow-id version] :or {version :latest}} run-spec
        wf-result (load-wf workflow-id version opts)
        workflow  (:workflow wf-result)]
    (run-pipeline workflow input opts)))

(defn handle-trigger-event
  "Process a single event against all trigger rules, firing matching workflows."
  [triggers opts futures-atom load-wf run-pipeline event]
  (doseq [trigger triggers]
    (when (matches-trigger? trigger event)
      (let [input    (or (extract-input trigger event) {})
            run-spec (:run trigger)
            f        (future (fire-workflow run-spec input opts load-wf run-pipeline))]
        (swap! futures-atom conj f)))))

(defn cancel-futures!
  "Cancel all pending futures and clear the atom."
  [futures-atom]
  (doseq [f @futures-atom]
    (future-cancel f))
  (reset! futures-atom []))

;------------------------------------------------------------------------------ Layer 3
;; Trigger lifecycle

(defn create-event-trigger
  "Create an event trigger that subscribes to an event stream.

   Arguments:
   - event-stream: Event stream to subscribe to
   - trigger-config: {:triggers [{:on :event/type :match {...} :run {...}}]}
   - opts: Options map, passed to workflow execution

   Returns {:subscriber-id keyword, :stop-fn (fn [])}"
  [event-stream trigger-config opts]
  (let [triggers     (:triggers trigger-config)
        futures-atom (atom [])
        subscribe!   (requiring-resolve 'ai.miniforge.event-stream.interface/subscribe!)
        unsubscribe! (requiring-resolve 'ai.miniforge.event-stream.interface/unsubscribe!)
        run-pipeline runner/run-pipeline
        load-wf      loader/load-workflow
        sub-id       :event-trigger]
    (subscribe! event-stream sub-id
                (partial handle-trigger-event triggers opts futures-atom load-wf run-pipeline))
    {:subscriber-id sub-id
     :stop-fn       #(do (unsubscribe! event-stream sub-id)
                         (cancel-futures! futures-atom))}))

(defn create-merge-trigger
  "Compatibility alias for PR-merge triggered workflows."
  [event-stream trigger-config opts]
  (create-event-trigger event-stream trigger-config opts))

(defn stop-trigger!
  "Stop an event trigger. Unsubscribes and cancels pending work."
  [{:keys [stop-fn]}]
  (when stop-fn (stop-fn)))
