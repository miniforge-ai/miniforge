;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-workspace.admission-test
  (:require [ai.miniforge.deliberation-workspace.interface :as board]
            [clojure.test :refer [deftest is]]))

(defn ws []
  {:workspace/version 0 :workspace/log [] :workspace/roles [:proposer :skeptic :synthesizer]
   :workspace/objects {"goal" (board/new-object {:id "goal" :type :goal :statement "solve"
                                               :role :interpreter :activation "seed" :version 0})}})
(defn ctx [role] {:role role :participant role :activation "trusted" :basis 0})
(defn claim [id] {:op :assert-claim :creates [{:id id :type :claim :statement id}]})
(defn submit [state role operations]
  (board/admit state {:tx/operations operations} (assoc (ctx role) :basis (:workspace/version state))))

(deftest runtime-identity-cannot-be-impersonated
  (is (:rejection (board/admit (ws) {:tx/role :synthesizer :tx/operations [(claim "c")]}
                              (ctx :proposer))))
  (let [result (submit (ws) :proposer [(claim "c")])]
    (is (= "trusted" (get-in result [:workspace :workspace/objects "c" :object/activation])))))

(deftest creation-type-and-attestation-are-enforced
  (doseq [spec [{:id "e" :type :evidence :statement "fabricated" :attrs {:source-class :user}}
                {:id "h" :type :constraint :statement "invented" :attrs {:kind :hard}}
                {:id "c" :type :claim :statement "c" :attrs {:policy-clauses []}}]]
    (is (:rejection (submit (ws) :proposer [{:op :assert-claim :creates [spec]}]))))
  (is (:rejection (submit (ws) :proposer
                         [{:op :attach-evidence :targets ["goal"]
                           :creates [{:id "e" :type :evidence :statement "fake"
                                      :attrs {:source-class :execution :receipt "missing"}}]}]))))

(deftest atomic-creation-adoption-and-closure
  (let [ops [{:op :propose-decision :creates [{:id "d" :type :decision :statement "choose A"
                                            :attrs {:choice "design"} :links {:depends-on ["goal"]}}]}
             {:op :accept-decision :targets ["d"]}
             {:op :close-goal :targets ["goal"] :outcome :accepted :links {:resolves ["d"]}}]
        good (submit (ws) :synthesizer ops)
        bad (submit (ws) :synthesizer (conj ops {:op :accept-decision :targets ["goal"]}))]
    (is (nil? (:rejection good)))
    (is (= 1 (get-in good [:workspace :workspace/version])))
    (is (= :accepted (get-in good [:workspace :workspace/objects "goal" :object/status])))
    (is (= 1 (count (get-in good [:workspace :workspace/log]))))
    (is (:rejection bad))
    (is (nil? (:workspace bad)))))

(deftest distinct-contributions-are-not-retries
  (let [first-state (:workspace (submit (ws) :proposer [(claim "c1")]))]
    (is (:workspace (submit first-state :proposer [(claim "c2")])))
    (is (:rejection (submit first-state :proposer [(claim "c1")])))))

(deftest an-actionable-question-can-challenge-an-adopted-decision
  (let [state (:workspace (submit (ws) :synthesizer
                                 [{:op :propose-decision :creates [{:id "d" :type :decision
                                                                   :statement "adopt" :attrs {:choice "x"}}]}
                                  {:op :accept-decision :targets ["d"]}]))
        result (submit state :skeptic [{:op :challenge :targets ["d"]
                                        :reason "Ordering is assumed"
                                        :resolution "Verify ordering against the transport contract"}])]
    (is (:workspace result))
    (is (= :accepted (get-in result [:workspace :workspace/objects "d" :object/status])))
    (is (true? (get-in result [:workspace :workspace/objects "d" :object/contested?])))))

(deftest conflict-history-resolves-on-supersession
  (let [state (:workspace (submit (ws) :proposer
                                 [{:op :assert-claim :creates [{:id "a" :type :claim :statement "a"}
                                                              {:id "b" :type :claim :statement "b"
                                                               :links {:contradicts ["a"]}}]}]))
        resolved (:workspace (submit state :proposer [{:op :supersede :targets ["a"]
                                                       :links {:resolves ["b"]}}]))]
    (is (= :open (get-in state [:workspace/objects "conflict-a-b" :object/status])))
    (is (= :resolved (get-in resolved [:workspace/objects "conflict-a-b" :object/status])))))

