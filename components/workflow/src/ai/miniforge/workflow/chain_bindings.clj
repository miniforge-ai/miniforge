;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-bindings
  "Pure interpretation of the deployed chain input-binding vocabulary.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} expression [declaration]
  (if (map? declaration) (:binding/expression declaration) declaration))

(defn ^{:stratum 0} required? [declaration]
  (and (map? declaration) (true? (:binding/required? declaration))))

(defn- ^{:stratum 0} detach-expression [value]
  (if (vector? value) (into [] value) value))

(defn- ^{:stratum 0} chain-input? [binding]
  (and (keyword? binding) (= "chain" (namespace binding))
       (.startsWith (name binding) "input.")))

(defn- ^{:stratum 0} input-value [binding input]
  (get input (keyword (subs (name binding) (count "input.")))))

(defn- ^{:stratum 0} previous-value [[root & path] previous]
  (let [sources {:prev/phase-results (:phase-results previous)
                 :prev/artifacts (:artifacts previous)
                 :prev/last-phase-result (:last-phase-result previous)}
        source (get sources root previous)]
    (if (seq path) (get-in source (vec path)) source)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} detach-declaration [declaration]
  (if (map? declaration)
    (update (into {} declaration) :binding/expression detach-expression)
    (detach-expression declaration)))

(defn ^{:stratum 1} resolve-binding [binding previous input]
  (let [value (expression binding)]
    (cond
      (string? value) value
      (chain-input? value) (input-value value input)
      (vector? value) (previous-value value previous)
      (keyword? value) (get input value))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-bindings [bindings previous input]
  (let [values (map #(resolve-binding % previous input) (vals bindings))]
    (into {} (remove (comp nil? val)) (zipmap (keys bindings) values))))

(comment
  (resolve-binding :chain/input.task nil {:task "example"}))
