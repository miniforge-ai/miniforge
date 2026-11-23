;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-job-creation-test
  (:require [ai.miniforge.dag-executor.protocols.executor :as executor]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes :as kubernetes]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-client :as client]
            [ai.miniforge.dag-executor.result :as result]
            [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} identity-metadata
  "Mirror the UID and resourceVersion strings emitted by the API server."
  {:uid "observed-job-uid"
   :resourceVersion "observed-creation-version"})

(def ^{:stratum 0 :private true} capsule-budgets
  "Pin the shipped default independently and use distinct executor/task overrides."
  {:default 3600
   :executor 5400
   :task 7200})

(def ^{:stratum 0 :private true} transport-settings
  "Distinct request, readiness and poll budgets expose cross-wired settings."
  {:kubernetes/creation-request-timeout-ms 4500
   :kubernetes/pod-ready-timeout-ms 16000
   :kubernetes/pod-ready-poll-interval-ms 500})

(defn- ^{:stratum 0} capsule-resources [budget]
  {:resources/timeout-seconds budget})

(defn- ^{:stratum 0} task-options [resources]
  {:resources resources})

(defn- ^{:stratum 0} provider-reply [exit output]
  {:exit exit
   :out output})

(defn- ^{:stratum 0} requested [args]
  (json/parse-string-strict (last args) true))

(defn- ^{:stratum 0} readiness [& _]
  (result/ok {:pod-name "mock-capsule-pod"}))

(defn- ^{:stratum 0} reject-io! [& _] (throw (AssertionError.)))

(defn- ^{:stratum 0} invalid-setting [options]
  (get-in (executor/acquire-environment!
            (kubernetes/create-kubernetes-executor options) (random-uuid) {})
          [:error :data :config/key]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} response [args altered]
  (let [job (update (requested args) :metadata merge identity-metadata)]
    (provider-reply 0 (json/generate-string (altered job)))))

