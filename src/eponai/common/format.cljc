(ns eponai.common.format
  #?(:clj (:refer-clojure :exclude [ref]))
  (:require [taoensso.timbre #?(:clj :refer :cljs :refer-macros) [debug error info warn]]
            [clojure.set :refer [rename-keys]]
            [clojure.data :as diff]
            [eponai.common.database.functions :as dbfn]
            [eponai.common.format.date :as date]
            #?(:clj [datomic.api :as datomic])
            [datascript.core :as datascript]
            [datascript.db])
  #?(:clj (:import [datomic Connection]
                   [clojure.lang Atom])))

(defn tempid [partition & [n]]
  #?(:clj (apply datomic/tempid (cond-> [partition] (some? n) (conj n)))
     :cljs (apply datascript/tempid (cond-> [partition] (some? n) (conj n)))))

#?(:clj
   (def tempid-type (type (tempid :db.part/user))))

(defn tempid? [x]
  #?(:clj (= tempid-type (type x))
     :cljs (and (number? x) (neg? x))))

(defn dbid? [x]
  #?(:clj  (or (tempid? x) (number? x))
     :cljs (number? x)))

(defn squuid []
  ;; Works for both datomic and datascript.
  (datascript/squuid))

(defn str->uuid [str-uuid]
  #?(:clj  (java.util.UUID/fromString str-uuid)
     :cljs (uuid str-uuid)))

(defn str->number [n]
  #?(:cljs (if-not (number? n)
             (cljs.reader/read-string n)
             n)
     :clj  (bigdec n)))

;; -------------------------- Database entities -----------------------------

(defn add-tempid
  "Add tempid to provided entity or collection of entities. If e is a map, assocs :db/id.
  If it's list, set or vector, maps over that collection and assoc's :db/id in each element."
  [e]
  (cond (map? e)
        (if (some? (:db/id e))
          e
          (assoc e :db/id (tempid :db.part/user)))

        (coll? e)
        (map (fn [v]
               (if (some? (:db/id v))
                 v
                 (assoc v :db/id (tempid :db.part/user))))
             e)
        :else
        e))

(defn project
  "Create project db entity belonging to user with :db/id user-eid.

  Provide opts including keys that should be specifically set. Will consider keys:
  * :project/name - name of this project, default value is 'Default'.
  * :project/created-at - timestamp if when project was created, default value is now.
  * :project/uuid - UUID to assign to this project entity, default will call (d/squuid).

  Returns a map representing a project entity"
  [user-dbid & [opts]]
  (cond-> {:db/id              (tempid :db.part/user)
           :project/uuid       (or (:project/uuid opts) (squuid))
           :project/created-at (or (:project/created-at opts) (date/date-time->long (date/now)))
           :project/name       (or (:project/name opts) "My Project")
           :project/categories #{(add-tempid {:category/name "Housing"})
                                 (add-tempid {:category/name "Transport"})}}
          (some? user-dbid)
          (->
            (assoc :project/created-by user-dbid)
            (assoc :project/users [user-dbid]))))


(defn category*
  [input]
  {:pre [(map? input)]}
  (add-tempid (select-keys input [:db/id :category/name])))

(defn tag*
  [input]
  {:pre [(map? input)]}
  (add-tempid (select-keys input [:db/id :tag/name])))

(defn date*
  [input]
  {:post [(map? %)
          (= (count (select-keys % [:date/ymd
                                    :date/timestamp
                                    :date/year
                                    :date/month
                                    :date/day])) 5)]}
  (let [date (date/date-map input)]
    (assert (and (:date/ymd date)
                 (:date/timestamp date)) (str "Created date needs :date/timestamp or :date/ymd, got: " date))
    (add-tempid date)))

(defn currency*
  [input]
  {:pre [(map? input)]}
  (add-tempid (select-keys input [:db/id :currency/code])))

(defn bigdec! [x]
  #?(:clj  (bigdec x)
     :cljs (cond-> x
                   (string? x)
                   (cljs.reader/read-string))))

(defn lookup-ref? [x]
  (and (sequential? x)
       (count (= 2 x))))

(defn fee* [fee]
  (letfn [(curr [c]
            (currency* (cond->> c (lookup-ref? c) (apply hash-map))))]
    (cond-> (add-tempid fee)
            (:transaction.fee/value fee)
            (update :transaction.fee/value bigdec!)
            (:transaction.fee/currency fee)
            (update :transaction.fee/currency curr))))

