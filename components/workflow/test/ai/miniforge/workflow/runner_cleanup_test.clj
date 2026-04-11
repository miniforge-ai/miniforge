;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.workflow.runner-cleanup-test
  "Tests for post-workflow cleanup hooks."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.runner-cleanup :as cleanup]))

;; ============================================================================
;; Signal factories (accessed via private fn resolution)
;; ============================================================================

(def ^:private make-completion-signal
  (var-get (ns-resolve 'ai.miniforge.workflow.runner-cleanup 'make-completion-signal)))

(def ^:private make-exception-signal
  (var-get (ns-resolve 'ai.miniforge.workflow.runner-cleanup 'make-exception-signal)))

(deftest make-completion-signal-success-test
  (testing "builds :workflow-complete signal for succeeded context"
    (let [ctx {:execution/status :completed
               :execution/id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
               :execution/phase-results {:plan {:status :completed}}
               :execution/metrics {:tokens 100}}
          signal (make-completion-signal ctx)]
      (is (= :workflow-complete (:signal/type signal)))
      (is (= (:execution/id ctx) (:workflow-id signal)))
      (is (inst? (:timestamp signal))))))

(deftest make-completion-signal-failure-test
  (testing "builds :workflow-failed signal for failed context"
    (let [ctx {:execution/status :failed
               :execution/id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
               :execution/errors [{:type :gate-failed}]}
          signal (make-completion-signal ctx)]
      (is (= :workflow-failed (:signal/type signal))))))

(deftest make-exception-signal-test
  (testing "builds minimal failure signal from workflow and exception"
    (let [wf {:workflow/id :test-wf}
          ex (ex-info "boom" {:code 500})
          signal (make-exception-signal wf ex)]
      (is (= :workflow-failed (:signal/type signal)))
      (is (= :test-wf (:workflow-id signal)))
      (is (= "boom" (:error signal))))))

(deftest make-exception-signal-nil-exception-test
  (testing "handles nil exception gracefully"
    (let [signal (make-exception-signal {:workflow/id :w} nil)]
      (is (nil? (:error signal))))))

;; ============================================================================
;; observe-workflow-signal!
;; ============================================================================

(deftest observe-workflow-signal-calls-observer-test
  (testing "passes signal to observe-signal-fn"
    (let [captured (atom nil)
          opts {:observe-signal-fn (fn [sig] (reset! captured sig))}
          ctx {:execution/status :completed
               :execution/id (random-uuid)}]
      (cleanup/observe-workflow-signal! opts ctx {} nil)
      (is (= :workflow-complete (:signal/type @captured))))))

(deftest observe-workflow-signal-noop-without-fn-test
  (testing "no-ops when observe-signal-fn is absent"
    (is (nil? (cleanup/observe-workflow-signal! {} {:execution/status :completed} {} nil)))))

;; ============================================================================
;; promote-mature-learnings!
;; ============================================================================

(deftest promote-mature-learnings-nil-store-test
  (testing "returns empty vector when knowledge-store is nil"
    (is (= [] (cleanup/promote-mature-learnings! nil)))))
