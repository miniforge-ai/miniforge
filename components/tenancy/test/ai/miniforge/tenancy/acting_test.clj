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
(ns ai.miniforge.tenancy.acting-test
  "The acting context (Ariadne step 3b)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.tenancy.interface :as tenancy]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} configured {:tenancy {:operator-name "chris"}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} an-acting
  []
  (tenancy/establish-acting (tenancy/resolve-operator configured now) now))

(deftest ^{:stratum 1} both-instant-representations-are-accepted-test
  ;; `inst?` admits java.util.Date as readily as java.time.Instant, so a
  ;; caller holding a schema-valid instant may hold either. Assuming
  ;; Instant and calling `str` on a Date yields "Sat Aug 16 ...", which
  ;; is not parseable and would fail validation far from its cause.
  (let [identity (tenancy/resolve-operator configured now)
        from-instant (tenancy/establish-acting identity now)
        from-date (tenancy/establish-acting identity (java.util.Date/from now))]
    (is (= from-instant from-date)
        "Instant and Date for the same moment produce the same context"))
  (testing "and an unparseable stamp yields a context that fails validation"
    (is (not (tenancy/valid-acting?
              (tenancy/establish-acting (tenancy/resolve-operator configured now)
                                        "not-an-instant"))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} acting-carries-ids-not-records-test
  ;; Ids are the stable half. Whole records would mean a resumed run
  ;; replays a display name captured months ago.
  (let [acting (an-acting)
        identity (tenancy/resolve-operator configured now)]
    (is (tenancy/valid-acting? acting))
    (is (= #{:acting/tenant-id :acting/principal-id :acting/established-at}
           (set (keys acting)))
        "closed: exactly the two ids and the instant")
    (is (= (get-in identity [:identity/tenant :tenant/id])
           (:acting/tenant-id acting)))
    (is (= (get-in identity [:identity/principal :principal/id])
           (:acting/principal-id acting)))))

(deftest ^{:stratum 2} both-ids-are-required-test
  ;; A tenant without a principal cannot say who did it; a principal
  ;; without a tenant cannot say who owns the result.
  (let [acting (an-acting)]
    (doseq [k [:acting/tenant-id :acting/principal-id :acting/established-at]]
      (is (not (tenancy/valid-acting? (dissoc acting k)))
          (str "must reject a context missing " k))
      (is (not (tenancy/valid-acting? (assoc acting k nil)))
          (str "must reject a nil " k)))
    (testing "and nothing else may ride along"
      (is (not (tenancy/valid-acting? (assoc acting :acting/extra :x)))))))

(deftest ^{:stratum 2} absence-is-loud-test
  ;; The whole point of the slice. An unowned record created during the
  ;; migration is indistinguishable later from a legitimately anonymous
  ;; one, and there is no such thing as legitimately anonymous here.
  (testing "missing"
    (let [result (tenancy/require-acting {} :execution/acting)]
      (is (anomaly/anomaly? result))
      (is (not (tenancy/valid-acting? result))
          "a refusal must not be mistakable for an acting context")))
  (testing "explicitly nil"
    (is (anomaly/anomaly? (tenancy/require-acting {:execution/acting nil}
                                                  :execution/acting))))
  (testing "present but malformed — a half-filled context is not a context"
    (is (anomaly/anomaly?
         (tenancy/require-acting {:execution/acting {:acting/tenant-id (random-uuid)}}
                                 :execution/acting))))
  (testing "present and valid"
    (let [acting (an-acting)]
      (is (= acting (tenancy/require-acting {:execution/acting acting}
                                            :execution/acting))))))

(deftest ^{:stratum 2} a-spawned-agent-inherits-the-tenant-and-gets-its-own-principal-test
  ;; The asymmetry is what makes a spawned agent containable: revoke the
  ;; lending tenant's grant and the agent's authority ends with it.
  (let [acting (an-acting)
        agent-acting (tenancy/acting-for-agent acting "reviewer")
        principal (tenancy/agent-principal acting "reviewer")]
    (is (tenancy/valid-acting? agent-acting))
    (is (tenancy/valid-principal? principal))
    (is (= (:acting/tenant-id acting) (:acting/tenant-id agent-acting))
        "an agent owns nothing, so it acts under the spawning tenant")
    (is (not= (:acting/principal-id acting) (:acting/principal-id agent-acting))
        "'an agent did it' and 'the operator did it' stay distinguishable")
    (is (= :agent-instance (:principal/kind principal)))
    (is (= (:acting/established-at acting) (:acting/established-at agent-acting))
        "the agent's authority is the run's authority, not a fresh grant")))

(deftest ^{:stratum 2} agent-principals-are-stable-and-distinct-test
  (let [acting (an-acting)]
    (is (= (tenancy/acting-for-agent acting "reviewer")
           (tenancy/acting-for-agent acting "reviewer"))
        "same run, same agent name, same principal")
    (is (not= (:acting/principal-id (tenancy/acting-for-agent acting "reviewer"))
              (:acting/principal-id (tenancy/acting-for-agent acting "implementer")))
        "different agents are different principals")
    (testing "and a different tenant spawning the same agent name differs"
      (let [other (assoc acting :acting/tenant-id (random-uuid))]
        (is (not= (:acting/principal-id (tenancy/acting-for-agent acting "reviewer"))
                  (:acting/principal-id (tenancy/acting-for-agent other "reviewer"))))))))

(deftest ^{:stratum 2} spawning-from-a-bad-context-refuses-test
  ;; Refuse rather than mint an agent principal under a tenant that was
  ;; never established.
  (is (anomaly/anomaly? (tenancy/acting-for-agent {} "reviewer")))
  (is (anomaly/anomaly? (tenancy/acting-for-agent nil "reviewer")))
  (testing "a blank agent name is not a name"
    (is (anomaly/anomaly? (tenancy/acting-for-agent (an-acting) "   ")))))

(deftest ^{:stratum 2} established-at-survives-serialization-test
  ;; The acting context is written into the workflow machine snapshot,
  ;; which stringifies every instant. A field typed `inst?` would go in
  ;; an Instant and come back a String, failing its own validation on
  ;; resume and leaving every resumed run unowned. So the canonical
  ;; representation IS the string.
  (let [acting (an-acting)]
    (is (string? (:acting/established-at acting)))
    (is (tenancy/valid-acting? acting))
    (testing "it round-trips through edn unchanged, which is what the snapshot does"
      (let [round-tripped (edn/read-string (pr-str acting))]
        (is (= acting round-tripped))
        (is (tenancy/valid-acting? round-tripped))))
    (testing "and re-establishing from the stored string is idempotent"
      (is (= acting (tenancy/establish-acting (tenancy/resolve-operator configured now)
                                              (:acting/established-at acting)))))))

(deftest ^{:stratum 2} agent-principal-refuses-rather-than-shaping-junk-test
  ;; `agent-principal` is public API. Returning a Principal-shaped map
  ;; for invalid input would put the burden of validation on every
  ;; caller, and a caller that must remember to validate eventually
  ;; forgets — which is the whole failure mode this component exists to
  ;; prevent.
  (let [acting (an-acting)]
    (is (tenancy/valid-principal? (tenancy/agent-principal acting "reviewer")))
    (testing "a blank name yields no principal, not an unnamed one"
      (doseq [bad ["" "   " nil]]
        (is (anomaly/anomaly? (tenancy/agent-principal acting bad))
            (str "should refuse agent name " (pr-str bad)))))
    (testing "no tenant to inherit means no principal"
      (is (anomaly/anomaly? (tenancy/agent-principal {} "reviewer")))
      (is (anomaly/anomaly?
           (tenancy/agent-principal (dissoc acting :acting/tenant-id) "reviewer"))))
    (testing "and the refusal names the actual cause, not a plausible one"
      ;; A refusal that blames the agent name for a missing tenant sends
      ;; the reader to the wrong place, which costs more than no message.
      (is (re-find #"no tenant"
                   (:anomaly/message (tenancy/agent-principal
                                      (dissoc acting :acting/tenant-id) "reviewer"))))
      (is (re-find #"agent name"
                   (:anomaly/message (tenancy/agent-principal acting "  ")))))))
