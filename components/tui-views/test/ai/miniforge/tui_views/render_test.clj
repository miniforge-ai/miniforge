;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.tui-views.render-test
  "Regression tests: each top-level view renders a visually distinct
   first row (tab bar) so navigation is recognisable to the user."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.transition :as transition]
   [ai.miniforge.tui-views.view :as view])
  (:import [java.util Date]))

(def ^:private size [80 24])

(defn render-first-line
  "Render a model and return the text of the first (tab-bar) row."
  [m]
  (-> (view/root-view m size)
      layout/buf->strings
      first
      str/trimr))

(deftest top-level-views-have-distinct-tab-bars
  (testing "each top-level view produces a unique first-row label"
    (let [base (model/init-model)
          lines (mapv (fn [v]
                        (render-first-line (transition/switch-view base v)))
                      model/top-level-views)]
      ;; All labels are distinct
      (is (= (count lines) (count (distinct lines)))
          (str "Duplicate tab-bar lines found:\n"
               (str/join "\n" (map-indexed #(str %1 ": " %2) lines))))
      ;; Workflow-list header contains "Workflows"
      (is (str/includes? (nth lines 1) "Workflows")
          (str "Expected 'Workflows' in workflow-list tab bar, got: " (nth lines 1)))
      ;; PR fleet header contains "PR Fleet"
      (is (str/includes? (nth lines 0) "PR Fleet")
          (str "Expected 'PR Fleet' in pr-fleet tab bar, got: " (nth lines 0)))
      ;; Evidence header contains "Evidence"
      (is (str/includes? (nth lines 2) "Evidence")
          (str "Expected 'Evidence' in evidence tab bar, got: " (nth lines 2))))))

(deftest workflow-list-empty-state-is-actionable
  (testing "empty workflow list shows the run command, not generic 'No data'"
    (let [m (transition/switch-view (model/init-model) :workflow-list)
          rendered (str/join "\n" (layout/buf->strings (view/root-view m size)))]
      (is (str/includes? rendered "mf workflow run")
          (str "Expected actionable empty state, got:\n" rendered)))))

(deftest pr-fleet-and-workflow-list-differ
  (testing "pr-fleet and workflow-list render different first rows"
    (let [base (model/init-model)
          pr-fleet-line  (render-first-line (transition/switch-view base :pr-fleet))
          wf-list-line   (render-first-line (transition/switch-view base :workflow-list))]
      (is (not= pr-fleet-line wf-list-line)
          (str "pr-fleet and workflow-list have identical tab bars:\n" pr-fleet-line)))))

(deftest workflow-list-renders-dated-workflows-without-throwing
  ;; Regression: temporal-bucket used (some-> wf-date (.between ChronoUnit/DAYS today))
  ;; which called wf-date.between(ChronoUnit.DAYS, today) — LocalDate has no such method.
  ;; This crashed the render on every keystroke when real workflows had :started-at set.
  (testing "workflow-list with :started-at dates renders without exception"
    (let [now (Date.)
          m (-> (model/init-model)
                (assoc :workflows [{:id "wf1" :name "Running now"  :status :running  :started-at now}
                                   {:id "wf2" :name "Done today"   :status :success  :started-at now}
                                   {:id "wf3" :name "No date"      :status :pending  :started-at nil}])
                (transition/switch-view :workflow-list))
          rendered (str/join "\n" (layout/buf->strings (view/root-view m size)))]
      ;; Header section "Today" must appear (confirms grouping ran without throwing)
      (is (str/includes? rendered "Today")
          (str "Expected 'Today' bucket header, got:\n" rendered))
      ;; Both dated workflows must appear by name
      (is (str/includes? rendered "Running now")
          "Expected 'Running now' workflow in rendered output")
      (is (str/includes? rendered "Done today")
          "Expected 'Done today' workflow in rendered output"))))
