;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.context-pack.factory
  "Factory functions for context-pack domain maps.

   Layer 0 — pure data construction.")

;------------------------------------------------------------------------------ Layer 0
;; ContextPack

(defn ->context-pack
  "Create a ContextPack map."
  [phase budget repo-map-text files search-results]
  {:phase phase
   :budget budget
   :tokens-used 0
   :repo-map repo-map-text
   :files files
   :search-results search-results
   :exhausted? false
   :sources []})

;------------------------------------------------------------------------------ Layer 0
;; Budget audit

(defn ->budget-audit
  "Create a budget audit snapshot."
  [phase budget tokens-used exhausted? source-count]
  {:phase phase
   :budget budget
   :tokens-used tokens-used
   :tokens-remaining (max 0 (- budget tokens-used))
   :exhausted? exhausted?
   :source-count source-count
   :utilization (if (pos? budget)
                  (double (/ tokens-used budget))
                  0.0)})

;------------------------------------------------------------------------------ Layer 0
;; Source tracking

(defn ->source
  "Create a source-tracking entry for audit trail."
  [kind path tokens]
  {:kind kind
   :path path
   :tokens tokens})
