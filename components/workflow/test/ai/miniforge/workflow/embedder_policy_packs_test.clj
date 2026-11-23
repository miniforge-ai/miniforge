;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.embedder-policy-packs-test
  "run-pipeline hands an embedder's :policy-packs to the gates it runs."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.workflow.checkpoint-test-support :as checkpoint]
            [ai.miniforge.workflow.isolation-test-support :as isolation]
            [ai.miniforge.workflow.runner :as runner]
            [clojure.test :refer [deftest is use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} probe :embedder-policy-packs-test/probe)

(def ^{:stratum 0} embedder-pack
  {:pack/id "embedder-pack"
   :pack/version "2026.10.29"
   :pack/rules [{:rule/id :embedder/no-todo
                 :rule/severity :major
                 :rule/detection {:type :content-scan :patterns ["TODO"]}
                 :rule/enforcement {:action :hard-halt :message "no TODO markers"}}]})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} enter-probe
  "Complete with one source file carrying a TODO marker."
  [ctx]
  (assoc ctx :phase {:name probe :status :completed :started-at 0
                     :result {:status :success
                              :output {:artifact/path "src/probe.clj"
                                       :artifact/content "(ns probe) ; TODO"}}}))

(def ^{:stratum 1} workflow
  {:workflow/id :embedder-policy-packs-test
   :workflow/version "1.0.0"
   :workflow/pipeline [{:phase probe :gates [:policy-pack]}]})

;------------------------------------------------------------------------------ Layer 2

(defmethod ^{:stratum 2} registry/get-phase-interceptor probe
  [config]
  {:name probe :config (registry/merge-with-defaults config) :enter enter-probe})

(deftest ^{:stratum 2} a-supplied-pack-is-the-one-the-gates-evaluate
  (checkpoint/call-with-temp-checkpoint-root
   (fn [root]
     (let [result (runner/run-pipeline workflow {:task "probe"}
                                       {:event-stream (events/create-event-stream {:sinks []})
                                        :policy-packs [embedder-pack]
                                        :checkpoint/root root
                                        :skip-lifecycle-events true})
           failures (get-in result [:execution/phase-results probe :phase/gate-failures])]
       (is (= :failed (:execution/status result)))
       (is (= [:embedder/no-todo] (mapv :code (mapcat :errors failures)))
           "the embedder's rule blocked, not the no-packs refusal")))))

(registry/register-phase-defaults! probe {:phase probe :gates [] :budget {:iterations 1}})

(use-fixtures :once isolation/with-isolated-host)

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.embedder-policy-packs-test))
