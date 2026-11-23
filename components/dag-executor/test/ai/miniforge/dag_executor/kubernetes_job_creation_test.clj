;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-job-creation-test
  (:require [ai.miniforge.dag-executor.protocols.executor :as executor]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes :as kubernetes]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-client :as client]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.result :as result]
            [ai.miniforge.dag-executor.kubernetes-creation-fixtures :as fixtures]
            [ai.miniforge.dag-executor.kubernetes-pod-fixtures :as pods]
            [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} capsule-budgets
  "Pin the shipped default independently and use distinct executor/task overrides."
  {:default 3600
   :executor 5400
   :task 7200})

(def ^{:stratum 0 :private true} transport-settings
  "Distinct request, readiness and poll budgets expose cross-wired settings."
  {:kubernetes/creation-request-timeout-ms 4500
   :kubernetes/pod-observation-request-timeout-ms 7000
   :kubernetes/pod-ready-timeout-ms 16000
   :kubernetes/pod-ready-poll-interval-ms 500})

(defn- ^{:stratum 0} capsule-resources [budget]
  {:resources/timeout-seconds budget})

(defn- ^{:stratum 0} task-options [resources]
  {:resources resources})

(def ^{:stratum 0 :private true} shipped-request-timeout-ms
  "Pin the default provider request budget independently of the implementation."
  30000)

(def ^{:stratum 0 :private true} provider-error-headers
  "Only leading kubectl headers may classify failures; a later header is not trusted."
  [["Error from server (AlreadyExists): " :already-exists]
   ["Error from server (Forbidden): " :forbidden]
   ["error: You must be logged in to the server (" :unauthorized]
   ["The Job \"capsule\" is invalid: " :invalid-job]
   ["unrecognized: Error from server (Forbidden): " :provider-command-failed]])

(defn- ^{:stratum 0} failure-data [reason exit-code]
  (cond-> {:reason reason}
    (some? exit-code) (assoc :exit-code exit-code)))

