(ns gsheetplus.core-test
  (:require [clojure.test :refer :all]
            [gsheetplus.core :refer :all]))

(deftest test-parse-spreadsheet-link
  (are [lnk expected]
    (= ((juxt :spreadsheet-id :sheet-id) (parse-spreadsheet-link lnk))
       expected)
    "https://docs.google.com/spreadsheets/d/1Y2ECOfHZJCbPE1yp0dsCAbkKAHaxOHcluh_JR-TAc5Y/edit?gid=1577761203#gid=1577761203"
    ["1Y2ECOfHZJCbPE1yp0dsCAbkKAHaxOHcluh_JR-TAc5Y" 1577761203]
    "https://docs.google.com/spreadsheets/d/1Y2ECOfHZJCbPE1yp0dsCAbkKAHaxOHcluh_JR-TAc5Y/edit#gid=1577761203"
    ["1Y2ECOfHZJCbPE1yp0dsCAbkKAHaxOHcluh_JR-TAc5Y" 1577761203]
    "https://docs.google.com/spreadsheets/d/SSID/edit"
    ["SSID" nil]
    nil
    [nil nil]
    "xxxxx"
    [nil nil]))

