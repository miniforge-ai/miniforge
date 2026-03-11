(ns ai.miniforge.phase.loader-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]))

(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (f)
    (phase/reset-phase-loader!)))

(deftest configured-phase-namespaces-test
  (testing "phase implementation namespaces are discovered from composed resources"
    (let [phase-namespaces (set (loader/configured-phase-namespaces))]
      (is (contains? phase-namespaces 'ai.miniforge.phase.explore))
      (is (contains? phase-namespaces 'ai.miniforge.phase.plan))
      (is (contains? phase-namespaces 'ai.miniforge.phase.release)))))

(deftest ensure-phase-implementations-loaded-test
  (testing "loading configured phase implementations registers their phase defaults"
    (phase/ensure-phase-implementations-loaded!)
    (let [phases (phase/list-phases)]
      (is (contains? phases :explore))
      (is (contains? phases :plan))
      (is (contains? phases :implement))
      (is (contains? phases :verify))
      (is (contains? phases :review))
      (is (contains? phases :release))
      (is (contains? phases :done)))))