(deftest ^{:stratum 1} invalid-capsule-budgets-fail-before-provider-io
  (let [instance (kubernetes/create-kubernetes-executor {})]
    (with-redefs [shell/sh reject-io!]
      (doseq [invalid [nil false (str (:default capsule-budgets))
                       (double (:default capsule-budgets)) 0 -1 (inc' Long/MAX_VALUE)]
              :let [budget (capsule-resources invalid)]]
        (is (= :resources/timeout-seconds (invalid-setting budget)))
        (is (= :invalid-capsule-deadline
               (get-in (executor/acquire-environment! instance (random-uuid) (task-options budget))
                       [:error :code])))))))

(deftest ^{:stratum 1} invalid-transport-budgets-are-rejected-at-configuration
  (with-redefs [shell/sh reject-io!]
    (doseq [key [:kubernetes/creation-request-timeout-ms
                 :kubernetes/pod-ready-timeout-ms
                 :kubernetes/pod-ready-poll-interval-ms]
            invalid [nil false 0 1.5 (inc Integer/MAX_VALUE)]]
      (is (= key (invalid-setting {key invalid}))))))

(deftest ^{:stratum 1} creation-diagnostics-classify-without-copying-provider-or-exception-text
  (let [instance (kubernetes/create-kubernetes-executor {})
        fixture-secret "mock-env-value"
        env-config {:env {:TEST_SECRET fixture-secret}}
        command-cases
        (for [[header reason] [["Error from server (AlreadyExists): " :already-exists]
                               ["Error from server (Forbidden): " :forbidden]
                               ["error: You must be logged in to the server (" :unauthorized]
                               ["The Job \"capsule\" is invalid: " :invalid-job]
                               ["unrecognized: Error from server (Forbidden): " :provider-command-failed]]]
          [{:exit 1 :err (str header fixture-secret) :out fixture-secret}
           {:reason reason :exit-code 1}])
        response-cases
        [[{:exit 0 :out fixture-secret} {:reason :malformed-response :exit-code 0}]
         [{:exit 0 :out (json/generate-string {:unexpected fixture-secret})} {:reason :receipt-mismatch :exit-code 0}]
         [(java.io.IOException. fixture-secret) {:reason :command-io-failed}]]]
    (with-redefs [client/wait-for-pod-ready reject-io! kubernetes/delete-job reject-io!]
      (doseq [[reply expected] (concat command-cases response-cases)]
        (with-redefs [shell/sh (fn [& _] (if (instance? Exception reply) (throw reply) reply))]
          (let [error (:error (executor/acquire-environment! instance (random-uuid) env-config))]
            (is (= :job-creation-unconfirmed (:code error)))
            (is (= expected (:data error)))
            (is (not (str/includes? (pr-str error) fixture-secret)))))))))

(deftest ^{:stratum 1} interrupted-job-submission-preserves-cancellation
  (let [instance (kubernetes/create-kubernetes-executor {})]
    (with-redefs [shell/sh (fn [& _] (throw (InterruptedException.)))
                  client/wait-for-pod-ready reject-io!
                  kubernetes/delete-job reject-io!]
      (try+
        (let [error (:error (executor/acquire-environment! instance (random-uuid) {}))
              interrupted? (Thread/interrupted)]
          (is (= :job-creation-unconfirmed (:code error)))
          (is (= {:reason :interrupted} (:data error)))
          (is interrupted?))
        (finally (Thread/interrupted))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} acquire-creates-once-and-retains-the-exact-job-receipt
  (let [instance (kubernetes/create-kubernetes-executor {:namespace "capsules"})
        submitted (atom 0)]
    (with-redefs [shell/sh (fn [& args]
                            (swap! submitted inc)
                            (is (= "create" (second args)))
                            (is (some #{"--request-timeout=30000ms"} args))
                            (is (= (:default capsule-budgets) (get-in (requested args) [:spec :activeDeadlineSeconds])))
                            (response args identity))
                  client/wait-for-pod-ready readiness
                  kubernetes/delete-job reject-io!]
      (let [acquired (executor/acquire-environment! instance (random-uuid) {})
            metadata (get-in acquired [:data :metadata])]
        (is (result/ok? acquired))
        (is (= 1 @submitted))
        (is (= (:uid identity-metadata) (:job-uid metadata)))
        (is (= (:resourceVersion identity-metadata) (:job-creation-resource-version metadata)))
        (is (= "capsules" (:namespace metadata)))))))

(deftest ^{:stratum 2} explicit-capsule-budgets-are-submitted-and-confirmed
  (let [instance (kubernetes/create-kubernetes-executor (capsule-resources (:executor capsule-budgets)))
        submitted (atom [])]
    (with-redefs [shell/sh (fn [& args]
                            (swap! submitted conj (get-in (requested args) [:spec :activeDeadlineSeconds]))
                            (response args identity))
                  client/wait-for-pod-ready readiness]
      (is (result/ok? (executor/acquire-environment! instance (random-uuid) {})))
      (is (result/ok? (executor/acquire-environment! instance (random-uuid)
                                                     (task-options (capsule-resources (:task capsule-budgets))))))
      (is (= ((juxt :executor :task) capsule-budgets) @submitted)))))

(deftest ^{:stratum 2} executor-transport-budgets-reach-their-own-boundaries
  (let [settings transport-settings
        instance (kubernetes/create-kubernetes-executor settings)]
    (with-redefs [shell/sh (fn [& args]
                            (is (some #{"--request-timeout=4500ms"} args))
                            (is (= (:default capsule-budgets) (get-in (requested args) [:spec :activeDeadlineSeconds])))
                            (response args identity))
                  client/wait-for-pod-ready (fn [_ _ _ effective]
                                              (is (= settings (select-keys effective (keys settings))))
                                              (readiness))]
      (is (result/ok? (executor/acquire-environment! instance (random-uuid) {}))))))

(deftest ^{:stratum 2} uncertain-job-creation-is-never-adopted-or-cleaned-up-by-name
  (let [options (assoc (capsule-resources (:executor capsule-budgets)) :namespace "capsules")
        instance (kubernetes/create-kubernetes-executor options)
        mutations [#(update % :metadata dissoc :uid)
                   #(assoc-in % [:metadata :resourceVersion] "")
                   #(assoc-in % [:metadata :name] "another-job")
                   #(assoc-in % [:metadata :namespace] "another-namespace")
                   #(assoc-in % [:spec :activeDeadlineSeconds] (:default capsule-budgets))
                   #(assoc % :kind "Pod")]]
    (with-redefs [client/wait-for-pod-ready reject-io! kubernetes/delete-job reject-io!]
      (doseq [reply (concat (map (fn [altered] (fn [& args] (response args altered))) mutations)
                            [(constantly {:exit 1 :err "AlreadyExists"})
                             (constantly {:exit 0 :out "not-json"})])]
        (with-redefs [shell/sh reply]
          (is (result/err? (executor/acquire-environment! instance (random-uuid) {}))))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-job-creation-test))
