 (ns flipmunks.budget.core-test
   (:require [clojure.test :refer :all]
             [datomic.api :only [q db] :as d]
             [flipmunks.budget.core :as b]
             [flipmunks.budget.datomic.core :as dc]
             [flipmunks.budget.datomic.format :as f]))

(def schema (read-string (slurp "resources/private/datomic-schema.edn")))

(def test-data [{:name       "coffee"
                :uuid       (str (d/squuid))
                :created-at 12345
                :date       "2015-10-10"
                :amount     100
                :currency   "SEK"
                :tags       ["fika" "thailand"]}])

 (def test-curs {:SEK "Swedish Krona"})

(defn new-db
  "Creates an empty database and returns the connection."
  ([]
   (new-db nil))
  ([txs]
   (let [uri "datomic:mem://test-db"]
     (d/create-database uri)
     (let [conn (d/connect uri)]
       (d/transact conn schema)
       (when txs
         (d/transact conn txs))
       conn))))

(defn speculate [conn txs]
  (:db-after
    (d/with (d/db conn) txs)))

 (defn key-set [m]
   (set (keys m)))

 (defn test-input-db-data
   "Test that the input data mathces the db entities db-data. Checking that count is the same,
   and that all keys in the maps match."
   [input db-data]
   (is (= (count input) (count db-data)))
   (is (every? true? (map #(= (key-set %1) (key-set %2)) input db-data))))

(deftest test-post-currencies
  (testing "Posting currencies, and verifying pull."
    (let [db (b/post-currencies speculate (new-db) test-curs)
          db-result (dc/pull-currencies db)]
      (test-input-db-data (f/curs->db-txs test-curs) db-result))))

(deftest test-post-transactions
  (testing "Posting user transactions, verify pull."
    (let [cur-db (new-db (f/curs->db-txs test-curs))
          db (b/post-user-txs speculate cur-db test-data)
          db-result (dc/pull-user-txs db {})]
      (test-input-db-data (f/user-txs->db-txs test-data) db-result))))