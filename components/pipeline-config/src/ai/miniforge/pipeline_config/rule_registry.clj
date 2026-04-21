(ns ai.miniforge.pipeline-config.rule-registry
  "Atom-backed registry mapping rule type keywords to constructor functions."
  (:require [ai.miniforge.data-quality.interface :as dq]))

(defn create-rule-registry
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
                     :pattern    (fn [{:rule/keys [field pattern]}]
                                   (dq/pattern-rule field (re-pattern pattern)))}
         :custom-rules {}}))

(defn register-rule!
  "Register a custom rule by id. rule-fn is the instantiated rule."
  [registry rule-id rule-fn]
  (swap! registry assoc-in [:custom-rules rule-id] rule-fn)
  nil)

(defn resolve-rules
  "Convert a rule registry into a flat lookup map suitable for the resolver.
   Returns a map where:
     - built-in type keywords map to resolver fns
     - custom rule ids map to rule instances."
  [registry]
  (let [{:keys [resolvers custom-rules]} @registry]
    (merge resolvers custom-rules)))
