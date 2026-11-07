(ns ai.miniforge.pipeline-config.rule-registry
  "Atom-backed registry mapping rule type keywords to constructor functions."
  (:require [ai.miniforge.data-quality.interface :as dq]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} pattern-rule-or-invalid
  "Build a :pattern rule. A pattern that does not compile is configuration
   data the caller got wrong, not a crash: return the descriptor with a
   :rule/error and no check fn, so preflight refuses it by name. Plain try:
   pipeline-config has no slingshot dependency (rule 211)."
  [{:rule/keys [field pattern] :as descriptor}]
  (try
    (dq/pattern-rule field (re-pattern pattern))
    (catch Exception e
      (assoc descriptor :rule/error {:error/kind    :rule/invalid-config
                                     :error/type    (.getName (class e))
                                     :error/message (ex-message e)}))))

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

(defn ^{:stratum 1} create-rule-registry
  "Create a rule registry pre-loaded with built-in rule type resolvers.
   Built-in types: :required, :type-check, :range, :pattern.
   Custom rules are registered separately by id."
  []
  (atom {:resolvers {:required   (fn [{:rule/keys [field]}]
                                   (dq/required-rule field))
                     :type-check (fn [{:rule/keys [field expected-type]}]
                                   (dq/type-check-rule field expected-type))
                     :range      (fn [{:rule/keys [field min max]}]
                                   (dq/range-rule field min max))
                     :pattern    pattern-rule-or-invalid}
         :custom-rules {}}))