(defn transaction
  "Create a transaction entity for the given input. Will replace the name space of the keys to the :transaction/ namespace

  Provide opts for special behavior, will consider keys:
  * :no-rename - set this key to not rename the namespace of the keys.

  Calls special functions on following keys to format according to datomic:
  * :transaction/currency - takes a currency code string, returns a currency entity.
  * :transaction/date - takes a \"yyy-MM-dd\" string, returns a date entity.
  * :transaction/tags - takes a collections of strings, returns a collection of tag entities.
  * :transaction/amount - takes a number string, returns a number.
  * :transaction/project - takes a string UUID, returns a lookup ref.

  Returns a map representing a transaction entity"
  [input]
  (let [conv-fn-map {:transaction/date     (fn [d] {:pre [(map? d)]}
                                             (date* d))
                     :transaction/tags     (fn [ts]
                                             {:pre [(coll? ts)]}
                                             (into #{} (comp (filter some?) (map tag*)) ts))
                     :transaction/category (fn [c]
                                             {:pre [(map? c)]}
                                             c)
                     :transaction/fees     (fn [fees]
                                             {:pre [(every? map? fees)]}
                                             (into #{} (map fee*) fees))
                     :transaction/amount   (fn [a]
                                             {:pre [(or (string? a) (number? a))]}
                                             (bigdec! a))
                     :transaction/type     (fn [t] {:pre [(or (keyword? (:db/ident t t)))]}
                                             {:db/ident t})}
        update-fn (fn [m k]
                    (if (get m k)
                      (update m k (get conv-fn-map k))
                      m))
        transaction (reduce update-fn input (keys conv-fn-map))]
    (add-tempid transaction)))


(defn category [category-name]
  (category* {:category/name category-name}))

(defn transaction-edit [{:keys [transaction/tags transaction/uuid] :as input-transaction}]
  (let [tag->txs (fn [{:keys [tag/status tag/name] :as tag}]
                   {:pre [(some? name)]}
                   (condp = status
                     :deleted
                     [[:db/retract [:transaction/uuid uuid] :transaction/tags [:tag/name name]]]

                     nil
                     (let [tempid (tempid :db.part/user)]
                       ;; Create new tag and add it to the transaction
                       [(assoc (tag* tag) :db/id tempid)
                        [:db/add [:transaction/uuid uuid] :transaction/tags tempid]])))
        transaction (-> input-transaction
                        (dissoc :transaction/tags)
                        (assoc :db/id (tempid :db.part/user))
                        (->> (reduce-kv (fn [m k v]
                                          (assoc m k (condp = k
                                                       :transaction/amount (str->number v)
                                                       :transaction/currency (add-tempid v)
                                                       :transaction/type (add-tempid v)
                                                       :transaction/date (add-tempid v)
                                                       :transaction/project {:project/uuid (str->uuid (:project/uuid v))}
                                                       v)))
                                        {})))]
    (cond-> [transaction]
            (seq tags)
            (into (mapcat tag->txs) tags))))

(defn edit-txs [{:keys [old new]} conform-fn created-at]
  {:pre [(some? (:db/id old))
         (= (:db/id old) (:db/id new))
         (or (number? created-at)
             (= ::dbfn/client-edit created-at))]}
  (let [edits-by-attr (->> (diff/diff old new)
                           (take 2)
                           (mapv conform-fn)
                           (zipmap [:old-by-attr :new-by-attr])
                           (mapcat (fn [[id m]]
                                     (map #(hash-map :id id :kv %) m)))
                           (group-by (comp first :kv)))]
    (->> edits-by-attr
         (remove (fn [[attr]] (= :db/id attr)))
         (mapv (fn [[attr changes]]
                 (let [{:keys [old-by-attr new-by-attr]} (reduce (fn [m {:keys [id kv]}]
                                                                   (assert (= (first kv) attr))
                                                                   (assoc m id (second kv)))
                                                                 {}
                                                                 changes)]
                   [:db.fn/edit-attr created-at (:db/id old) attr {:old-value old-by-attr
                                                                   :new-value new-by-attr}]))))))

(defn client-edit [env k params conform-fn]
  (->> (edit-txs params conform-fn ::dbfn/client-edit)
       (mapcat (fn [[_ created-at eid attr old-new]]
                 (assert (number? eid) (str "entity id was not number for client edit: " [k eid attr old-new]))
                 (binding [dbfn/cardinality-many? dbfn/cardinality-many?-datascript
                           dbfn/ref? dbfn/ref?-datascript
                           dbfn/unique-datom dbfn/unique-datom-datascript
                           dbfn/tempid? dbfn/tempid?-datascript
                           dbfn/update-edit dbfn/update-edit-datascript]
                   (debug [:eid eid :attr attr :old-new old-new])
                   (dbfn/edit-attr (datascript/db (:state env)) created-at eid attr old-new))))
       (vec)))

(defn server-edit [env k params conform-fn]
  (let [created-at (some :eponai.common.parser/created-at [params env])]
    (assert (some? created-at)
            (str "No created-at found in either params or env for edit: " k " params: " params))
    (edit-txs params conform-fn created-at)))

(defn edit
  [env k p conform-fn]
  {:pre [(some? (:eponai.common.parser/server? env))]}
  (if (:eponai.common.parser/server? env)
    (server-edit env k p conform-fn)
    (client-edit env k p conform-fn)))
