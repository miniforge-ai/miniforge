;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.tui-engine.core-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-engine.core :as core]
   [ai.miniforge.tui-engine.runtime :as runtime]
   [ai.miniforge.tui-engine.screen :as screen]
   [ai.miniforge.tui-engine.layout :as layout]))

(defn test-app
  "Create a minimal test app with mock screen."
  []
  (let [mock (screen/create-mock-screen [40 10])]
    (core/create-app
     {:init   (fn [] {:count 0 :messages []})
      :update (fn [model msg]
                (-> model
                    (update :messages conj msg)
                    (cond->
                      (= msg [:input :key/j]) (update :count inc)
                      (= msg [:input :key/k]) (update :count dec))))
      :view   (fn [model [cols rows]]
                (layout/text [cols rows]
                             (str "Count: " (:count model))))
      :screen mock})))

(deftest create-app-test
  (testing "App initializes with model from init fn"
    (let [app (test-app)]
      (is (= 0 (:count (runtime/get-model app))))
      (is (= [] (:messages (runtime/get-model app)))))))

(deftest dispatch-test
  (testing "Dispatch calls update and re-renders"
    (let [app (test-app)]
      ;; Need to start screen for rendering to work
      (screen/start-screen! (:screen @app))
      (runtime/dispatch! app [:input :key/j])
      (is (= 1 (:count (runtime/get-model app))))
      (is (= [[:input :key/j]] (:messages (runtime/get-model app))))
      (screen/stop-screen! (:screen @app))))

  (testing "Multiple dispatches accumulate"
    (let [app (test-app)]
      (screen/start-screen! (:screen @app))
      (runtime/dispatch! app [:input :key/j])
      (runtime/dispatch! app [:input :key/j])
      (runtime/dispatch! app [:input :key/k])
      (is (= 1 (:count (runtime/get-model app))))
      (screen/stop-screen! (:screen @app)))))

(deftest view-renders-to-screen-test
  (testing "View output appears on mock screen"
    (let [mock (screen/create-mock-screen [40 10])
          app (core/create-app
               {:init   (fn [] {:label "Hello TUI"})
                :update (fn [model _] model)
                :view   (fn [model [cols rows]]
                          (layout/text [cols rows] (:label model)))
                :screen mock})]
      (screen/start-screen! mock)
      (swap! app core/render!)
      (let [line (screen/mock-read-line mock 0 40)]
        (is (str/includes? line "Hello TUI")))
      (screen/stop-screen! mock))))

(deftest get-model-returns-snapshot-test
  (testing "get-model returns current state"
    (let [app (test-app)]
      (is (= {:count 0 :messages []} (runtime/get-model app))))))

(deftest elm-loop-with-mock-input-test
  (testing "Full Elm loop: input -> update -> view"
    (let [mock (screen/create-mock-screen [40 10])
          updates (atom [])
          app (core/create-app
               {:init   (fn [] {:value "initial"})
                :update (fn [model msg]
                          (swap! updates conj msg)
                          (case (second msg)
                            :key/j (assoc model :value "pressed-j")
                            model))
                :view   (fn [model [cols rows]]
                          (layout/text [cols rows] (:value model)))
                :screen mock})]
      (screen/start-screen! mock)
      (runtime/dispatch! app [:input :key/j])
      (is (= "pressed-j" (:value (runtime/get-model app))))
      (is (= [[:input :key/j]] @updates))
      (let [line (screen/mock-read-line mock 0 40)]
        (is (str/includes? line "pressed-j")))
      (screen/stop-screen! mock))))

(deftest cell-level-diffing-test
  (testing "Second render with identical model writes zero cells (no flash)"
    (let [mock (screen/create-mock-screen [40 10])
          app (core/create-app
               {:init   (fn [] {:label "static"})
                :update (fn [model _] model)
                :view   (fn [model [cols rows]]
                          (layout/text [cols rows] (:label model)))
                :screen mock})]
      (screen/start-screen! mock)
      ;; First render writes all cells
      (swap! app core/render!)
      (let [first-count (screen/mock-get-put-count mock)]
        (is (pos? first-count) "First render should write cells"))
      ;; Reset counter
      (screen/mock-reset-put-count! mock)
      ;; Second render with identical model should write zero cells
      (swap! app core/render!)
      (is (zero? (screen/mock-get-put-count mock))
          "Identical model should produce zero put-string! calls")
      (screen/stop-screen! mock)))

  (testing "Render after model change only writes changed cells"
    (let [mock (screen/create-mock-screen [40 10])
          model-atom (atom {:label "before"})
          app (core/create-app
               {:init   (fn [] @model-atom)
                :update (fn [_ msg] msg)
                :view   (fn [model [cols rows]]
                          (layout/text [cols rows] (:label model)))
                :screen mock})]
      (screen/start-screen! mock)
      ;; First render
      (swap! app core/render!)
      (screen/mock-reset-put-count! mock)
      ;; Change model and re-render
      (runtime/dispatch! app {:label "after!"})
      (let [changed-count (screen/mock-get-put-count mock)]
        ;; Should write fewer cells than a full screen
        (is (pos? changed-count) "Changed model should write some cells")
        (is (< changed-count 400) "Should write far fewer than 400 cells (40×10)"))
      (screen/stop-screen! mock))))

(deftest side-effect-dispatch-test
  (testing "Side-effect is stripped from model and executed async"
    (let [effect-result (promise)
          mock (screen/create-mock-screen [40 10])
          app (core/create-app
               {:init   (fn [] {:value "init"})
                :update (fn [model msg]
                          (case (first msg)
                            :trigger (assoc model :side-effect {:type :test-fx}
                                                  :value "triggered")
                            :fx-done (assoc model :value (:result (second msg)))
                            model))
                :view   (fn [model [cols rows]]
                          (layout/text [cols rows] (:value model)))
                :effect-handler
                (fn [effect]
                  (deliver effect-result effect)
                  [:fx-done {:result "from-effect"}])
                :screen mock})]
      (screen/start-screen! mock)
      (runtime/dispatch! app [:trigger nil])
      ;; Model should NOT contain :side-effect (stripped by runtime)
      (is (nil? (:side-effect (runtime/get-model app))))
      ;; Wait for effect to complete (effect runs on background thread)
      (let [fx (deref effect-result 2000 :timeout)]
        (is (= {:type :test-fx} fx)))
      ;; Wait for effect result dispatch to propagate
      (Thread/sleep 200)
      ;; After the effect completes and dispatches back, the model
      ;; should reflect the final state from the effect handler result
      (is (= "from-effect" (:value (runtime/get-model app))))
      (screen/stop-screen! mock)))

  (testing "No effect-handler means side-effects are silently ignored"
    (let [mock (screen/create-mock-screen [40 10])
          app (core/create-app
               {:init   (fn [] {:value "init"})
                :update (fn [model _]
                          (assoc model :side-effect {:type :ignored}
                                       :value "set"))
                :view   (fn [model [cols rows]]
                          (layout/text [cols rows] (:value model)))
                :screen mock})]
      (screen/start-screen! mock)
      (runtime/dispatch! app [:any nil])
      ;; Side-effect stripped, value updated, no crash
      (is (nil? (:side-effect (runtime/get-model app))))
      (is (= "set" (:value (runtime/get-model app))))
      (screen/stop-screen! mock))))
