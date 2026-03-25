(ns skipp.alloy.grammar-test
  (:require
   [clojure.test :refer :all]
   [clojure.zip :as z]
   [skipp.alloy.grammar :refer :all]
   [skipp.alloy.examples :refer [context-map1]])
  (:import
   clojure.lang.ExceptionInfo))

(deftest test-ast-node
  (is (ast-node? (mkastnode :REF [0] :k 1)))
  (is (= (:k (mkastnode :REF [0] :k 1))) 1)
  (is (not (ast-node? {:op :REF})))
  (is (not (ast-node? nil))))

(deftest test-vec-index
  (let [zipper (z/vector-zip [1 [2 3 [4] 5] [6 7]])]
    (are [n expected]
         (= (vec-index n) expected)
      (-> zipper z/down) [0]
      (-> zipper z/down z/right z/down) [1 0]
      (-> zipper z/down z/right z/right z/down z/right) [2 1])))

(deftest test-grammar
  (is (= (the-parser "f o o") ["f o o"]))
  (is (= (the-parser "{x}") ["{" "x}"]))
  (is (= (the-parser " {x}") [" " "{" "x}"]))
  (is (= (the-parser "{x} ") ["{" "x} "]))
  (is (= (the-parser "%{x%} ") ["%" "{" "x%} "]))
  (is (= (the-parser "{{x}}") [[:EXPR [:REF [:IDENTIFIER "x"]]]]))
  (is (= (the-parser "{{x.$}}") [[:EXPR [:REF [:IDENTIFIER "x"] [:IDENTIFIER "$"]]]]))
  (is (= (the-parser "{{$.foo}}") [[:EXPR [:REF [:IDENTIFIER "$"] [:IDENTIFIER "foo"]]]]))
  (is (= (the-parser "{%x%}") [[:CTAG [:IDENTIFIER "x"]]])))

(deftest test-parse-struct-literals
  (is (= (parse-struct the-parser (z/vector-zip [["foo"]]))
         [[[(mkastnode :VALUE [0 0] :value "foo")]]]))
  (is (= (parse-struct the-parser (z/vector-zip [[1.0]]))
         [[[(mkastnode :VALUE [0 0] :value 1.0)]]])))

(deftest test-parse-struct
  (let [zipper (z/vector-zip [["{{x}} {{cabinetry.color-&-finish.cabinet_doors/primary}}"
                               "{{ (x >= 1) }}"]])
        result (parse-struct the-parser zipper)]
    (is (= result
           [[[(mkastnode :REF [0 0] :args [:x] :filters [])
              (mkastnode :VALUE [0 0] :value " ")
              (mkastnode :REF [0 0] :args [:cabinetry :color-&-finish :cabinet_doors/primary] :filters [])]
             [(mkastnode :EXPR [0 1]
                         :expr ['>=
                                (mkastnode :REF [0 1] :args [:x] :filters [])
                                1]
                         :filters [])]]])))
  ;; ROWS_BLOCK
  (let [zipper (z/vector-zip [["{% for y in l %}"]
                              ["{% if  tst %}" "{% if tst2 %}"]
                              ["{% endfor %}"]])
        [row1 row2 row3] (parse-struct the-parser zipper)]
    (is (= (map (partial map :op) row1)
           [[:FOR]]))
    (is (= (map (partial map :op) row2)
           [[:IF] [:IF]]))
    (is (= (map (partial map :op) row3)
           [[:ENDFOR]])))
  ;; WITH
  (let [zipper (z/vector-zip [["{% with y = 1 %}"]
                              ["{{y}}"]
                              ["{% endwith %}"]])
        [row1 row2 row3] (parse-struct the-parser zipper)]
    (is (= (map (partial map :op) row1)
           [[:WITH]]))
    (is (= (map (partial map :op) row2)
           [[:REF]]))
    (is (= (map (partial map :op) row3)
           [[:ENDWITH]])))
  ; An error EXPR, no symbol in expr
  (is (thrown-with-msg?
       ExceptionInfo #"Could not parse"
       (parse-struct the-parser (z/vector-zip [["{{ (1 2) }}"]]))))
  ; CTAG test
  (is (= (parse-struct the-parser (z/vector-zip [["{% custom-tag arg1 [opt1] %}"
                                                  "{{x}}"
                                                  "{% end-custom-tag %}"]]))
         [[[(mkastnode :CTAG [0 0] :ctag :custom-tag
                       :end? false
                       :options [:opt1]
                       :args [(mkastnode :REF [0 0] :args [:arg1] :filters [])])]
           [(mkastnode :REF [0 1] :args [:x] :filters [])]
           [(mkastnode :CTAG [0 2] :ctag :custom-tag :args nil :options nil :end? true)]]]))
  ; Filter test
  (is (= (parse-struct the-parser (z/vector-zip [["{{a..b..c/s|upper-case}}"]]))
         [[[(mkastnode :REF [0 0] :args [:a.b.c/s]
                       :filters [{:args nil, :op :upper-case}])]]])))
