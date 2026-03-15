(ns gsheetplus.table-test
  (:require
    [clojure.string :as string]
    [clojure.test :refer [deftest is are testing]]
    [gsheetplus.table :as table]))

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
  (let [raw [["Title" "My Sheet"] ["Version" "2"] [] ["Name" "Age"]]
        [info remaining _] (table/info-params raw)]
    (is (= {:title "My Sheet" :version "2"} info))
    (is (= [["Name" "Age"]] remaining))))

(deftest test-info-params-skips-blank-values
  (let [raw [["Key" ""] ["Other" "val"] []]
        [info _ _] (table/info-params raw)]
    (is (not (contains? info :key)))
    (is (= "val" (:other info)))))

;;; ── blocks, sections ─────────────────────────────────────────-────────────

(deftest test-blocks
  (is (= (table/blocks [["foo" "bar" "baz"]
                        [nil "other"]
                        ["A" "a" nil]
                        ["a" "a"]
                        ["a2" "a2"]
                        ["B"]
                        ["b" "b"]
                        [nil]]
                       ["A" "B" "C"])
         [[["A" "a" nil]
           ["a" "a"]
           ["a2" "a2"]]
          [["B"]
           ["b" "b"]
           [nil]]])))

(def sample-raw-data [["foo" "bar" "baz"]
                      ["apple" nil]
                      ["x" 1 "X"]
                      ["honda" "ford" nil nil]
                      [nil " " nil]
                      ["a" "b" "c (junk)" "d  " "e (foo) e"] ; header row, index = 5

                      ["A" nil nil]                         ; start of section data, index 7 (drop 6)
                      ["" "A.a1" nil "A.a1.d"]
                      ["" "" "A.a1.c.1" nil]
                      ["" "" "A.a1.c.2" "A.a1.d.2"]
                      [nil nil nil nil]

                      ["B" nil "B.c"]
                      ["" "B.b1" nil "B.b1.d"]
                      ["" "" "B.b1.c.1" nil]
                      ["" "" nil "B.b1.d.2"]
                      ["" "" "B.b1.c.3" "B.b1.d.3"]
                      ["" "B.b2" nil "B.b2.d"]
                      ["" "" "B.b2.c.1" "B.b2.d.1"]])

(deftest test-split-sections
  (let [headers (table/headers-from-row (nth sample-raw-data 5))
        x (table/split-sections (drop 6 sample-raw-data)
                                headers
                                {:section-fn       (fn [record _] (some-> record :a string/trim))
                                 :subsection-fn    (fn [record _] (and (not (:a record))
                                                                       (some-> record :b string/trim)))
                                 :record-filter-fn :d})]
    (is (= x
           [{:a            "A"
             :section-name "A"
             :records      [{:b       "A.a1" :d "A.a1.d" :section-name "A" :subsection-name "A.a1"
                             :records [{:c "A.a1.c.2" :d "A.a1.d.2" :section-name "A" :subsection-name "A.a1"}]}]}
            {:a            "B"
             :c            "B.c"
             :section-name "B"
             :records      [{:section-name "B" :subsection-name "B.b1" :b "B.b1" :d "B.b1.d"
                             :records      [{:section-name "B" :subsection-name "B.b1" :d "B.b1.d.2"}
                                            {:section-name "B" :subsection-name "B.b1" :c "B.b1.c.3" :d "B.b1.d.3"}]}
                            {:section-name "B" :subsection-name "B.b2" :b "B.b2" :d "B.b2.d"
                             :records      [{:section-name "B" :subsection-name "B.b2" :c "B.b2.c.1" :d "B.b2.d.1"}]}]}]))))

;;; ── fill-down ─────────────────────────────────────────────────────────────

(deftest test-fill-down-propagates
  (let [records [{:a "x" :b "1"} {:b "2"} {:b "3"}]
        result (table/fill-down [:a] false records)]
    (is (= [{:a "x" :b "1"} {:a "x" :b "2"} {:a "x" :b "3"}] result))))

(deftest test-fill-down-no-overwrite
  (let [records [{:a "x"} {:a "y"}]
        result (table/fill-down [:a] false records)]
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
