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
   [ai.miniforge.tui-engine.screen :as screen]
   [ai.miniforge.tui-engine.layout :as layout]))

(defn- test-app
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
      (is (= 0 (:count (core/get-model app))))
      (is (= [] (:messages (core/get-model app)))))))

(deftest dispatch-test
  (testing "Dispatch calls update and re-renders"
    (let [app (test-app)]
      ;; Need to start screen for rendering to work
      (screen/start-screen! (:screen @app))
      (core/dispatch! app [:input :key/j])
      (is (= 1 (:count (core/get-model app))))
      (is (= [[:input :key/j]] (:messages (core/get-model app))))
      (screen/stop-screen! (:screen @app))))

  (testing "Multiple dispatches accumulate"
    (let [app (test-app)]
      (screen/start-screen! (:screen @app))
      (core/dispatch! app [:input :key/j])
      (core/dispatch! app [:input :key/j])
      (core/dispatch! app [:input :key/k])
      (is (= 1 (:count (core/get-model app))))
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
      (is (= {:count 0 :messages []} (core/get-model app))))))

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
      (core/dispatch! app [:input :key/j])
      (is (= "pressed-j" (:value (core/get-model app))))
      (is (= [[:input :key/j]] @updates))
      (let [line (screen/mock-read-line mock 0 40)]
        (is (str/includes? line "pressed-j")))
      (screen/stop-screen! mock))))
