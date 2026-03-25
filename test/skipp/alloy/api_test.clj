(ns skipp.alloy.api-test
  (:require
   [clojure.test :refer :all]
   [gsheetplus.core :as g+core]
   [gsheetplus.extras :as g+extras]
   [skipp.alloy.api :as alloy]
   [skipp.alloy.examples :refer [context-map1]]
   [skipp.util.lang :as lang]
   [taoensso.timbre :as timbre]))

; Instructions: You must manually delete the sheet indicated by new-sheet-name before running this.
(deftest ^:integration test-render-a-google-sheet
  (let [spreadsheet-id    "1LFTwmxP39esqwplAzFcDnc6-crHwNs1Q9IOPf-JlRvc"
        sheet-name        "skipp.alloy.api-test"
        new-sheet-name    (str sheet-name 1)
        gsheet-service    (g+extras/login-with-aws-secret {} "production/google/bridge")
        src-sheet-id      (g+core/find-sheet-id gsheet-service spreadsheet-id sheet-name)
        existing-sheet-id (g+core/find-sheet-id gsheet-service spreadsheet-id new-sheet-name)]
    (timbre/info "Test Sheets" {:src-sheet-id      src-sheet-id
                                :existing-sheet-id existing-sheet-id})
    (when existing-sheet-id
      (g+core/delete-sheet gsheet-service spreadsheet-id existing-sheet-id))
    (g+core/copy-to gsheet-service spreadsheet-id src-sheet-id spreadsheet-id new-sheet-name)
    ; The only test is that we don't throw an (uncaught) exception
    (alloy/render-google-sheet
     gsheet-service spreadsheet-id new-sheet-name context-map1
     {:filters      {:take    (fn [node-val n] (take n node-val))
                     :boolean boolean
                     :default (fn [v arg1] (if (lang/nil-or-blank? v) arg1 v))}
      :inline-ctags {:datetime (fn [context-map] (str (java.util.Date.)))}})))

(deftest kw-or-str-test
  (is (= (alloy/kw-or-str "foo") "foo"))
  (is (= (alloy/kw-or-str ":foo") :foo))
  (is (= (alloy/kw-or-str nil) nil)))
