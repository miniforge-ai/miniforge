(ns ai.miniforge.data-quality.rules
  "Built-in rule constructors for data quality checks."
  (:require [ai.miniforge.data-quality.messages :as msg]))

(defn required-rule
  "Create a rule that checks field presence and non-nil value."
  [field & {:keys [severity] :or {severity :error}}]
  {:rule/id       (keyword (str "required-" (name field)))
   :rule/type     :required
   :rule/field    field
   :rule/severity severity
   :rule/check-fn (fn [record]
                    (let [v (get record field)]
                      (if (and (some? v) (not (and (string? v) (empty? v))))
                        {:valid? true}
                        {:valid? false
                         :message (msg/t :violation/required {:field (name field)})})))})

(defn type-check-rule
  "Create a rule that checks a field's value type."
  [field expected-type & {:keys [severity] :or {severity :error}}]
  (let [type-pred (case expected-type
                    :string  string?
                    :int     int?
                    :long    int?  ;; int? covers both int and long in Clojure
                    :decimal decimal?
                    :double  (fn [v] (or (double? v) (float? v)))
                    :boolean boolean?
                    :uuid    uuid?
                    ;; Default: always pass (unknown types are not checked)
                    (constantly true))]
    {:rule/id       (keyword (str "type-" (name field) "-" (name expected-type)))
     :rule/type     :type-check
     :rule/field    field
     :rule/severity severity
     :rule/check-fn (fn [record]
                      (let [v (get record field)]
                        (if (or (nil? v) (type-pred v))
                          {:valid? true}
                          {:valid? false
                           :message (msg/t :violation/type-check
                                           {:field (name field)
                                            :expected expected-type
                                            :actual (type v)})})))}))

(defn range-rule
  "Create a rule that checks numeric bounds: min <= value <= max."
  [field min-val max-val & {:keys [severity] :or {severity :error}}]
  {:rule/id       (keyword (str "range-" (name field)))
   :rule/type     :range
   :rule/field    field
   :rule/severity severity
   :rule/check-fn (fn [record]
                    (let [v (get record field)]
                      (if (or (nil? v)
                              (and (number? v) (<= min-val v max-val)))
                        {:valid? true}
                        {:valid? false
                         :message (msg/t :violation/range
                                         {:field (name field)
                                          :value v
                                          :min min-val
                                          :max max-val})})))})

(defn pattern-rule
  "Create a rule that checks a string field against a regex pattern."
  [field pattern & {:keys [severity] :or {severity :warning}}]
  {:rule/id       (keyword (str "pattern-" (name field)))
   :rule/type     :pattern
   :rule/field    field
   :rule/severity severity
   :rule/check-fn (fn [record]
                    (let [v (get record field)]
                      (if (or (nil? v)
                              (and (string? v) (re-matches pattern v)))
                        {:valid? true}
                        {:valid? false
                         :message (msg/t :violation/pattern
                                         {:field (name field)
                                          :value v
                                          :pattern (str pattern)})})))})

(defn custom-rule
  "Create a rule with an arbitrary check function.
   check-fn: (fn [record] {:valid? bool :message str})"
  [rule-id check-fn & {:keys [severity] :or {severity :error}}]
  {:rule/id       rule-id
   :rule/type     :custom
   :rule/severity severity
   :rule/check-fn check-fn})
