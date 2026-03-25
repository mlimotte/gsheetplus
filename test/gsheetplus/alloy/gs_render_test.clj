(ns gsheetplus.alloy.gs-render-test
  (:require
   [clojure.test :refer :all]
   [clojure.zip :as z]
   [gsheetplus.alloy.gs-render :refer :all]
   [gsheetplus.alloy.render :as render]
   [gsheetplus.alloy.examples :refer [context-map1]]
   [gsheetplus.alloy.grammar :as g]))

(deftest test-generate-instructions
  (let [zipper (z/vector-zip [["{% for inner in l3 %}"] ; [10, 20]
                              ["{{inner}}"]
                              ["{%endfor%}"]])
        input  (render/unroll
                (render/render-ast context-map1 nil (g/parse-struct g/the-parser zipper)))]
    (is (= (generate-instructions input)
           [{:end-rowidx   3
             :instruct     :delete-rows
             :start-rowidx 2}
            {:end-src-row-exclusive 2
             :instruct              :dupe-rows
             :num-blocks            2
             :rowidx                1}
            {:colidx   0
             :instruct :update
             :rowidx   2
             :value    20}
            {:colidx   0
             :instruct :update
             :rowidx   1
             :value    10}
            {:end-rowidx   1
             :instruct     :delete-rows
             :start-rowidx 0}])))
  (let [zipper (z/vector-zip [["DONE {{x}}"]]) ; 1
        input  (render/unroll
                (render/render-ast context-map1 nil (g/parse-struct g/the-parser zipper)))]
    (is (= (generate-instructions input)
           [{:colidx   0
             :instruct :update
             :rowidx   0
             :value    "DONE 1"}])))
  (let [zipper (z/vector-zip [["hello"] ; [a b c]
                              ["foo" "{% if 1 = 1 %}{{x}}{%endif%}"] ; [10, 20]
                              ["bar"]])
        input  (render/unroll
                (render/render-ast context-map1 nil (g/parse-struct g/the-parser zipper)))]
    (is (= (generate-instructions input)
           [{:colidx   1
             :instruct :update
             :rowidx   1
             :value    1}])))
  (let [zipper (z/vector-zip [["{% for outer in l2 %}"] ; [a b c]
                              ["{% for inner in l3 %}"] ; [10, 20]
                              ["{{inner}} {{outer}}" "literal-string"]
                              ["{%endfor%}"]
                              ["{%endfor%}"]])
        input  (render/unroll
                (render/render-ast context-map1 nil (g/parse-struct g/the-parser zipper)))]
    (is (= (generate-instructions input)
           [{:end-rowidx   5
             :instruct     :delete-rows
             :start-rowidx 4}
            {:end-src-row-exclusive 4
             :instruct              :dupe-rows
             :num-blocks            3
             :rowidx                1}
            {:end-rowidx   10
             :instruct     :delete-rows
             :start-rowidx 9}
            {:end-src-row-exclusive 9
             :instruct              :dupe-rows
             :num-blocks            2
             :rowidx                8}
            {:colidx   0
             :instruct :update
             :rowidx   9
             :value    "20 c"}
            {:colidx   0
             :instruct :update
             :rowidx   8
             :value    "10 c"}
            {:end-rowidx   8
             :instruct     :delete-rows
             :start-rowidx 7}
            {:end-rowidx   7
             :instruct     :delete-rows
             :start-rowidx 6}
            {:end-src-row-exclusive 6
             :instruct              :dupe-rows
             :num-blocks            2
             :rowidx                5}
            {:colidx   0
             :instruct :update
             :rowidx   6
             :value    "20 b"}
            {:colidx   0
             :instruct :update
             :rowidx   5
             :value    "10 b"}
            {:end-rowidx   5
             :instruct     :delete-rows
             :start-rowidx 4}
            {:end-rowidx   4
             :instruct     :delete-rows
             :start-rowidx 3}
            {:end-src-row-exclusive 3
             :instruct              :dupe-rows
             :num-blocks            2
             :rowidx                2}
            {:colidx   0
             :instruct :update
             :rowidx   3
             :value    "20 a"}
            {:colidx   0
             :instruct :update
             :rowidx   2
             :value    "10 a"}
            {:end-rowidx   2
             :instruct     :delete-rows
             :start-rowidx 1}
            {:end-rowidx   1
             :instruct     :delete-rows
             :start-rowidx 0}]))))
