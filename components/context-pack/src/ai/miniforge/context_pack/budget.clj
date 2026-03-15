;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.context-pack.budget
  "Token budget enforcement for context packs.

   Layer 1 — depends on config (Layer 0) and factory (Layer 0)."
  (:require [ai.miniforge.context-pack.config :as config]
            [ai.miniforge.context-pack.factory :as factory]))

;------------------------------------------------------------------------------ Layer 0
;; Token estimation

(defn estimate-tokens
  "Estimate token count for a string (~4 chars per token)."
  [s]
  (if (string? s)
    (int (Math/ceil (/ (count s) 4)))
    0))

(defn would-exceed?
  "Check if adding tokens would exceed the pack's budget."
  [pack additional-tokens]
  (> (+ (:tokens-used pack) additional-tokens) (:budget pack)))

;------------------------------------------------------------------------------ Layer 1
;; Budget tracking

(defn add-source
  "Add a source to the pack, tracking its token cost.
   Returns updated pack, or pack with :exhausted? true if over budget."
  [pack kind path content]
  (let [tokens (estimate-tokens content)
        new-total (+ (:tokens-used pack) tokens)
        over-budget? (> new-total (:budget pack))
        policy (config/exhaustion-policy)]
    (if (and over-budget? (= policy :fail-closed))
      (assoc pack :exhausted? true)
      (-> pack
          (assoc :tokens-used new-total)
          (update :sources conj (factory/->source kind path tokens))
          (cond->
            (and over-budget? (= policy :warn))
            (assoc :exhausted? true))))))

(defn try-add-item
  "Try to add an item to the pack within budget.
   Returns updated pack if it fits, or pack with :exhausted? true if not."
  [pack kind path content update-fn]
  (let [tokens (estimate-tokens content)]
    (if (would-exceed? pack tokens)
      (assoc pack :exhausted? true)
      (-> pack
          update-fn
          (add-source kind path content)))))

(defn tokens-remaining
  "Get remaining token budget for a pack."
  [pack]
  (max 0 (- (:budget pack) (:tokens-used pack))))

(defn exhausted?
  "Check if a pack's budget is exhausted."
  [pack]
  (:exhausted? pack))