(deftest pass-and-rejection-advance-the-schedule
  (let [roles (atom [])
        closed (board/run (assoc (ws) :workspace/budget {:activations 4}
                                      :workspace/closure-reserve 1)
                          (fn [request] (swap! roles conj (:participant request)) nil) {})]
    (is (= [:proposer :skeptic :synthesizer :synthesizer] @roles))
    (is (= :executed (get-in closed [:workspace/termination :termination/closure])))
    (is (= 4 (get-in closed [:workspace/spent :activations])))))

(deftest callbacks-receive-no-raw-workspace-and-links-do-not-reveal-hidden-ids
  (let [state (assoc-in (ws) [:workspace/objects "hidden"]
                       (board/new-object {:id "hidden" :type :claim :statement "secret"
                                          :role :skeptic :activation "seed" :version 0}))
        state (assoc-in state [:workspace/objects "goal" :object/links :supports] #{"hidden"})
        view (board/project state :proposer {:visibility :none})
        seen (atom nil)]
    (board/run (assoc state :workspace/budget {:activations 1}) #(do (reset! seen %) nil) {})
    (is (not (contains? @seen :workspace)))
    (is (empty? (get-in view [:projection/goals 0 :object/links :supports])))))

(deftest new-evidence-can-be-attached-to-an-adopted-claim
  (let [state (:workspace (submit (ws) :proposer [(claim "c")]))
        state (assoc-in state [:workspace/objects "c" :object/status] :accepted)
        result (submit state :proposer
                       [{:op :attach-evidence :targets ["c"] :links {:supports ["e"]}
                         :creates [{:id "e" :type :evidence :statement "contrary observation"
                                    :attrs {:source-class :agent-analysis}}]}])]
    (is (:workspace result))
    (is (= #{"e"} (get-in result [:workspace :workspace/objects "c" :object/links :supports])))))

(deftest a-second-choice-requires-explicit-supersession
  (let [state (:workspace (submit (ws) :synthesizer
                    [{:op :propose-decision :creates [{:id "d1" :type :decision :statement "old"
                                                      :attrs {:choice "same"}}
                                                     {:id "d2" :type :decision :statement "new"
                                                      :attrs {:choice "same"}}]}
                     {:op :accept-decision :targets ["d1"]}]))]
    (is (:rejection (submit state :synthesizer [{:op :accept-decision :targets ["d2"]}])))
    (let [replacement (submit state :synthesizer
                             [{:op :supersede :targets ["d1"] :links {:resolves ["d2"]}}
                              {:op :accept-decision :targets ["d2"]}])]
      (is (= :superseded (get-in replacement [:workspace :workspace/objects "d1" :object/status])))
      (is (= :accepted (get-in replacement [:workspace :workspace/objects "d2" :object/status]))))))

(deftest a-rejected-goal-is-not-success
  (let [state (assoc-in (ws) [:workspace/objects "goal" :object/status] :rejected)
        closed (board/run state (fn [_] (throw (Exception. "must not activate"))) {})]
    (is (= :goals-rejected (get-in closed [:workspace/termination :termination/outcome])))
    (is (not= :success (get-in closed [:workspace/termination :termination/rule])))))

(deftest admission-preserves-external-staleness-while-permitting-its-own-writes
  (let [state (:workspace (submit (ws) :proposer [(claim "c")]))
        result (board/admit state {:tx/operations [{:op :refine-claim :targets ["c"] :statement "stale"}]}
                            (ctx :proposer))]
    (is (= :anomalies.deliberation/stale-basis (get-in result [:rejection :anomaly/subtype])))
    (is (= "c" (get-in state [:workspace/objects "c" :object/statement])))))

(deftest peers-can-propose-soft-constraints-and-reference-existing-evidence
  (let [state (:workspace (submit (ws) :proposer
                    [(claim "c")
                     {:op :add-constraint :creates [{:id "soft" :type :constraint :statement "prefer simple"
                                                     :attrs {:kind :soft}}]}
                     {:op :attach-evidence :targets ["c"]
                      :creates [{:id "e" :type :evidence :statement "analysis"
                                 :attrs {:source-class :agent-analysis}}]}]))
        result (submit state :skeptic [{:op :attach-evidence :targets ["c"] :links {:supports ["e"]}}])]
    (is (:workspace result))
    (is (= #{"e"} (get-in result [:workspace :workspace/objects "c" :object/links :supports])))))
