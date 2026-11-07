(ns ai.miniforge.pipeline-config.rule-registry
  "Atom-backed registry mapping rule type keywords to constructor functions."
  (:require [ai.miniforge.data-quality.interface :as dq]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} required-rule [{:rule/keys [field]}]
  (dq/required-rule field))

(defn- ^{:stratum 0} type-check-rule [{:rule/keys [field expected-type]}]
  (dq/type-check-rule field expected-type))

(defn- ^{:stratum 0} range-rule [{:rule/keys [field min max]}]
  (dq/range-rule field min max))

(defn- ^{:stratum 0} pattern-rule [{:rule/keys [field pattern]}]
  (dq/pattern-rule field (re-pattern pattern)))

(defn- ^{:stratum 0} invalid-rule
  "The descriptor, kept as data with a :rule/error and no check fn."
  [descriptor e]
  (assoc descriptor :rule/error {:error/kind    :rule/invalid-config
                                 :error/type    (.getName (class e))
                                 :error/message (ex-message e)}))

(defn ^{:stratum 0} register-rule!
  "Register a custom rule by id. rule-fn is the instantiated rule."
  [registry rule-id rule-fn]
  (swap! registry assoc-in [:custom-rules rule-id] rule-fn)
  nil)

(defn ^{:stratum 0} resolve-rules
  "Convert a rule registry into a flat lookup map suitable for the resolver.
   Returns a map where:
     - built-in type keywords map to resolver fns
     - custom rule ids map to rule instances."
  [registry]
  (let [{:keys [resolvers custom-rules]} @registry]
    (merge resolvers custom-rules)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private constructors
  "Built-in rule types and the constructor for each."
  {:required   required-rule
   :type-check type-check-rule
   :range      range-rule
   :pattern    pattern-rule})

(defn- ^{:stratum 1} guarded-constructor
  "Wrap a built-in constructor. A descriptor it cannot build (a missing
   field, a pattern that does not compile) is configuration the caller got
   wrong, not a crash: return it with a :rule/error and no check fn, so
   preflight refuses it by name. Plain try: pipeline-config has no
   slingshot dependency (rule 211)."
  [constructor]
  (fn [descriptor]
    (try
      (constructor descriptor)
      (catch Exception e
        (invalid-rule descriptor e)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create-rule-registry
  "Create a rule registry pre-loaded with built-in rule type resolvers.
   Built-in types: :required, :type-check, :range, :pattern. A descriptor a
   built-in constructor cannot build resolves to itself with a :rule/error.
   Custom rules are registered separately by id."
  []
  (atom {:resolvers    (update-vals constructors guarded-constructor)
         :custom-rules {}}))
