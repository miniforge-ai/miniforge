;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.improvement.interface
  "Public API for the improvement pipeline.

   Layer 0: Proposal generation
   Layer 1: Pipeline management"
  (:require
   [ai.miniforge.improvement.proposal :as proposal]
   [ai.miniforge.improvement.pipeline :as pipeline]))

;------------------------------------------------------------------------------ Layer 0
;; Proposal generation

(def generate-proposal
  "Generate an improvement proposal from a diagnosis."
  proposal/generate-proposal)

(def generate-proposals
  "Generate proposals from diagnoses (filtered by min-confidence)."
  proposal/generate-proposals)

;------------------------------------------------------------------------------ Layer 1
;; Pipeline management

(def create-pipeline
  "Create an improvement pipeline store."
  pipeline/create-pipeline)

(def store-proposal! pipeline/store-proposal!)
(def get-proposals pipeline/get-proposals)
(def approve-proposal! pipeline/approve-proposal!)
(def deploy-proposal! pipeline/deploy-proposal!)
(def rollback-proposal! pipeline/rollback-proposal!)
