;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.training-capture.interface
  "Public API for training example capture per learning.spec §2.

   Layer 0: Quality scoring (pure)
   Layer 1: Training record creation (pure)
   Layer 2: Training store (stateful)"
  (:require
   [ai.miniforge.training-capture.quality :as quality]
   [ai.miniforge.training-capture.capture :as capture]))

;------------------------------------------------------------------------------ Layer 0
;; Quality scoring

(def compute-quality-score
  "Compute quality score from feedback signals. Returns float 0.0-1.0."
  quality/compute-quality-score)

(def label-example
  "Classify example type: :positive | :negative | :corrected | :hard-positive | :ambiguous"
  quality/label-example)

;------------------------------------------------------------------------------ Layer 1
;; Training record creation

(def create-training-record
  "Create a training record from agent execution results.

   Arguments:
     opts - {:agent-role :workflow-id :phase :input :output :feedback :outcome}

   Returns: training record with :training/labels {:quality-score :example-type}"
  capture/create-training-record)

;------------------------------------------------------------------------------ Layer 2
;; Training store

(def create-store
  "Create an in-memory training example store."
  capture/create-store)

(def store-example!
  "Store a training example. Maintains indexes by workflow and agent."
  capture/store-example!)

(def get-examples
  "Get training examples with optional filters.
   Options: :agent-role :min-quality :label :limit"
  capture/get-examples)

(def example-count
  "Get the total number of stored examples."
  capture/example-count)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def store (create-store))

  (store-example! store
                  (create-training-record
                   {:agent-role :implementer
                    :workflow-id (random-uuid)
                    :phase :implement
                    :feedback {:validation-result :passed :iterations-to-pass 1}
                    :outcome {:phase-succeeded? true}}))

  (example-count store) ;; => 1
  (get-examples store {:min-quality 0.8})

  :leave-this-here)
