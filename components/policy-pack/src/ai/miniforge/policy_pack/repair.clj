(ns ai.miniforge.policy-pack.repair
  "Repair function registry for policy-pack violations.

   Repair functions can automatically fix certain violation types.
   They are registered by rule-id or violation pattern.

   Layer 0: Repair registry
   Layer 1: Built-in repair functions
   Layer 2: Repair orchestration"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Result predicates

(defn succeeded?
  "Check if a repair result indicates success."
  [result]
  (boolean (:success? result)))

;; Repair registry

;; Registry of repair functions keyed by rule-id pattern.
(defonce repair-registry (atom {}))

(defn register-repair!
  "Register a repair function for a rule-id or pattern.

   Arguments:
   - rule-id-or-pattern: String or regex matching rule IDs
   - repair-fn: (fn [violation artifact context] -> {:success? bool :artifact map :fix-description string})

   Returns: rule-id-or-pattern"
  [rule-id-or-pattern repair-fn]
  (swap! repair-registry assoc rule-id-or-pattern repair-fn)
  rule-id-or-pattern)

(defn deregister-repair!
  "Remove a repair function."
  [rule-id-or-pattern]
  (swap! repair-registry dissoc rule-id-or-pattern)
  nil)

(defn get-repair-fn
  "Find a repair function for a given rule-id.

   Checks exact match first, then regex patterns.

   Returns: repair-fn or nil"
  [rule-id]
  (or (get @repair-registry rule-id)
      (some (fn [[k v]]
              (when (and (instance? java.util.regex.Pattern k)
                         (re-matches k rule-id))
                v))
            @repair-registry)))

(defn list-repairs
  "List all registered repair function keys."
  []
  (keys @repair-registry))

;------------------------------------------------------------------------------ Layer 1
;; Built-in repair functions

(defn whitespace-repair
  "Repair function for whitespace violations (trailing whitespace, mixed tabs/spaces)."
  [_violation artifact _context]
  (let [content (get artifact :content "")
        fixed (-> content
                  (str/replace #"[ \t]+\n" "\n")
                  (str/replace #"\t" "  "))]
    (if (= content fixed)
      {:success? false :artifact artifact :fix-description "No whitespace issues found"}
      {:success? true
       :artifact (assoc artifact :content fixed)
       :fix-description "Removed trailing whitespace and converted tabs to spaces"})))

(defn trailing-newline-repair
  "Ensure file ends with exactly one newline."
  [_violation artifact _context]
  (let [content (get artifact :content "")
        fixed (str (clojure.string/trimr content) "\n")]
    {:success? true
     :artifact (assoc artifact :content fixed)
     :fix-description "Ensured file ends with single newline"}))

;------------------------------------------------------------------------------ Layer 2
;; Repair orchestration

(defn attempt-repair
  "Attempt to repair a single violation.

   Arguments:
   - violation: Violation map with :violation/rule-id
   - artifact: Artifact map with :content
   - context: Execution context

   Returns: {:success? bool :artifact map :fix-description string?} or nil if no repair available"
  [violation artifact context]
  (when-let [repair-fn (get-repair-fn (:violation/rule-id violation))]
    (try
      (repair-fn violation artifact context)
      (catch Exception e
        {:success? false
         :artifact artifact
         :fix-description (str "Repair failed: " (ex-message e))}))))

(defn attempt-repairs
  "Attempt to repair all violations in sequence.

   Applies repair functions in order. Each successful repair updates the artifact
   for subsequent repairs.

   Arguments:
   - violations: Vector of violation maps
   - artifact: Artifact map
   - context: Execution context

   Returns: {:artifact map :repaired [] :unrepaired [] :repair-count int}"
  [violations artifact context]
  (loop [remaining violations
         current-artifact artifact
         repaired []
         unrepaired []]
    (if (empty? remaining)
      {:artifact current-artifact
       :repaired repaired
       :unrepaired unrepaired
       :repair-count (count repaired)}
      (let [violation (first remaining)
            result (attempt-repair violation current-artifact context)]
        (if (and result (succeeded? result))
          (recur (rest remaining)
                 (:artifact result)
                 (conj repaired {:violation violation
                                 :fix (:fix-description result)})
                 unrepaired)
          (recur (rest remaining)
                 current-artifact
                 repaired
                 (conj unrepaired violation)))))))
