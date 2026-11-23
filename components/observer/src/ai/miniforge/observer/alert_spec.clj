;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.alert-spec
  "Shared alert fields and threshold-specific rule schemas.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Severity [:enum :info :warning :critical])

(def ^{:stratum 0} Type [:enum :phase-gap :tool-call-timeout :tool-error-rate])

(def ^{:stratum 0} Milliseconds [:map [:threshold-ms pos-int?]])

(def ^{:stratum 0} Percentage [:map [:threshold-pct [:and number? [:fn #(<= 0.0 % 1.0)]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} fields
  [[:alert/rule-id keyword?] [:alert/type Type] [:alert/severity Severity]
   [:alert/message string?] [:alert/data map?] [:alert/timestamp inst?]])

(defn- ^{:stratum 1} rule [type threshold]
  [type [:map [:alert/id keyword?] [:alert/type [:= type]]
         [:alert/threshold threshold]
         [:alert/severity {:optional true} Severity]
         [:alert/message-template {:optional true} string?]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} AlertRule
  [:multi {:dispatch :alert/type}
   (rule :phase-gap Milliseconds)
   (rule :tool-call-timeout Milliseconds)
   (rule :tool-error-rate Percentage)])

(def ^{:stratum 2} AlertMap (into [:map] fields))

(def ^{:stratum 2} AlertFired
  (into [:map [:event/type [:= :observer/alert-fired]]
         [:event/workflow-id some?] [:event/timestamp inst?]] fields))

(comment ::alert-value-schemas)
