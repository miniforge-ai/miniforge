(ns ai.miniforge.policy-calibration.gate-check
  "Build-time gate-readiness check: a rule compiled to an ACTING SEMANTIC gate
   must carry a passing calibration record. \"Acting\" = an enforcement action
   that actually gates — :hard-halt (block) or :require-approval (pause for a
   human). Deterministic-detector rules (content-scan etc.) are exempt —
   objective by construction. An acting semantic rule that gates without a
   passing record fails the build, so a rule earns the gate rather than being
   granted it by frontmatter."
  (:require [ai.miniforge.policy-calibration.config :as config]
            [ai.miniforge.policy-pack.interface :as pp]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private acting-actions
  "Enforcement actions that make a rule gate (block / require approval)."
  #{:hard-halt :require-approval})

(defn requires-calibration?
  "True iff the rule gates via the semantic judge — an acting enforcement action
   AND a semantic (non-deterministic) detector."
  [rule]
  (and (contains? acting-actions (get-in rule [:rule/enforcement :action]))
       (= :semantic (pp/resolve-detector rule))))

(defn check
  "Given resolved `rules` and a calibration `record` ({rule-id -> verdict}),
   return {:ok? bool :ungated [rule-id ...]} — acting semantic rules
   (:hard-halt or :require-approval) that lack a passing (:gate-ready? true)
   calibration record."
  [rules record]
  (let [ungated (->> rules
                     (filter requires-calibration?)
                     (remove #(get-in record [(:rule/id %) :gate-ready?]))
                     (mapv :rule/id))]
    {:ok? (empty? ungated) :ungated ungated}))

(defn- read-edn [path] (edn/read-string (slurp (io/file path))))

(defn check-shipped
  "Load the shipped packs + the committed calibration record (paths from the
   calibration config) and run `check`."
  []
  (let [cfg    (config/load-config)
        rules  (pp/resolve-rules (mapcat #(:pack/rules (read-edn %) []) (:pack-paths cfg)))
        record (:rules (read-edn (:record-out cfg)))]
    (check rules record)))

(comment
  (check-shipped)
  :leave-this-here)
