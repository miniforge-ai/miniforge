(ns ai.miniforge.cli.workflow-selection-config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.workflow-selection-config :as cfg]))

(deftest configured-selection-profiles-test
  (testing "software-factory workflow selection profiles load from resources"
    (let [profiles (cfg/configured-selection-profiles)]
      (is (= :canonical-sdlc-v1 (:comprehensive profiles)))
      (is (= :lean-sdlc-v1 (:fast profiles)))
      (is (= :lean-sdlc-v1 (:default profiles))))))

(deftest resolve-selection-profile-test
  (testing "configured profiles resolve to available workflows"
    (let [available-workflows [{:workflow/id :canonical-sdlc-v1
                                :workflow/phases [{:phase/id :explore}
                                                  {:phase/id :plan}
                                                  {:phase/id :implement}
                                                  {:phase/id :verify}
                                                  {:phase/id :review}
                                                  {:phase/id :release}
                                                  {:phase/id :done}]
                                :workflow/config {:max-total-iterations 50}}
                               {:workflow/id :lean-sdlc-v1
                                :workflow/phases [{:phase/id :plan-design}
                                                  {:phase/id :implement-tdd}
                                                  {:phase/id :review}
                                                  {:phase/id :release}
                                                  {:phase/id :observe}]
                                :workflow/config {:max-total-iterations 20}}]]
      (is (= :canonical-sdlc-v1
             (cfg/resolve-selection-profile :comprehensive available-workflows)))
      (is (= :lean-sdlc-v1
             (cfg/resolve-selection-profile :fast available-workflows)))
      (is (= :lean-sdlc-v1
             (cfg/resolve-selection-profile :default available-workflows))))))
