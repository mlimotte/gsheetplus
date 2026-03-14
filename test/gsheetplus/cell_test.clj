(ns gsheetplus.cell-test
  (:require
   [clojure.test :refer [deftest is are testing]]
   [gsheetplus.cell :as cell])
  (:import
   (com.google.api.services.sheets.v4.model CellData ExtendedValue)))

;;; ── cell->clj ─────────────────────────────────────────────────────────────

(defn- ev [m] {"effectiveValue" m})
(defn- uev [m] {"userEnteredValue" m})
(defn- date-ev [n] {"effectiveValue"    {"numberValue" n}
                    "userEnteredFormat" {"numberFormat" {"type" "DATE"}}})
(defn- currency-ev [n] {"effectiveValue"    {"numberValue" n}
                        "userEnteredFormat" {"numberFormat" {"type" "CURRENCY"}}})

(deftest test-cell->clj-string
  (is (= "hello" (cell/cell->clj (ev {"stringValue" "hello"})))))

(deftest test-cell->clj-number
  (is (= 3.14 (cell/cell->clj (ev {"numberValue" 3.14})))))

(deftest test-cell->clj-boolean
  (are [input expected] (= expected (cell/cell->clj (ev {"boolValue" input})))
    true  true
    false false))

(deftest test-cell->clj-date
  (testing "date serial number → LocalDate"
    (let [result (cell/cell->clj (date-ev 2.0))]
      ;; Serial 2 = 1900-01-01 (Sheets epoch is 1899-12-30, +2 days)
      (is (= java.time.LocalDate (class result)))
      (is (= (java.time.LocalDate/of 1900 1 1) result)))))

(deftest test-cell->clj-currency
  (let [result (cell/cell->clj (currency-ev 9.99))]
    (is (instance? BigDecimal result))
    (is (= (bigdec 9.99) result))))

(deftest test-cell->clj-nil-map
  ;; Empty plain map has no effectiveValue/userEnteredValue → falls through to :else → returns itself
  (is (= {} (cell/cell->clj {}))))

(deftest test-cell->clj-empty-CellData
  (is (nil? (cell/cell->clj (CellData.)))))

(deftest test-cell->clj-ambiguous-ev-and-uev
  (is (thrown? clojure.lang.ExceptionInfo
               (cell/cell->clj {"effectiveValue"    {"stringValue" "a"}
                                "userEnteredValue"  {"stringValue" "b"}}))))

;;; ── date-serial-number ────────────────────────────────────────────────────

(deftest test-date-serial-number-known
  (testing "1900-01-01 = serial 2"
    (is (= 2.0 (cell/date-serial-number (java.time.LocalDate/of 1900 1 1)))))
  (testing "1899-12-30 = serial 0 (epoch)"
    (is (= 0.0 (cell/date-serial-number (java.time.LocalDate/of 1899 12 30))))))
