(ns skipp.alloy.examples)

(def context-map1
  {:x            1
   "this-&-that" 1
   :a.b.c/s      "fooBar"
   :some-ns/-x   2
   :a            {:b 30}
   :l            [{:a 2 :b 'b1} {:a 4 :b 'b2} {:a 6 :b nil}]
   :l2           '[a b c]
   :l3           [10 20]
   :f-val        ['a nil 'c]
   :empty        []
   :IS_HDR       true
   ; 1. "$" is a valid identifier
   ; 2. Strings like "a" can be keys also. If the keyword is not found, it will try string next
   :DATA         {"$" "one", "$$" "two", "a" "A"}})

(def sample-context-map
  {:x      1
   :s      "fooBar"
   :a      {:b 30}
   :l      [{:a 2 :b 'b1} {:a 4 :b 'b2} {:a 6 :b nil}]
   :l2     [10 20]
   :l3     ['a 'b 'c 'd]
   :IS_HDR true})

(def sample-tmpl
  [["foo" 10]
   []
   ["baz" "The value of x is {{ x }}, s = {{s|uppercase|lowercase}}" "{{a.b}}" "{{x|add:10}}"]
   ["{% if IS_HDR %}{{a.b}}{%endif%}" "other text"]
   ["{% for row in l %}"]
   ["{% with blurb=\"lorem ipsum\" %}" "{{blurb}}" "{%endwith%}"]
   ["{% with blurb=-33.1 %}" "{{blurb}}"] ; another `with` using the same variable
   ["{%endwith%}"]
   ["{{row.a}}" "{% runtime %}"]
   ["{% endfor %}"]])

(def simple-tmpl
  [;["foo" 10 "{{x}}"]
   ;["baz" "X={{ x }}, s={{s|uppercase|lowercase}}" "{{a.b}}" "{{x|add:10}}"]
   ;
   ;;;["{% XTAG %}"]
   ;["{% with blurb=-33.1 %}" "{{blurb}}" "{%endwith%}"]
   ;["{% with blurb=5 %}" "{{blurb}}"] ; another `with` using the same variable
   ;["{%endwith%}"]

   ["{% for row in l %}"]
   ["{% for numb in l2 %}"]
   ["numb" "{{numb}}"]
   ["{% endfor %}"]
   ["{{row.a}}"]
   ["X" "{{row.b}}"]
   ["loop-line-2"]

   ["{% endfor %}"]

   ["THE END"]])
