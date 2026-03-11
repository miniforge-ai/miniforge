(ns ai.miniforge.policy-pack.interface.checking
  "Detection and artifact-checking helpers."
  (:require
   [ai.miniforge.policy-pack.core :as core]
   [ai.miniforge.policy-pack.detection :as detection]))

;------------------------------------------------------------------------------ Layer 0
;; Detection and checking

(def detect-violation detection/detect-violation)
(def check-rules detection/check-rules)
(def check-artifact core/check-artifact)
(def check-artifacts core/check-artifacts)
(def blocking-violations detection/blocking-violations)
(def approval-required-violations detection/approval-required-violations)
(def warning-violations detection/warning-violations)
(def violation->error detection/violation->error)
(def violation->warning detection/violation->warning)
