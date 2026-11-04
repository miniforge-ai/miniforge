;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.native-material-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.native-material :as native]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.native-material-fixtures :as fixtures]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} attempted [operation value]
  (try+ (operation value) (catch ClassCastException _ ::comparator-failure)))

(deftest ^{:stratum 0} classification-retains-originals-and-does-not-change-comparison-admission-test
  (let [original (fixtures/frame "opaque-native-value")
        admitted (reporting/admit original)
        classified (native/classify original)]
    (is (identical? original (:reporting/value admitted)))
    (is (.contains (pr-str classified) "opaque-native-value"))
    (is (= :kubernetes/data-minimized-reporting-v1
           (get-in classified [:reporting/value :kubernetes/verification :kubernetes/reporting-profile])))))

(deftest ^{:stratum 0} nested-marked-material-is-protected-in-returned-reports-test
  (let [input {:observations [(fixtures/frame "opaque-native-value")]}
        protected (reporting/protect input)]
    (is (not (anomaly/anomaly? protected)))
    (is (not (.contains (pr-str protected) "opaque-native-value")))
    (is (= (redaction/marker)
           (get-in protected [:observations 0 :kubernetes/verification "spec" :kubernetes.reporting/secret])))))

(deftest ^{:stratum 0} already-protected-native-material-does-not-grow-or-change-on-republication-test
  (let [first-report (reporting/protect (fixtures/frame "opaque-native-value"))
        next-report (reporting/protect first-report)]
    (is (not (anomaly/anomaly? first-report)))
    (is (= first-report next-report))))

