(ns gsheetplus.table-test
  (:require
   [clojure.test :refer [deftest is are testing]]
   [gsheetplus.table :as table]))

;;; ── ->kebab-keyword ───────────────────────────────────────────────────────

(deftest test-kebab-keyword-plain
  (is (= :first-name (table/->kebab-keyword "First Name"))))

(deftest test-kebab-keyword-parenthetical
  (are [input expected] (= expected (table/->kebab-keyword input))
    "Unit Price (USD)"     :unit-price
    "Name (optional)"      :name
    "Column A (required)"  :column-a))

(deftest test-kebab-keyword-special-chars
  (are [input expected] (= expected (table/->kebab-keyword input))
    "A/B Test"     :a-b-test
    "foo--bar"     :foo-bar
    "  spaces  "   :spaces))

;;; ── headers-from-row ──────────────────────────────────────────────────────

(deftest test-headers-from-row-simple
  (is (= [:name :age :email]
         (table/headers-from-row ["Name" "Age" "Email"]))))

(deftest test-headers-from-row-stops-at-blank
  (is (= [:name :age]
         (table/headers-from-row ["Name" "Age" nil "Email"]))))

(deftest test-headers-from-row-custom-fn
  (is (= ["NAME" "AGE"]
         (table/headers-from-row ["name" "age"] {:column-name-fn clojure.string/upper-case}))))

(deftest test-headers-from-row-keep-leading-blanks
  (let [headers (table/headers-from-row [nil nil "Name" "Age"] {:keep-leading-blanks true})]
    (is (= [nil nil :name :age] (vec headers)))))

;;; ── row->record ───────────────────────────────────────────────────────────

(deftest test-row->record-basic
  (is (= {:a "x" :b "y" :c "z"}
         (table/row->record [:a :b :c] ["x" "y" "z"]))))

(deftest test-row->record-removes-blank-by-default
  (let [result (table/row->record [:a :b :c] ["x" "" nil])]
    (is (= {:a "x"} result))))

(deftest test-row->record-keep-blanks
  (let [result (table/row->record [:a :b] ["x" ""] {:remove-blank-keys false})]
    (is (contains? result :b))
    (is (= "" (get result :b)))))

;;; ── info-params ───────────────────────────────────────────────────────────

(deftest test-info-params-basic
  (let [raw   [["Title" "My Sheet"] ["Version" "2"] [] ["Name" "Age"]]
        [info remaining _] (table/info-params raw)]
    (is (= {:title "My Sheet" :version "2"} info))
    (is (= [["Name" "Age"]] remaining))))

(deftest test-info-params-skips-blank-values
  (let [raw   [["Key" ""] ["Other" "val"] []]
        [info _ _] (table/info-params raw)]
    (is (not (contains? info :key)))
    (is (= "val" (:other info)))))

;;; ── fill-down ─────────────────────────────────────────────────────────────

(deftest test-fill-down-propagates
  (let [records [{:a "x" :b "1"} {:b "2"} {:b "3"}]
        result  (table/fill-down [:a] false records)]
    (is (= [{:a "x" :b "1"} {:a "x" :b "2"} {:a "x" :b "3"}] result))))

(deftest test-fill-down-no-overwrite
  (let [records [{:a "x"} {:a "y"}]
        result  (table/fill-down [:a] false records)]
    (is (= [{:a "x"} {:a "y"}] result))))

(deftest test-fill-down-required-throws
  (let [records [{:b "1"} {:b "2"}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (table/fill-down [:a] true records)))))

;;; ── prune-start-rows / drop-after-stop-row ────────────────────────────────

(deftest test-drop-after-stop-row
  (let [raw [[1] [2] ["/STOP"] [3]]]
    (is (= [[1] [2]] (vec (table/drop-after-stop-row raw))))))

(deftest test-prune-start-rows
  (let [raw [["header"] ["/START"] ["data1"] ["data2"]]]
    (is (= [["data1"] ["data2"]] (vec (table/prune-start-rows raw))))))
