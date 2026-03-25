(ns ai.miniforge.decision.interface-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.decision.interface :as decision]))

;------------------------------------------------------------------------------ Checkpoint tests

(deftest create-control-plane-checkpoint-test
  (testing "normalizes control-plane decision requests"
    (let [agent-id (random-uuid)
          checkpoint (decision/create-control-plane-checkpoint
                      agent-id
                      "Choose a merge strategy"
                      {:type :choice
                       :priority :high
                       :options ["squash" "rebase"]})]
      (is (= :control-plane-agent (get-in checkpoint [:source :kind])))
      (is (= agent-id (get-in checkpoint [:source :agent-id])))
      (is (= :pending (:checkpoint/status checkpoint)))
      (is (= :implementation-pattern-choice
             (get-in checkpoint [:proposal :decision-class])))
      (is (= 2 (count (get-in checkpoint [:proposal :alternatives])))))))

(deftest create-loop-escalation-checkpoint-test
  (testing "normalizes loop escalation state"
    (let [loop-state {:loop/id (random-uuid)
                      :loop/state :escalated
                      :loop/iteration 4
                      :loop/errors [{:message "Missing paren"}]
                      :loop/task {:task/id (random-uuid)
                                  :task/type :implement}
                      :loop/termination {:reason :max-iterations}}
          checkpoint (decision/create-loop-escalation-checkpoint loop-state {})]
      (is (= :loop-escalation (get-in checkpoint [:source :kind])))
      (is (= :repair-escalation (get-in checkpoint [:proposal :decision-class])))
      (is (= 4 (get-in checkpoint [:context :loop/iteration])))
      (is (= :medium (get-in checkpoint [:risk :tier]))))))

(deftest resolve-checkpoint-and-update-episode-test
  (testing "resolved checkpoints update the episode shell"
    (let [checkpoint (decision/create-control-plane-checkpoint
                      (random-uuid)
                      "Approve?"
                      {:type :approval})
          episode (decision/create-episode checkpoint)
          resolved (decision/resolve-checkpoint checkpoint
                                                {:type :approve
                                                 :value "yes"
                                                 :authority-role :human})
          updated (decision/update-episode episode resolved)]
      (is (= :resolved (:checkpoint/status resolved)))
      (is (= :approve (get-in updated [:supervision :type])))
      (is (= :resolved (:episode/status updated))))))
