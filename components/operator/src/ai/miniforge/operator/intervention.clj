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

(ns ai.miniforge.operator.intervention
  "Pure bounded intervention lifecycle helpers.

   This namespace models supervisory intervention intent and lifecycle state
   transitions. It does not apply control actions directly."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [ai.miniforge.operator.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Config

(def ^:private intervention-config-resource
  "Classpath location of the intervention lifecycle config."
  "config/operator/intervention.edn")

(defn- load-intervention-config
  []
  (if-let [resource (io/resource intervention-config-resource)]
    (:operator/intervention (edn/read-string (slurp resource)))
    (throw (ex-info "Missing operator intervention config resource"
                    {:resource intervention-config-resource}))))

(def ^:private intervention-config
  (delay (load-intervention-config)))

(def ^:private initial-state
  (:initial-state @intervention-config))

(def approval-required-types
  "Interventions that default to an explicit human approval step because the
   requested action materially changes runtime posture or execution."
  (:approval-required-types @intervention-config))

(def terminal-states
  "Terminal intervention lifecycle states."
  (:terminal-states @intervention-config))

(def default-target-type-by-intervention
  "Default target type for each intervention."
  (:default-target-type-by-intervention @intervention-config))

(def lifecycle-transitions
  "Valid intervention lifecycle transitions. Map of [from-state event] -> to-state."
  (:lifecycle-transitions @intervention-config))

(def intervention-types
  "Bounded v1 intervention vocabulary per N5-delta-supervisory-control-plane
   §7.1."
  (set (keys default-target-type-by-intervention)))

(def target-types
  "Known target categories for v1 interventions."
  (set (vals default-target-type-by-intervention)))

(def lifecycle-states
  "Intervention lifecycle states per N5-delta-supervisory-control-plane §3.3."
  (-> (set (mapcat (fn [[[from-state _event] to-state]]
                     [from-state to-state])
                   lifecycle-transitions))
      (conj initial-state)
      (set/union terminal-states)))

;------------------------------------------------------------------------------ Layer 1
;; Construction and validation

(defn approval-required?
  "Return true when `intervention-type` defaults to a pending-human step."
  [intervention-type]
  (contains? approval-required-types intervention-type))

(defn valid-type?
  "Check if an intervention type is part of the bounded vocabulary."
  [intervention-type]
  (contains? intervention-types intervention-type))

(defn valid-target-type?
  "Check if a target type is recognized."
  [target-type]
  (contains? target-types target-type))

(defn valid-state?
  "Check if an intervention lifecycle state is valid."
  [state]
  (contains? lifecycle-states state))

(defn terminal-state?
  "Check if an intervention lifecycle state is terminal."
  [state]
  (contains? terminal-states state))

(defn intervention-target-type
  "Resolve the default target type for an intervention."
  [intervention-type]
  (get default-target-type-by-intervention intervention-type))

(defn- intervention-transition-error
  [error message]
  {:success? false
   :error error
   :message message})

(defn- with-optional-intervention-attrs
  [intervention opts]
  (cond-> intervention
    (:reason opts) (assoc :intervention/reason (:reason opts))
    (:outcome opts) (assoc :intervention/outcome (:outcome opts))
    (:details opts)
    (update :intervention/details
            (fn [existing]
              (merge (or existing {}) (:details opts))))))

(defn- transition-opts
  [key value]
  (cond-> {}
    value (assoc key value)))

(defn create-intervention
  "Create a new InterventionRequest map.

   Required keys in `request`:
   - :intervention/type
   - :intervention/target-id
   - :intervention/requested-by
   - :intervention/request-source

   Optional:
   - :intervention/target-type (defaults from the intervention vocabulary)
   - :intervention/justification
   - :intervention/details
   - :intervention/approval-required?"
  [{:intervention/keys [type target-id requested-by request-source target-type]
    :as request}]
  (let [resolved-target-type (or target-type (intervention-target-type type))
        now (java.util.Date.)
        approval-required?* (boolean (or (:intervention/approval-required? request)
                                         (approval-required? type)))]
     (cond
      (not (valid-type? type))
      (throw (ex-info (messages/t :intervention/unknown-type)
                      {:intervention/type type}))

      (nil? resolved-target-type)
      (throw (ex-info (messages/t :intervention/target-type-required)
                      {:intervention/type type}))

      (not (valid-target-type? resolved-target-type))
      (throw (ex-info (messages/t :intervention/unknown-target-type)
                      {:intervention/type type
                       :intervention/target-type resolved-target-type}))

      (nil? target-id)
      (throw (ex-info (messages/t :intervention/target-id-required)
                      {:intervention/type type}))

      (or (nil? requested-by) (nil? request-source))
      (throw (ex-info (messages/t :intervention/requester-required)
                      {:intervention/type type
                       :intervention/requested-by requested-by
                       :intervention/request-source request-source}))

      :else
      (cond-> {:intervention/id (or (:intervention/id request) (random-uuid))
               :intervention/type type
               :intervention/target-type resolved-target-type
               :intervention/target-id target-id
               :intervention/requested-by requested-by
               :intervention/request-source request-source
               :intervention/state initial-state
               :intervention/requested-at now
               :intervention/updated-at now}
        (:intervention/justification request)
        (assoc :intervention/justification (:intervention/justification request))

        (:intervention/details request)
        (assoc :intervention/details (:intervention/details request))

        approval-required?*
        (assoc :intervention/approval-required? true)))))

;------------------------------------------------------------------------------ Layer 2
;; Lifecycle helpers

(defn valid-transition?
  "Check if an intervention lifecycle transition is valid."
  [current-state event]
  (contains? lifecycle-transitions [current-state event]))

(defn next-state
  "Resolve the next intervention lifecycle state."
  [current-state event]
  (get lifecycle-transitions [current-state event]))

(defn transition
  "Apply a lifecycle transition to `intervention`.

   `opts` may carry:
   - :reason
   - :outcome
   - :details
   - :updated-at"
  ([intervention event]
   (transition intervention event {}))

  ([intervention event opts]
   (let [current-state (:intervention/state intervention)
         next (next-state current-state event)
         updated-at (or (:updated-at opts) (java.util.Date.))]
     (cond
       (not (valid-state? current-state))
       (intervention-transition-error
        :invalid-state
        (messages/t :intervention/invalid-state {:state current-state}))

       (terminal-state? current-state)
       (intervention-transition-error
        :terminal-state
        (messages/t :intervention/terminal-state {:state current-state}))

       (nil? next)
       (intervention-transition-error
        :invalid-transition
        (messages/t :intervention/invalid-transition
                    {:state current-state
                     :event event}))

       :else
       {:success? true
        :intervention (-> intervention
                          (assoc :intervention/state next
                                 :intervention/updated-at updated-at)
                          (with-optional-intervention-attrs opts))}))))

(defn start-approval
  "Move an intervention into `:pending-human`."
  [intervention]
  (transition intervention :require-human))

(defn approve
  "Approve an intervention."
  [intervention]
  (transition intervention :approve))

(defn reject
  "Reject an intervention."
  ([intervention]
   (reject intervention nil))
  ([intervention reason]
   (transition intervention :reject (transition-opts :reason reason))))

(defn dispatch
  "Dispatch an approved intervention to the orchestrator."
  [intervention]
  (transition intervention :dispatch))

(defn apply-result
  "Mark an intervention as applied."
  ([intervention]
   (apply-result intervention nil))
  ([intervention outcome]
   (transition intervention :apply (transition-opts :outcome outcome))))

(defn verify
  "Mark an intervention as verified."
  ([intervention]
   (verify intervention nil))
  ([intervention outcome]
   (transition intervention :verify (transition-opts :outcome outcome))))

(defn fail
  "Mark an intervention as failed."
  ([intervention]
   (fail intervention nil))
  ([intervention reason]
   (transition intervention :fail (transition-opts :reason reason))))

(defn supported-target-types
  "Return the configured target types for a given intervention type."
  [intervention-type]
  (let [target-type (intervention-target-type intervention-type)]
    (if target-type
      #{target-type}
      #{})))

(defn bounded-vocabulary?
  "Check if every requested intervention type is part of the bounded vocabulary."
  [types]
  (empty? (set/difference (set types) intervention-types)))
