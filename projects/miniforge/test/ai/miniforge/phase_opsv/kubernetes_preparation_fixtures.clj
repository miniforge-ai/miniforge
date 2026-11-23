;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-preparation-fixtures
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.boundary.kubernetes-client :as client]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as candidate]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-environment-fingerprint-fixtures :as fingerprint]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-settings-test-support :as settings]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as native]
            [ai.miniforge.phase-opsv.native-material-fixtures :as material]
            [ai.miniforge.phase-opsv.kubernetes-preparation :as model]
            [ai.miniforge.phase-opsv.policy :as policy]
            [ai.miniforge.phase-opsv.runtime-context :as runtime]
            [ai.miniforge.phase-opsv.test-support :as support]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} converged []
  (let [selected (last support/ramp-steps)]
    {:opsv/convergence-result {:state {:selected-step selected}}}))

(defn ^{:stratum 0} declaration []
  (assoc (inventory/declaration) :workflow/id (:workflow/id (environment/verification-binding))))

(defn- ^{:stratum 0} request-binding [declared payloads]
  (let [attempt (:verification/id (environment/verification-binding))]
    (assoc (select-keys payloads [:candidate/hash :experiment-pack/hash])
           :verification/id attempt :workflow/id (:workflow/id declared) :runtime/target (:runtime/target declared))))

(defn ^{:stratum 0} replica-expectation [projected minimum maximum]
  (let [replica (first (:comparison/replica-observations projected))
        target (:runtime/target projected)
        template (:replica/template-hash replica)
        pools (get-in projected [:environment-fingerprint :node-pools])
        images (get-in projected [:environment-fingerprint :image-digests])]
    {:replicas/min-count minimum
     :replicas/max-count maximum
     :replica/target target
     :replica/template-hash template
     :replica/node-pools pools
     :replica/image-digests images}))

(defn ^{:stratum 0} adapter-context [ctx input directory measurements settings]
  (let [declared (assoc (:declaration input) :workflow/id (runtime/workflow-id ctx))
        prepare (partial phase/prepare-kubernetes-verification! settings declared)
        observed (model/observation (:projected input))
        measure (partial candidate/measure measurements directory observed)]
    (assoc-in ctx [:execution/opts :opsv/adapter]
              (phase/functional-adapter identity identity prepare measure))))

(defn- ^{:stratum 0} preparation-request [payloads pin]
  (let [bound (get-in pin [:comparison/contract :comparison/binding])]
    (assoc payloads :verification/id (:verification/id bound)
                   :runtime/target (:runtime/target bound)
                   :comparison/pin pin)))

(defn- ^{:stratum 0} capture [calls captured settings declared]
  (swap! calls conj [settings declared])
  captured)

(defn ^{:stratum 0} mismatched-requests [request]
  (let [other (hash/content-hash :different)]
    [(assoc request :verification/id (random-uuid))
     (assoc request :candidate/hash other)
     (assoc request :experiment-pack/hash other)
     (assoc-in request [:runtime/target :cluster] "other")
     (assoc-in request [:runtime/target :namespace] "other")
     (assoc-in request [:runtime/target :namespace-uid] "other")
     (assoc-in request [:runtime/target :workload-uid] "other")
     (assoc-in request [:candidate/policy :operational-policy/version] "2.0.0")
     (assoc-in request [:experiment-pack :experiment-pack/version] "2.0.0")
     (assoc-in request [:comparison/pin :comparison/hash] other)
     (assoc-in request [:comparison/pin :comparison/contract :comparison/changes :config-hash] "other")]))

(defn ^{:stratum 0} without-population-contract [request]
  (let [contract (dissoc (get-in request [:comparison/pin :comparison/contract]) :comparison/replica-contract)
        pin (phase/pin-verification-environment contract)]
    (assoc request :comparison/pin pin)))

(defn ^{:stratum 0} changed-configuration [responses]
  (-> responses
      (assoc-in [wire/config-path "data" "workers"] "8")
      (assoc-in [wire/config-path "metadata" "resourceVersion"] "43")))

(defn- ^{:stratum 0} pending-pod [pod]
  (assoc pod "status" {"phase" "Pending"}))

(defn- ^{:stratum 0} desired-count [replicas resource]
  (assoc-in resource ["spec" "replicas"] replicas))

(defn- ^{:stratum 0} email-pod [pod]
  (update-in pod ["spec" "containers" 0 "env"] (fnil into [])
             (material/literal-environment "person@example.com")))

(defn ^{:stratum 0} with-transport [routes operation]
  (let [calls (atom [])
        options (dissoc (settings/settings) :kubernetes/pod-selector)]
    (with-redefs [transport/start! (partial wire/reply! routes calls)
                  transport/response-fields identity]
      (operation options calls))))

(defn- ^{:stratum 0} prepare-transport [input options calls]
  [(phase/prepare-kubernetes-verification! options (:declaration input) (:request input)) @calls])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} routes []
  (-> (native/routes)
      (update inventory/deployment-path (partial desired-count 2))
      (update-in [inventory/replica-sets-path "items"] (partial mapv (partial desired-count 1)))))

(defn ^{:stratum 1} observe-transport [routes input]
  (with-transport routes (partial prepare-transport input)))

(defn ^{:stratum 1} missing-runtime [responses]
  (update-in responses [inventory/pods-path "items"] (partial mapv pending-pod)))

(defn- ^{:stratum 1} payloads []
  (let [ctx (support/execution-context nil)
        pack (get-in ctx [:execution/input :opsv/experiment-pack])
        candidate (policy/operational-policy ctx (converged))
        candidate-hash (hash/content-hash candidate)
        pack-hash (hash/content-hash pack)]
    {:candidate/policy candidate
     :experiment-pack pack
     :candidate/hash candidate-hash
     :experiment-pack/hash pack-hash}))

(defn- ^{:stratum 1} plan [declared payloads projected]
  (let [bound (request-binding declared payloads)
        baseline (:environment-fingerprint projected)
        candidate (replica-expectation projected 5 6)
        rollback (replica-expectation projected 1 3)]
    {:comparison/binding bound
     :comparison/baseline baseline
     :comparison/changes {}
     :comparison/replica-contract {:candidate candidate
                                 :rollback rollback}}))

(defn ^{:stratum 1} observe [input]
  (let [calls (atom [])]
    (with-redefs [client/observe-verification! (partial capture calls (:capture input))]
      [(phase/prepare-kubernetes-verification! {} (:declaration input) (:request input)) @calls])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} email-routes []
  (update-in (routes) [inventory/pods-path "items"] (partial mapv email-pod)))

(defn ^{:stratum 2} input
  ([] (input (routes)))
  ([responses]
   (let [[capture _calls] (native/capture! responses {})
         bounds (fingerprint/bounds 1 3)
         projected (phase/project-kubernetes-environment-fingerprint capture bounds)
         declared (declaration)
         payloads (payloads)
         planned (plan declared payloads projected)
         pin (phase/pin-verification-environment planned)
         request (preparation-request payloads pin)]
     {:declaration declared
      :request request
      :capture capture
      :projected projected
      :routes responses})))

(comment (input))
