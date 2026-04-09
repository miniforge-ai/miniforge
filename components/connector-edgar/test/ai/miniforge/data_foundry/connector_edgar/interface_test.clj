(ns ai.miniforge.data-foundry.connector-edgar.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.data-foundry.connector-edgar.impl :as impl]))

(deftest connect-validates-config-test
  (testing "do-connect requires form-type, user-agent, aggregation"
    (is (thrown? Exception (impl/do-connect {} nil)))
    (is (thrown? Exception (impl/do-connect {:edgar/form-type "4"} nil)))
    (is (thrown? Exception
                (impl/do-connect {:edgar/form-type "4"
                                  :edgar/user-agent "Test/1.0"} nil)))))

(deftest connect-close-lifecycle-test
  (testing "connect and close work"
    (let [config {:edgar/form-type   "4"
                  :edgar/user-agent  "Test/1.0 test@test.com"
                  :edgar/aggregation :monthly-buy-sell-ratio}
          result (impl/do-connect config nil)]
      (is (string? (:connection/handle result)))
      (is (= :connected (:connector/status result)))
      (let [close-result (impl/do-close (:connection/handle result))]
        (is (= :closed (:connector/status close-result)))))))

(deftest extract-form4-transactions-test
  (testing "extracts P and S transactions from Form 4 XML"
    (let [xml-str "<?xml version=\"1.0\"?>
<ownershipDocument>
  <nonDerivativeTable>
    <nonDerivativeTransaction>
      <transactionAmounts>
        <transactionShares><value>1000</value></transactionShares>
        <transactionPricePerShare><value>50.00</value></transactionPricePerShare>
        <transactionAcquiredDisposedCode><value>A</value></transactionAcquiredDisposedCode>
      </transactionAmounts>
      <transactionCoding>
        <transactionCode>P</transactionCode>
      </transactionCoding>
    </nonDerivativeTransaction>
    <nonDerivativeTransaction>
      <transactionAmounts>
        <transactionShares><value>500</value></transactionShares>
        <transactionPricePerShare><value>55.00</value></transactionPricePerShare>
        <transactionAcquiredDisposedCode><value>D</value></transactionAcquiredDisposedCode>
      </transactionAmounts>
      <transactionCoding>
        <transactionCode>S</transactionCode>
      </transactionCoding>
    </nonDerivativeTransaction>
    <nonDerivativeTransaction>
      <transactionAmounts>
        <transactionShares><value>200</value></transactionShares>
        <transactionPricePerShare><value>48.00</value></transactionPricePerShare>
      </transactionAmounts>
      <transactionCoding>
        <transactionCode>A</transactionCode>
      </transactionCoding>
    </nonDerivativeTransaction>
  </nonDerivativeTable>
</ownershipDocument>"
          txns (#'impl/extract-form4-transactions (.getBytes xml-str "UTF-8") #{"P" "S"})]
      (is (= 2 (count txns)))
      (is (= "P" (:code (first txns))))
      (is (= 1000.0 (:shares (first txns))))
      (is (= "S" (:code (second txns))))
      (is (= 500.0 (:shares (second txns)))))))

(deftest extract-form4-transactions-invalid-xml-test
  (testing "invalid XML returns empty"
    (let [txns (#'impl/extract-form4-transactions (.getBytes "not xml" "UTF-8") #{"P" "S"})]
      (is (empty? txns)))))

(deftest monthly-windows-test
  (testing "generates monthly windows from start date"
    (let [windows (#'impl/monthly-windows "2026-01-01")]
      (is (pos? (count windows)))
      (is (= "2026-01-01" (first (first windows))))
      ;; Each window is [start end]
      (doseq [[s e] windows]
        (is (string? s))
        (is (string? e))))))

(deftest discover-test
  (testing "discover returns form type info"
    (let [config {:edgar/form-type   "4"
                  :edgar/user-agent  "Test/1.0"
                  :edgar/aggregation :monthly-buy-sell-ratio}
          handle (:connection/handle (impl/do-connect config nil))
          result (impl/do-discover handle)]
      (is (= 1 (:discover/total-count result)))
      (is (= "4" (:schema/name (first (:schemas result)))))
      (impl/do-close handle))))
