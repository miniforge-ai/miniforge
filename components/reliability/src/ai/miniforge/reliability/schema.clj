;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.schema
  "Malli schemas for reliability data structures per N1 §5.5.")

;------------------------------------------------------------------------------ Layer 0
;; Enums

(def WorkflowTier
  "Workflow tier determines SLO targets and degradation behavior."
  [:enum :best-effort :standard :critical])

(def SliName
  [:enum :SLI-1 :SLI-2 :SLI-3 :SLI-4 :SLI-5 :SLI-6 :SLI-7])

(def Window
  [:enum :1h :7d :30d])

(def DegradationMode
  [:enum :nominal :degraded :safe-mode])

(def DependencyKind
  [:enum :provider :platform :environment])

(def DependencyHealthStatus
  [:enum :healthy :degraded :unavailable :misconfigured :operator-action-required])

;------------------------------------------------------------------------------ Layer 0
;; Records

(def SliResult
  [:map
   [:sli/name SliName]
   [:sli/value :double]
   [:sli/window Window]
   [:sli/tier {:optional true} WorkflowTier]
   [:sli/dimensions {:optional true} :map]])

(def SloCheck
  [:map
   [:breached? :boolean]
   [:sli/name SliName]
   [:slo/target :double]
   [:slo/actual :double]
   [:slo/tier WorkflowTier]
   [:slo/window Window]])

(def ErrorBudget
  [:map
   [:error-budget/tier WorkflowTier]
   [:error-budget/sli SliName]
   [:error-budget/window Window]
   [:error-budget/remaining :double]
   [:error-budget/burn-rate :double]
   [:error-budget/computed-at inst?]])

(def DependencyHealth
  [:map
   [:dependency/id keyword?]
   [:dependency/source keyword?]
   [:dependency/kind DependencyKind]
   [:dependency/status DependencyHealthStatus]
   [:dependency/failure-count :int]
   [:dependency/window-size :int]
   [:dependency/incident-counts [:map-of DependencyHealthStatus :int]]
   [:dependency/vendor {:optional true} keyword?]
   [:dependency/class {:optional true} keyword?]
   [:dependency/retryability {:optional true} keyword?]
   [:failure/class {:optional true} keyword?]
   [:dependency/last-observed-at {:optional true} inst?]
   [:dependency/last-recovered-at {:optional true} inst?]])
