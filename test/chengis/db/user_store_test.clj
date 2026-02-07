(ns chengis.db.user-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-user-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

(deftest password-hashing-test
  (testing "hash and verify password"
    (let [hash (user-store/hash-password "secret123")]
      (is (string? hash))
      (is (not= "secret123" hash))
      (is (true? (user-store/check-password "secret123" hash)))
      (is (false? (user-store/check-password "wrong" hash))))))

(deftest user-crud-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create and retrieve user"
      (let [user (user-store/create-user! ds {:username "alice"
                                              :password "pass123"
                                              :role "developer"})]
        (is (some? (:id user)))
        (is (= "alice" (:username user)))
        (is (= "developer" (:role user)))
        ;; No password hash in return value
        (is (nil? (:password-hash user)))))

    (testing "get user by username"
      (let [user (user-store/get-user-by-username ds "alice")]
        (is (some? user))
        (is (= "alice" (:username user)))
        (is (= "developer" (:role user)))
        ;; Full record includes password hash
        (is (some? (:password-hash user)))))

    (testing "get user by id"
      (let [by-name (user-store/get-user-by-username ds "alice")
            by-id (user-store/get-user ds (:id by-name))]
        (is (= (:username by-name) (:username by-id)))))

    (testing "list users"
      (user-store/create-user! ds {:username "bob"
                                   :password "pass456"
                                   :role "viewer"})
      (let [users (user-store/list-users ds)]
        (is (= 2 (count users)))
        ;; No password hashes in list
        (is (every? #(nil? (:password-hash %)) users))))

    (testing "update user role"
      (let [user (user-store/get-user-by-username ds "bob")]
        (user-store/update-user! ds (:id user) {:role "developer"})
        (let [updated (user-store/get-user-by-username ds "bob")]
          (is (= "developer" (:role updated))))))

    (testing "update password"
      (let [user (user-store/get-user-by-username ds "alice")]
        (user-store/update-password! ds (:id user) "newpass")
        (let [updated (user-store/get-user-by-username ds "alice")]
          (is (true? (user-store/check-password "newpass" (:password-hash updated))))
          (is (false? (user-store/check-password "pass123" (:password-hash updated)))))))

    (testing "soft delete user"
      (let [user (user-store/get-user-by-username ds "bob")]
        (user-store/delete-user! ds (:id user))
        (let [deleted (user-store/get-user-by-username ds "bob")]
          (is (= 0 (:active deleted))))))

    (testing "count users"
      (is (= 2 (user-store/count-users ds))))))

(deftest seed-admin-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "seed admin creates user when table is empty"
      (let [admin (user-store/seed-admin! ds "secretpw")]
        (is (some? admin))
        (is (= "admin" (:username admin)))
        (is (= "admin" (:role admin)))))

    (testing "seed admin is idempotent — no-op when users exist"
      (let [result (user-store/seed-admin! ds "anotherpw")]
        (is (nil? result))
        ;; Still just one user
        (is (= 1 (user-store/count-users ds)))))

    (testing "admin password is correct"
      (let [admin (user-store/get-user-by-username ds "admin")]
        (is (true? (user-store/check-password "secretpw" (:password-hash admin))))))))

(deftest api-token-test
  (let [ds (conn/create-datasource test-db-path)]
    ;; Create a user first
    (let [user (user-store/create-user! ds {:username "tokenuser"
                                            :password "pass"
                                            :role "developer"})]
      (testing "create API token"
        (let [token-result (user-store/create-api-token! ds {:user-id (:id user)
                                                             :name "CI Token"})]
          (is (some? (:token token-result)))
          (is (= "CI Token" (:name token-result)))
          ;; Token is long enough
          (is (>= (count (:token token-result)) 70))))

      (testing "list tokens for user"
        (let [tokens (user-store/list-api-tokens ds (:id user))]
          (is (= 1 (count tokens)))
          (is (= "CI Token" (:name (first tokens))))
          ;; No hash in list
          (is (nil? (:token-hash (first tokens))))))

      (testing "find user by API token"
        (let [token-result (user-store/create-api-token! ds {:user-id (:id user)
                                                             :name "Lookup Token"})
              found-user (user-store/find-api-token-user ds (:token token-result))]
          (is (some? found-user))
          (is (= "tokenuser" (:username found-user)))))

      (testing "invalid token returns nil"
        (is (nil? (user-store/find-api-token-user ds "bogus-token-value"))))

      (testing "delete token"
        (let [tokens (user-store/list-api-tokens ds (:id user))
              token-id (:id (first tokens))]
          (user-store/delete-api-token! ds token-id)
          (let [remaining (user-store/list-api-tokens ds (:id user))]
            (is (= 1 (count remaining)))))))))
