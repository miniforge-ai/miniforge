;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-event-projection-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.runner-event-dispatch :as dispatch]
            [ai.miniforge.workflow.runner-phase-event :as phase-event]
            [ai.miniforge.workflow.runner-pr-events :as prs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} pr-info [repo number]
  (let [url (str "https://github.com/" repo "/pull/" number)]
    {:pr/repo repo
     :pr/id number
     :pr/url url}))

(defn- ^{:stratum 0} failed-phase []
  {:status :completed
   :result {:status :error
            :error {:message "failed"
                    :data {:stop-reason :error-data
                           :num-turns 0}}
            :metrics {:stop-reason :metrics
                      :num-turns 9
                      :tool-call-count 0}
            :stop-reason :legacy}
   :duration-ms nil
   :phase/metrics {:duration-ms 20
                   :tokens 7}
   :metrics {:duration-ms 30
             :tokens 0
             :cost-usd 0.0}})

(defn- ^{:stratum 0} raise [value & _]
  (throw+ value))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} diagnostic-precedence-and-explicit-values-survive-projection
  (let [result (failed-phase)
        data (phase-event/data result)]
    (is (= :failure (:outcome data)))
    (is (nil? (:duration-ms data)))
    (is (zero? (:tokens data)))
    (is (zero? (:cost-usd data)))
    (is (= :error-data (get-in data [:meta :stop-reason])))
    (is (zero? (get-in data [:meta :turn-count])))
    (is (zero? (get-in data [:meta :tool-call-count])))
    (is (= (get-in result [:result :error]) (:error data)))
    (is (= :metrics (get-in (phase-event/data (update-in result [:result :error] dissoc :data))
                            [:meta :stop-reason])))
    (is (= :legacy (get-in (phase-event/data (update result :result dissoc :error :metrics))
                           [:meta :stop-reason])))))

(deftest ^{:stratum 1} completion-prs-preserve-first-seen-order-and-reject-incomplete-records
  (let [first-pr (assoc (pr-info "org/one" 1) :title "first")
        second-pr (pr-info "org/two" 2)
        duplicate (assoc first-pr :title "later")
        context {:execution/dag-pr-infos [first-pr second-pr {:pr/id 3}]
                 :workflow/pr-info duplicate}
        projected (prs/completion-prs context)]
    (is (= ["org/one" "org/two"] (mapv :pr/repo projected)))
    (is (= [1 2] (mapv :pr/number projected)))
    (is (= "first" (:pr/title (first projected))))
    (is (= "org/two" (:pr/repo (first (prs/completion-prs
                                      {:workflow/pr-info (dissoc second-pr :pr/repo)})))))
    (is (empty? (prs/completion-prs {:workflow/pr-info {:pr/id 1 :pr/url "pull/1"}})))))

(deftest ^{:stratum 1} legacy-dispatch-preserves-best-effort-and-fatal-error-boundaries
  (let [stream (events/create-event-stream {:sinks []})]
    (doseq [failure [(Exception. "transport failed") {:reason :transport-failed}]]
      (with-redefs [events/publish! (partial raise failure)]
        (let [returned (atom :not-called)
              printed (with-out-str (reset! returned (dispatch/publish-event! stream {})))]
          (is (seq printed))
          (is (nil? @returned)))))
    (with-redefs [events/publish! (partial raise (AssertionError. "fatal"))]
      (is (thrown? AssertionError (dispatch/publish-event! stream {}))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.runner-event-projection-test))
