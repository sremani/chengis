(ns chengis.db.pagination-test
  (:require [clojure.test :refer :all]
            [chengis.db.pagination :as pagination]))

(deftest encode-cursor-test
  (testing "encodes timestamp and id into Base64"
    (let [cursor (pagination/encode-cursor "2024-01-15 10:30:00" "abc-123")]
      (is (string? cursor))
      (is (not (clojure.string/blank? cursor)))))

  (testing "returns nil when timestamp or id is nil"
    (is (nil? (pagination/encode-cursor nil "abc")))
    (is (nil? (pagination/encode-cursor "2024-01-01" nil)))
    (is (nil? (pagination/encode-cursor nil nil)))))

(deftest decode-cursor-test
  (testing "roundtrip encode/decode"
    (let [cursor (pagination/encode-cursor "2024-01-15 10:30:00" "abc-123")
          decoded (pagination/decode-cursor cursor)]
      (is (= "2024-01-15 10:30:00" (:timestamp decoded)))
      (is (= "abc-123" (:id decoded)))))

  (testing "returns nil for invalid cursor"
    (is (nil? (pagination/decode-cursor nil)))
    (is (nil? (pagination/decode-cursor "")))
    (is (nil? (pagination/decode-cursor "   ")))
    (is (nil? (pagination/decode-cursor "not-valid-base64!!!"))))

  (testing "returns nil for malformed cursor (no separator)"
    (let [bad (.encodeToString (java.util.Base64/getUrlEncoder)
                               (.getBytes "no-separator" "UTF-8"))]
      (is (nil? (pagination/decode-cursor bad))))))

(deftest apply-cursor-where-test
  (testing "returns original where when no cursor"
    (is (= [:= :org-id "org1"]
           (pagination/apply-cursor-where [:= :org-id "org1"] nil :created-at :id :desc))))

  (testing "adds descending cursor condition"
    (let [cursor {:timestamp "2024-01-15 10:30:00" :id "abc-123"}
          result (pagination/apply-cursor-where nil cursor :created-at :id :desc)]
      (is (= [:or
              [:< :created-at "2024-01-15 10:30:00"]
              [:and
               [:= :created-at "2024-01-15 10:30:00"]
               [:< :id "abc-123"]]]
             result))))

  (testing "adds ascending cursor condition"
    (let [cursor {:timestamp "2024-01-15 10:30:00" :id "abc-123"}
          result (pagination/apply-cursor-where nil cursor :created-at :id :asc)]
      (is (= [:or
              [:> :created-at "2024-01-15 10:30:00"]
              [:and
               [:= :created-at "2024-01-15 10:30:00"]
               [:> :id "abc-123"]]]
             result))))

  (testing "combines with existing where clause"
    (let [cursor {:timestamp "2024-01-15 10:30:00" :id "abc-123"}
          result (pagination/apply-cursor-where [:= :org-id "org1"] cursor :created-at :id :desc)]
      (is (= :and (first result)))
      (is (= [:= :org-id "org1"] (second result))))))

(deftest paginated-response-test
  (testing "returns all items when fewer than limit"
    (let [items [{:id "1" :created-at "2024-01-01"} {:id "2" :created-at "2024-01-02"}]
          result (pagination/paginated-response items 10 :id :created-at)]
      (is (= 2 (count (:items result))))
      (is (false? (:has-more result)))
      (is (nil? (:next-cursor result)))))

  (testing "truncates to limit and signals has-more"
    (let [items (mapv (fn [i] {:id (str i) :created-at (str "2024-01-" (format "%02d" i))})
                      (range 1 12))]  ;; 11 items
      ;; Simulate: we fetched limit+1=11, so limit=10
      (let [result (pagination/paginated-response items 10 :id :created-at)]
        (is (= 10 (count (:items result))))
        (is (true? (:has-more result)))
        (is (some? (:next-cursor result)))
        ;; Verify cursor decodes to last item in page
        (let [decoded (pagination/decode-cursor (:next-cursor result))]
          (is (= "10" (:id decoded)))
          (is (= "2024-01-10" (:timestamp decoded)))))))

  (testing "empty result set"
    (let [result (pagination/paginated-response [] 10 :id :created-at)]
      (is (empty? (:items result)))
      (is (false? (:has-more result)))
      (is (nil? (:next-cursor result)))))

  (testing "exactly limit items means no more"
    (let [items (mapv (fn [i] {:id (str i) :created-at (str "2024-01-" (format "%02d" i))})
                      (range 1 11))]  ;; exactly 10
      (let [result (pagination/paginated-response items 10 :id :created-at)]
        (is (= 10 (count (:items result))))
        (is (false? (:has-more result)))
        (is (nil? (:next-cursor result)))))))

(deftest cursor-special-characters-test
  (testing "handles special characters in id"
    (let [cursor (pagination/encode-cursor "2024-01-15 10:30:00" "id-with|pipe")
          decoded (pagination/decode-cursor cursor)]
      ;; The id should capture everything after the first |
      (is (= "2024-01-15 10:30:00" (:timestamp decoded)))
      (is (= "id-with|pipe" (:id decoded)))))

  (testing "handles UUIDs in id"
    (let [id "550e8400-e29b-41d4-a716-446655440000"
          cursor (pagination/encode-cursor "2024-01-15 10:30:00" id)
          decoded (pagination/decode-cursor cursor)]
      (is (= id (:id decoded))))))
