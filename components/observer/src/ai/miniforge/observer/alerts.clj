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

(ns ai.miniforge.observer.alerts
  (:require [clojure.string :as str]))

(def AlertRule
  [:multi {:dispatch :alert/type}
   [:phase-gap [:map [:alert/id keyword?] [:alert/type [:= :phase-gap]]
                [:alert/threshold [:map [:threshold-ms pos-int?]]]
                [:alert/severity {:optional true} [:enum :info :warning :critical]]
                [:alert/message-template {:optional true} string?]]]
   [:tool-call-timeout [:map [:alert/id keyword?] [:alert/type [:= :tool-call-timeout]]
                        [:alert/threshold [:map [:threshold-ms pos-int?]]]
                        [:alert/severity {:optional true} [:enum :info :warning :critical]]
                        [:alert/message-template {:optional true} string?]]]
   [:tool-error-rate [:map [:alert/id keyword?] [:alert/type [:= :tool-error-rate]]
                      [:alert/threshold [:map [:threshold-pct [:and number? [:fn #(<= 0.0 % 1.0)]]]]]
                      [:alert/severity {:optional true} [:enum :info :warning :critical]]
                      [:alert/message-template {:optional true} string?]]]])

(def AlertMap [:map [:alert/rule-id keyword?]
               [:alert/type [:enum :phase-gap :tool-call-timeout :tool-error-rate]]
               [:alert/severity [:enum :info :warning :critical]]
               [:alert/message string?] [:alert/data map?] [:alert/timestamp inst?]])

(def AlertFired [:map [:event/type [:= :observer/alert-fired]]
                 [:event/workflow-id some?] [:event/timestamp inst?]
                 [:alert/rule-id keyword?]
                 [:alert/type [:enum :phase-gap :tool-call-timeout :tool-error-rate]]
                 [:alert/severity [:enum :info :warning :critical]]
                 [:alert/message string?] [:alert/data map?] [:alert/timestamp inst?]])

(def ^:private window-size 100)

(defn create-alert-state []
  (atom {:outstanding-calls {} :tool-outcomes []}))

(defn- event-ms [event]
  (try
    (let [ts (:event/timestamp event)]
      (cond
        (instance? java.util.Date ts) (.getTime ^java.util.Date ts)
        (number? ts) (long ts)
        (string? ts) (.toEpochMilli (java.time.Instant/parse ts))
        :else (System/currentTimeMillis)))
    (catch Exception _ (System/currentTimeMillis))))

(defn- update-state! [state event]
  (case (:event/type event)
    :agent/tool-call-started
    (when-let [call-id (:tool/call-id event)]
      (swap! state assoc-in [:outstanding-calls call-id] {:started-at (event-ms event)}))

    :tool/call-completed
    (let [call-id (:tool/call-id event)]
      (swap! state
             (fn [s]
               (cond-> (update s :outstanding-calls dissoc call-id)
                 (contains? event :tool/success?)
                 (update :tool-outcomes
                         (fn [xs]
                           (let [xs' (conj (vec xs) (:tool/success? event))]
                             (if (> (count xs') window-size)
                               (subvec xs' (- (count xs') window-size))
                               xs'))))))))
    nil))

(defn- template [s data]
  (str/replace s #"\{\{([\w][\w/-]*)\}\}" #(str (get data (keyword (second %)) ""))))

(defn- message [rule data]
  (if-let [s (:alert/message-template rule)]
    (template s data)
    (case (:alert/type rule)
      :phase-gap (str "Phase gap of " (:gap-ms data) "ms exceeds threshold")
      :tool-call-timeout (str "Tool call " (:call-id data) " timed out")
      :tool-error-rate (format "Tool error rate %.1f%% exceeds threshold"
                               (* 100.0 (double (:error-rate data)))))))

(defn- alert [rule data]
  {:alert/rule-id (:alert/id rule)
   :alert/type (:alert/type rule)
   :alert/severity (get rule :alert/severity :warning)
   :alert/message (message rule data)
   :alert/data data
   :alert/timestamp (java.util.Date.)})

(defn- phase-gap [rule event]
  (when (= :workflow/phase-heartbeat (:event/type event))
    (let [gap (:phase/gap-since-last-event-ms event)
          threshold (get-in rule [:alert/threshold :threshold-ms])]
      (when (and gap threshold (> gap threshold))
        (alert rule {:gap-ms gap :threshold-ms threshold})))))

(defn- timeouts [rule event state]
  (when (= :workflow/phase-heartbeat (:event/type event))
    (let [threshold (get-in rule [:alert/threshold :threshold-ms])
          now (event-ms event)]
      (->> (:outstanding-calls @state)
           (keep (fn [[call-id {:keys [started-at alerted?]}]]
                   (let [elapsed (- now started-at)]
                     (when (and threshold (not alerted?) (> elapsed threshold))
                       (swap! state assoc-in [:outstanding-calls call-id :alerted?] true)
                       (alert rule {:call-id call-id :elapsed-ms elapsed
                                    :threshold-ms threshold})))))
           vec))))

(defn- error-rate [rule event state]
  (when (= :workflow/phase-heartbeat (:event/type event))
    (let [xs (:tool-outcomes @state)
          threshold (get-in rule [:alert/threshold :threshold-pct])]
      (when (and (seq xs) threshold)
        (let [failures (count (remove true? xs))
              rate (double (/ failures (count xs)))]
          (when (> rate threshold)
            (alert rule {:error-rate rate
                         :failure-count failures
                         :total-count (count xs)
                         :threshold-pct threshold})))))))

(defn evaluate-rules [rules event state]
  (update-state! state event)
  (into []
        (mapcat (fn [rule]
                  (let [x (case (:alert/type rule)
                            :phase-gap (phase-gap rule event)
                            :tool-call-timeout (timeouts rule event state)
                            :tool-error-rate (error-rate rule event state)
                            nil)]
                    (cond (nil? x) [] (sequential? x) x :else [x]))))
        rules))

(defn alert-fired [_stream workflow-id alert-map]
  (merge {:event/type :observer/alert-fired
          :event/workflow-id workflow-id
          :event/timestamp (java.util.Date.)}
         (select-keys alert-map [:alert/rule-id :alert/type :alert/severity
                                 :alert/message :alert/data :alert/timestamp])))