(defn- ^{:stratum 0} capture-request! [submitted timeout-ms budget & args]
  (swap! submitted conj (get-in (fixtures/requested args) [:spec :activeDeadlineSeconds]))
  (is (= "create" (second args)))
  (is (some #{(str "--request-timeout=" timeout-ms "ms")} args))
  (is (= budget (get-in (fixtures/requested args) [:spec :activeDeadlineSeconds])))
  (apply fixtures/response identity args))

(defn- ^{:stratum 0} capture-budget! [submitted & args]
  (swap! submitted conj (get-in (fixtures/requested args) [:spec :activeDeadlineSeconds]))
  (apply fixtures/response identity args))

(defn- ^{:stratum 0} provider-outcome [reply & _]
  (if (instance? Exception reply) (throw reply) reply))

(defn- ^{:stratum 0} interrupted-request [& _]
  (throw (InterruptedException.)))

(defn- ^{:stratum 0} readiness [& _]
  (result/ok {:pod-name "mock-capsule-pod"}))

(defn- ^{:stratum 0} expected-owner-readback [timeout-ms created until arguments]
  (is (some #{(str "--request-timeout=" timeout-ms "ms")} arguments))
  (pods/read-pod! created until arguments))

(defn- ^{:stratum 0} reject-io! [& _] (throw (AssertionError.)))

(defn- ^{:stratum 0} invalid-setting [options]
  (get-in (executor/acquire-environment!
            (kubernetes/create-kubernetes-executor options) (random-uuid) {})
          [:error :data :config/key]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} expected-readiness [expected _ _ _ effective]
  (is (= expected (select-keys effective (keys expected))))
  (readiness))

(defn- ^{:stratum 1} command-case [secret [header reason]]
  [(assoc (fixtures/provider-reply 1 secret) :err (str header secret))
   (failure-data reason 1)])

(defn- ^{:stratum 1} response-cases [secret]
  [[(fixtures/provider-reply 0 secret) (failure-data :malformed-response 0)]
   [(fixtures/provider-reply 0 (json/generate-string {:unexpected secret})) (failure-data :receipt-mismatch 0)]
   [(java.io.IOException. secret) (failure-data :command-io-failed nil)]])

(deftest ^{:stratum 1} test-invalid-capsule-budgets-fail-before-provider-io
  (let [instance (kubernetes/create-kubernetes-executor {})]
    (with-redefs [shell/sh reject-io!]
      (doseq [invalid [nil false (str (:default capsule-budgets))
                       (double (:default capsule-budgets)) 0 -1 (inc' Long/MAX_VALUE)]
              :let [budget (capsule-resources invalid)]]
        (is (= :resources/timeout-seconds (invalid-setting budget)))
        (is (= :invalid-capsule-deadline
               (get-in (executor/acquire-environment! instance (random-uuid) (task-options budget))
                       [:error :code])))))))

(deftest ^{:stratum 1} test-invalid-transport-budgets-are-rejected-at-configuration
  (with-redefs [shell/sh reject-io!]
    (doseq [key [:kubernetes/creation-request-timeout-ms
                 :kubernetes/pod-observation-request-timeout-ms
                 :kubernetes/pod-ready-timeout-ms
                 :kubernetes/pod-ready-poll-interval-ms]
            invalid [nil false 0 1.5 (inc Integer/MAX_VALUE)]]
      (is (= key (invalid-setting {key invalid}))))))

(deftest ^{:stratum 1} test-interrupted-job-submission-preserves-cancellation
  (let [instance (kubernetes/create-kubernetes-executor {})]
    (with-redefs [shell/sh interrupted-request
                  client/wait-for-pod-ready reject-io!
                  kubernetes/delete-job reject-io!]
      (try+
        (let [error (:error (executor/acquire-environment! instance (random-uuid) {}))
              interrupted? (Thread/interrupted)]
          (is (= :job-creation-unconfirmed (:code error)))
          (is (= {:reason :interrupted} (:data error)))
          (is interrupted?))
        (finally (Thread/interrupted))))))

(deftest ^{:stratum 1} test-invalid-settings-disable-the-executor-without-provider-io
  (with-redefs [shell/sh reject-io! client/cluster-info reject-io!]
    (doseq [options [[] false "" (capsule-resources 0)]
            :let [instance (kubernetes/create-kubernetes-executor options)
                  availability (executor/available? instance)
                  executed (executor/execute! instance "unused" [] {})]]
      (is (= :unavailable (executor/executor-type instance)))
      (is (false? (get-in availability [:data :available?])))
      (is (= :invalid-kubernetes-settings (get-in executed [:error :code]))))))

(deftest ^{:stratum 1} test-acquire-creates-once-and-retains-the-exact-job-receipt
  (let [instance (kubernetes/create-kubernetes-executor {:namespace "capsules"})
        submitted (atom [])
        created (atom nil)]
    (with-redefs [shell/sh (partial pods/capture-created! created
                                   (partial capture-request! submitted shipped-request-timeout-ms (:default capsule-budgets)))
                  command/run! (partial pods/read-pod! created)
                  client/wait-for-pod-ready readiness
                  kubernetes/delete-job reject-io!]
      (let [acquired (executor/acquire-environment! instance (random-uuid) {})
            metadata (get-in acquired [:data :metadata])]
        (is (result/ok? acquired))
        (is (= 1 (count @submitted)))
        (is (= (:uid fixtures/identity-metadata) (:job-uid metadata)))
        (is (= (:resourceVersion fixtures/identity-metadata) (:job-creation-resource-version metadata)))
        (is (= "capsules" (:namespace metadata)))
        (is (= "observed-pod-uid" (:pod-uid metadata)))
        (is (= "observed-pod-version" (:pod-observed-resource-version metadata)))))))

(deftest ^{:stratum 1} test-explicit-capsule-budgets-are-submitted-and-confirmed
  (let [instance (kubernetes/create-kubernetes-executor (capsule-resources (:executor capsule-budgets)))
        submitted (atom [])
        created (atom nil)]
    (with-redefs [shell/sh (partial pods/capture-created! created (partial capture-budget! submitted))
                  command/run! (partial pods/read-pod! created)
                  client/wait-for-pod-ready readiness]
      (is (result/ok? (executor/acquire-environment! instance (random-uuid) {})))
      (is (result/ok? (executor/acquire-environment! instance (random-uuid)
                                                     (task-options (capsule-resources (:task capsule-budgets))))))
      (is (= ((juxt :executor :task) capsule-budgets) @submitted)))))

(deftest ^{:stratum 1} test-uncertain-job-creation-is-never-adopted-or-cleaned-up-by-name
  (let [options (assoc (capsule-resources (:executor capsule-budgets)) :namespace "capsules")
        instance (kubernetes/create-kubernetes-executor options)
        mutations [#(update % :metadata dissoc :uid)
                   #(assoc-in % [:metadata :resourceVersion] "")
                   #(assoc-in % [:metadata :name] "another-job")
                   #(assoc-in % [:metadata :namespace] "another-namespace")
                   #(assoc-in % [:spec :activeDeadlineSeconds] (:default capsule-budgets))
                   #(assoc % :kind "Pod")]]
    (with-redefs [client/wait-for-pod-ready reject-io! kubernetes/delete-job reject-io!]
      (doseq [reply (concat (map (fn [altered] (partial fixtures/response altered)) mutations)
                            [(constantly {:exit 1 :err "AlreadyExists"})
                             (constantly {:exit 0 :out "not-json"})])]
        (with-redefs [shell/sh reply]
          (is (result/err? (executor/acquire-environment! instance (random-uuid) {}))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-creation-diagnostics-exclude-provider-and-exception-text
  (let [instance (kubernetes/create-kubernetes-executor {})
        fixture-secret "mock-env-value"
        env-config {:env {:TEST_SECRET fixture-secret}}
        commands (map (partial command-case fixture-secret) provider-error-headers)
        responses (response-cases fixture-secret)]
    (with-redefs [client/wait-for-pod-ready reject-io! kubernetes/delete-job reject-io!]
      (doseq [[reply expected] (concat commands responses)]
        (with-redefs [shell/sh (partial provider-outcome reply)]
          (let [error (:error (executor/acquire-environment! instance (random-uuid) env-config))]
            (is (= :job-creation-unconfirmed (:code error)))
            (is (= expected (:data error)))
            (is (not (str/includes? (pr-str error) fixture-secret)))))))))

(deftest ^{:stratum 2} test-executor-transport-budgets-reach-their-own-boundaries
  (let [settings transport-settings
        instance (kubernetes/create-kubernetes-executor settings)
        submitted (atom [])
        created (atom nil)
        timeout-ms (:kubernetes/creation-request-timeout-ms settings)]
    (with-redefs [shell/sh (partial pods/capture-created! created
                                   (partial capture-request! submitted timeout-ms (:default capsule-budgets)))
                  command/run! (partial expected-owner-readback
                                        (:kubernetes/pod-observation-request-timeout-ms settings) created)
                  client/wait-for-pod-ready (partial expected-readiness settings)]
      (is (result/ok? (executor/acquire-environment! instance (random-uuid) {}))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-job-creation-test))
