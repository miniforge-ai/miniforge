#!/usr/bin/env bb
;; Unit-test the pure-data layer of refresh-cost-table.bb:
;; transform + upstream-pricing. Network fetch isn't tested here
;; (CI smoke-runs the live fetch via the actual workflow run);
;; this test pins the math + the unmatched-falls-back-to-current
;; behaviour.

(load-file "scripts/refresh-cost-table.bb")

(require '[clojure.test :as test :refer [deftest is testing]])

(def synthetic-upstream
  "Subset of what litellm's JSON looks like; only the fields the
   transform actually reads. Cheshire parses with keyword keys
   when the script does — mirror that here."
  {:claude-sonnet-4-5
   {:input_cost_per_token 0.000003   ; $3.00 / 1M
    :output_cost_per_token 0.000015} ; $15.00 / 1M
   :claude-opus-4-1-20250805
   {:input_cost_per_token 0.000005   ; $5.00 / 1M
    :output_cost_per_token 0.000025} ; $25.00 / 1M
   :gpt-5-pro
   {:input_cost_per_token 0.000015   ; $15.00 / 1M
    :output_cost_per_token 0.000075} ; $75.00 / 1M
   :gemini-2.5-flash
   {:input_cost_per_token 0.0000003  ; $0.30 / 1M
    :output_cost_per_token 0.0000025} ; $2.50 / 1M
   :a-model-with-no-price-fields
   {:context_window 200000}})

(def synthetic-mapping
  {"claude-sonnet-4-6"   "claude-sonnet-4-5"
   "claude-opus-4-7"     "claude-opus-4-1-20250805"
   "gpt-5.4-pro"         "gpt-5-pro"})

;; ----------------------------------------------------------------- upstream-pricing

(deftest upstream-pricing-via-mapping-test
  (testing "Model lookup goes through the mapping table when present
            and resolves the upstream entry's cost-per-token fields."
    (is (= {:input-per-1m 3.0 :output-per-1m 15.0}
           (upstream-pricing synthetic-upstream synthetic-mapping
                             "claude-sonnet-4-6"))
        "sonnet-4-6 -> sonnet-4-5 via mapping -> $3/$15 per 1M")))

(deftest upstream-pricing-falls-back-to-direct-key-test
  (testing "When no mapping entry exists, the transform tries the
            our-id as the upstream key directly. Gemini ids match
            upstream verbatim — no mapping needed."
    (is (= {:input-per-1m 0.3 :output-per-1m 2.5}
           (upstream-pricing synthetic-upstream {}
                             "gemini-2.5-flash"))
        "direct-key fallback resolves gemini-2.5-flash")))

(deftest upstream-pricing-nil-when-no-match-test
  (testing "Neither mapping NOR direct-key hit returns nil so the
            caller can fold the miss into the unmatched bucket."
    (is (nil? (upstream-pricing synthetic-upstream synthetic-mapping
                                "completely-unknown-model")))))

(deftest upstream-pricing-nil-when-entry-missing-prices-test
  (testing "Upstream may list a model with no cost-per-token fields
            (research previews, deprecated entries). Treat the
            same as no match — never emit a {0.0 0.0} bogus price."
    (is (nil? (upstream-pricing synthetic-upstream {}
                                "a-model-with-no-price-fields")))))

;; ----------------------------------------------------------------- transform

(deftest transform-refreshes-matched-and-preserves-unmatched-test
  (testing "Each entry in the current table is refreshed against
            upstream OR kept at its current price if upstream
            doesn't cover it. Unmatched ids surface in :unmatched
            so the operator can update the mapping or accept the
            stale price."
    (let [current {:pricing/by-model-id
                   {"claude-sonnet-4-6"     {:input-per-1m 999 :output-per-1m 999}
                    "gemini-2.5-flash"      {:input-per-1m 999 :output-per-1m 999}
                    "free-local-model"      {:input-per-1m 0.0 :output-per-1m 0.0}}}
          {:keys [refreshed unmatched changed]}
          (transform current synthetic-mapping synthetic-upstream)]
      (is (= {:input-per-1m 3.0 :output-per-1m 15.0}
             (get refreshed "claude-sonnet-4-6"))
          "claude-sonnet-4-6 refreshed via mapping")
      (is (= {:input-per-1m 0.3 :output-per-1m 2.5}
             (get refreshed "gemini-2.5-flash"))
          "gemini refreshed via direct key")
      (is (= {:input-per-1m 0.0 :output-per-1m 0.0}
             (get refreshed "free-local-model"))
          "free-local-model has no upstream match — kept verbatim")
      (is (= ["free-local-model"] unmatched)
          ":unmatched names what kept its prior price")
      (is (= 2 (count changed))
          "two models had refreshed prices; one unchanged stays out
           of :changed even though it WAS refreshed (same value)"))))

(deftest transform-only-flags-actual-value-changes-test
  (testing ":changed only lists entries whose price actually moved.
            Re-running the ETL when nothing upstream changed must
            be a no-op — otherwise the auto-PR workflow opens
            empty PRs every cycle."
    (let [;; Current prices already match what upstream says.
          current {:pricing/by-model-id
                   {"claude-sonnet-4-6" {:input-per-1m 3.0 :output-per-1m 15.0}
                    "gemini-2.5-flash"  {:input-per-1m 0.3 :output-per-1m 2.5}}}
          {:keys [changed unmatched]}
          (transform current synthetic-mapping synthetic-upstream)]
      (is (empty? changed)
          "no price moved → :changed is empty")
      (is (empty? unmatched)
          "all entries matched upstream"))))

(deftest transform-empty-current-table-test
  (testing "Empty cost-table → empty refresh (no work). Defensive
            shape — operator may have wiped the table to start
            fresh; ETL should handle it without throwing."
    (let [{:keys [refreshed unmatched changed]}
          (transform {:pricing/by-model-id {}}
                     synthetic-mapping
                     synthetic-upstream)]
      (is (empty? refreshed))
      (is (empty? unmatched))
      (is (empty? changed)))))

;; ----------------------------------------------------------------- extract-preamble

(deftest extract-preamble-pulls-text-before-first-brace-test
  (testing "Pre-map text is the file's comment header — license,
            documentation, provider-group dividers. extract-preamble
            grabs everything up to (but not including) the first `{`.
            Real cost-table.edn shape: many comment lines, then the
            map opener — comments must round-trip across rewrites."
    (let [raw (str ";; License header line 1\n"
                   ";; License header line 2\n"
                   ";;\n"
                   ";; Documentation block\n"
                   "\n"
                   "{:pricing/by-model-id {\"a\" {:input-per-1m 1}}}\n")]
      (is (= (str ";; License header line 1\n"
                  ";; License header line 2\n"
                  ";;\n"
                  ";; Documentation block\n"
                  "\n")
             (extract-preamble raw))
          "everything up to the first `{` is preserved verbatim"))))

(deftest extract-preamble-empty-when-no-comments-test
  (testing "EDN file with no pre-map content (the file starts with
            `{` directly) yields an empty preamble — preserves the
            existing behaviour of writing a clean EDN map without
            phantom leading whitespace."
    (is (= "" (extract-preamble "{:pricing/by-model-id {}}\n")))))

(deftest extract-preamble-handles-nil-and-no-brace-test
  (testing "Defensive: nil input (missing file) and brace-less
            input (corrupted file) both return empty string so the
            caller still produces a well-formed EDN map."
    (is (= "" (extract-preamble nil))
        "nil → empty preamble, no NPE")
    (is (= "" (extract-preamble ";; comment with no map content\n"))
        "no `{` → empty preamble, caller's data block still writes")))

;; ----------------------------------------------------------------- Driver

(let [{:keys [fail error]} (test/run-tests 'user)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
