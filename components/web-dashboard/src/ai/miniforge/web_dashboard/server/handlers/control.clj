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
(ns ai.miniforge.web-dashboard.server.handlers.control
  "Control-intervention chain for dashboard control actions: writes
   operator intervention events and routes an authorized control action
   onto the governed operator channel. Depends only on the sibling
   `support` namespace."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.web-dashboard.server.responses :as responses]
   [ai.miniforge.web-dashboard.server.handlers.support :as support]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} request-workflow-intervention!
  "Write a `:supervisory/intervention-requested` event into
   `{events-dir}/operator/` for `workflow-id`. The runner's
   operator-event consumer routes it through the intervention
   lifecycle and flips the run's control state; every transition comes
   back on the event stream the console already renders.

   Returns the written event, or an anomaly when the verb is unknown or
   the write fails. Nothing is best-effort here: a control the operator
   pressed either reached the audit stream or reports why not."
  [workflow-id command requested-by]
  (if-let [intervention-type (get support/control-intervention-by-command
                                  (some-> command name str))]
    (try
      (event-stream/request-intervention!
       {:intervention/type intervention-type
        :intervention/target-type :workflow
        :intervention/target-id (str workflow-id)
        :intervention/requested-by requested-by
        :intervention/request-source :dashboard})
      (catch Exception e
        (support/make-anomaly :anomalies/fault
                              (str "Intervention request failed: " (ex-message e))
                              {:workflow-id workflow-id :command command})))
    (support/make-anomaly :anomalies/incorrect
                          (str "Unknown workflow command: " (pr-str command))
                          {:workflow-id workflow-id
                           :supported-commands (vec (sort (keys support/control-intervention-by-command)))})))

(defn ^{:stratum 0} authorization-error-response
  "Build an anomaly response for a failed authorization check."
  [auth-result action-type]
  (support/anomaly-http-response
   (or (:anomaly auth-result)
       (support/make-anomaly :anomalies/forbidden
                             (:reason auth-result)
                             {:action-type action-type}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute-via-command!
  "Execution function that routes an authorized control action onto the
   governed operator channel. Returns a failure response when the request
   cannot be written — `execute-control-action!` records the failure rather
   than letting an unapplied action report success."
  ;; `_state`: the arg is part of the `execute-control-action!` executor
  ;; signature (partial-applied with workflow-id) but this verb reaches
  ;; the operator channel directly, without touching dashboard state.
  [_state workflow-id action]
  (let [cmd (name (:action/type action))
        ;; Same server-side-only rule as command-requester: the action's
        ;; requester came from the request body and is unauthenticated
        ;; (miniforge#1460), so it must not become the governed
        ;; intervention's recorded identity. Attribute to the surface.
        result (request-workflow-intervention!
                workflow-id
                cmd
                (support/command-requester))]
    (when (anomaly/anomaly? result)
      (response/failure (:anomaly/message result)
                        {:data (:anomaly/data result)}))
    (when-not (anomaly/anomaly? result)
      {:command cmd
       :workflow-id workflow-id
       :intervention-id (str (:intervention/id result))})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-authorized-action
  "Execute a control action that has passed authorization."
  [state workflow-id action]
  (let [es (:event-stream @state)
        result (event-stream/execute-control-action!
                es action (partial execute-via-command! state workflow-id))]
    (responses/json-response {:status "executed" :result result})))
