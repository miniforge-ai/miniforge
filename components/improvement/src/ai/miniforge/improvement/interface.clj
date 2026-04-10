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

(def store-proposal!
  "Store a new improvement proposal in the pipeline."
  pipeline/store-proposal!)

(def get-proposals
  "Get proposals, optionally filtered by status keyword."
  pipeline/get-proposals)

(def approve-proposal!
  "Mark a proposal as approved for deployment."
  pipeline/approve-proposal!)

(def deploy-proposal!
  "Mark an approved proposal as deployed."
  pipeline/deploy-proposal!)

(def rollback-proposal!
  "Mark a deployed proposal as rolled back with reason."
  pipeline/rollback-proposal!)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def pipeline (create-pipeline))
  (def p (generate-proposal {:diagnosis/id (random-uuid)
                              :diagnosis/confidence 0.7
                              :diagnosis/hypothesis "Test"}))
  (store-proposal! pipeline p)
  (approve-proposal! pipeline (:improvement/id p))
  (get-proposals pipeline :approved)

  :leave-this-here)