(deftest ^{:stratum 0} unmarked-generic-collection-types-remain-unchanged-test
  (let [value {:ordinary (list {:value 1} #{:a :b})}
        classified (:reporting/value (native/classify value))]
    (is (= value classified))
    (is (list? (:ordinary classified)))
    (is (set? (second (:ordinary classified))))))

(deftest ^{:stratum 0} malformed-and-unsupported-native-frames-fail-without-echo-test
  (doseq [value ["opaque-native-value" [(Object.)] {:opaque (Object.)}]]
    (let [result (reporting/protect {:kubernetes/verification value})]
      (is (= :invalid-input (:anomaly/type result)))
      (is (= {} (:anomaly/data result)))
      (is (not (.contains (pr-str result) "opaque-native-value"))))))

(deftest ^{:stratum 0} native-report-protection-failure-is-value-free-test
  (let [value (fixtures/frame "opaque-native-value")
        result (with-redefs [redaction/redact identity] (reporting/protect value))]
    (is (= :unavailable (:anomaly/type result)))
    (is (= {} (:anomaly/data result)))
    (is (not (.contains (pr-str result) "opaque-native-value")))))

(deftest ^{:stratum 0} native-artifact-protection-failure-cannot-create-a-record-test
  (let [value (fixtures/frame "opaque-native-value")
        result (with-redefs [redaction/redact identity]
                 (fixtures/record value))]
    (is (= :unavailable (:anomaly/type result)))
    (is (not (contains? result :artifact/id)))
    (is (not (.contains (pr-str result) "opaque-native-value")))))

(deftest ^{:stratum 0} reserved-profile-collisions-and-carrier-metadata-are-not-silently-discarded-test
  (let [frame (fixtures/frame "opaque-native-value")
        collision (assoc-in frame [:kubernetes/verification :kubernetes/reporting-profile] "123-45-6789")]
    (doseq [value [collision (with-meta frame {:private "opaque-native-value"})]]
      (let [result (reporting/protect value)]
        (is (= :invalid-input (:anomaly/type result)))
        (is (= {} (:anomaly/data result)))
        (is (not (.contains (pr-str result) "123-45-6789")))))))

(deftest ^{:stratum 0} native-carriers-cannot-collapse-values-through-comparators-or-complex-keys-test
  (doseq [value [(fixtures/sorted-carrier) {(fixtures/frame "opaque-native-value") :unsupported-key}]]
    (let [result (native/classify value)]
      (is (= :invalid-input (:anomaly/type result)))
      (is (= {} (:anomaly/data result))))))

(deftest ^{:stratum 0} ordinary-list-and-set-carriers-preserve-their-types-and-originals-before-scanning-test
  (let [frame (fixtures/frame "opaque-native-value")
        input {:ordinary (list frame #{frame})}
        classified (:reporting/value (native/classify input))]
    (is (list? (:ordinary classified)))
    (is (set? (second (:ordinary classified))))
    (is (.contains (pr-str classified) "opaque-native-value"))))

(deftest ^{:stratum 0} generic-secret-scalars-outside-native-frames-retain-shared-policy-test
  (doseq [value [nil false 42]]
    (let [input (fixtures/generic-secret-scalar value)]
      (is (= input (reporting/protect input))))))

(deftest ^{:stratum 0} unrelated-generic-scalars-remain-unchanged-alongside-a-native-frame-test
  (let [input (assoc (fixtures/frame "opaque-native-value") :generic (fixtures/generic-secret-scalar false))
        protected (reporting/protect input)]
    (is (not (anomaly/anomaly? protected)))
    (is (= (:generic input) (:generic protected)))))

(deftest ^{:stratum 0} native-material-hidden-in-map-or-list-metadata-is-refused-before-discovery-test
  (let [hidden (fixtures/frame "opaque-native-value")]
    (doseq [value [(with-meta {:ordinary true} hidden)
                   (with-meta (list :ordinary) hidden)]]
      (let [result (reporting/protect value)]
        (is (= :invalid-input (:anomaly/type result)))
        (is (= {} (:anomaly/data result)))))))

(deftest ^{:stratum 0} list-carrier-metadata-cannot-be-dropped-before-original-pii-scanning-test
  (let [value (with-meta (list (fixtures/frame "opaque-native-value")) {:private "123-45-6789"})
        result (fixtures/record value)]
    (is (= :invalid-input (:anomaly/type result)))
    (is (not (contains? result :artifact/id)))))

(deftest ^{:stratum 0} opaque-or-deferred-language-metadata-is-refused-without-inspection-test
  (let [deferred (lazy-seq (throw (ex-info "metadata must not be realized" {})))
        value (with-meta {:ordinary true} {:private deferred})
        result (reporting/admit value)]
    (is (= :invalid-input (:anomaly/type result)))
    (is (not (realized? deferred)))))

(deftest ^{:stratum 0} nested-native-frames-preserve-canonical-profiles-and-republication-test
  (let [input {:kubernetes/verification {:child (fixtures/frame "opaque-native-value")}}
        protected (reporting/protect input)
        republished (reporting/protect protected)
        record (fixtures/record protected)]
    (is (= :kubernetes/data-minimized-reporting-v1
           (get-in protected [:kubernetes/verification :child :kubernetes/verification :kubernetes/reporting-profile])))
    (is (not (.contains (pr-str protected) "opaque-native-value")))
    (is (= protected republished))
    (is (:artifact/id record))
    (is (= protected (:artifact/content record)))))

(deftest ^{:stratum 0} carried-original-findings-bind-immutable-measurement-identity-test
  (let [ctx (fixtures/context)
        original (fixtures/stamped-frame "person@example.com")
        clean (fixtures/stamped-frame "opaque-native-value")
        content (:artifact/content original)
        record (fixtures/measurement-record ctx content (dissoc original :artifact/content))
        other (fixtures/measurement-record ctx content (dissoc clean :artifact/content))]
    (is (= content (:artifact/content clean)))
    (is (true? (get-in record [:artifact/metadata :evidence/contains-pii?])))
    (is (not= (:artifact/id record) (:artifact/id other)))
    (is (not (.contains (pr-str record) "person@example.com")))))

(deftest ^{:stratum 0} stale-or-malformed-carried-protection-cannot-create-a-measurement-record-test
  (let [ctx (fixtures/context)
        prepared (fixtures/stamped-frame "person@example.com")
        content (:artifact/content prepared)
        retained (dissoc prepared :artifact/content)]
    (doseq [changed [(assoc retained :reporting/content-hash "mismatch")
                     (assoc-in retained [:artifact/metadata :evidence/contains-pii?] false)
                     (assoc retained :artifact/metadata (Object.)) nil]]
      (let [result (fixtures/measurement-record ctx content changed)]
        (is (= :invalid-input (:anomaly/type result)))
        (is (= {} (:anomaly/data result)))
        (is (not (contains? result :artifact/id)))))
    (is (= :invalid-input
           (:anomaly/type (fixtures/measurement-record ctx (assoc content :changed true) retained))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} sorted-string-keys-do-not-trigger-reserved-key-comparisons-test
  (let [generic (sorted-map "ordinary" true)
        frame (update (fixtures/frame "opaque-native-value") :kubernetes/verification
                      #(into (sorted-map) %))]
    (is (= generic (attempted reporting/protect generic)))
    (is (= generic (:reporting/value (attempted native/classify generic))))
    (doseq [input [frame {:ordinary (sorted-map "nested" frame)}
                  (fixtures/comparator-sensitive-frame "opaque-native-value")]]
      (let [protected (attempted reporting/protect input)]
        (is (map? protected))
        (is (not (anomaly/anomaly? protected)))
        (is (not (.contains (pr-str protected) "opaque-native-value")))))))
