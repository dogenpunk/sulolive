(ns eponai.web.ui.all-transactions
  (:require [clojure.set :as set]
            [eponai.web.ui.add-transaction :refer [->AddTransaction AddTransaction]]
            [eponai.web.ui.format :as f]
            [eponai.web.ui.datepicker :refer [->Datepicker]]
            [eponai.web.ui.utils.filter :as filter]
            [eponai.client.lib.transactions :as lib.t]
            [eponai.client.ui :refer [map-all update-query-params!] :refer-macros [style opts]]
            [eponai.web.ui.utils :as utils]
            [eponai.common.format :as format]
            [garden.core :refer [css]]
            [goog.string :as gstring]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [sablono.core :refer-macros [html]]
            [taoensso.timbre :refer-macros [debug]]
            [datascript.core :as d]
            [cljs-time.core :as time]
            [cljs-time.coerce :as coerce]
            [eponai.web.routes :as routes]))

;; ################### Om next components ###################

(defui Transaction
  static om/IQuery
  (query [_]
    [:db/id
     :transaction/uuid
     :transaction/title
     :transaction/amount
     :transaction/created-at
     {:transaction/currency [:currency/code
                             :currency/symbol-native]}
     {:transaction/tags [:tag/name]}
     {:transaction/date [:db/id
                         :date/timestamp
                         :date/ymd
                         :date/day
                         :date/month
                         :date/year]}
     {:transaction/project [:db/id :project/uuid :project/name]}
     {:transaction/type [:db/ident]}
     :transaction/conversion])
  Object
  (add-tag [this tag]
    (om/update-state! this (fn [st]
                             (-> st
                                 (assoc :input-tag ""
                                        :add-tag? false)
                                 (update-in [:input-transaction :transaction/tags] #(utils/add-tag % tag))))))
  (delete-tag [this tag]
    (om/update-state! this update-in [:input-transaction :transaction/tags] #(utils/delete-tag % tag)))

  (save-edit [this]
    (let [{:keys [input-transaction
                  init-state]} (om/get-state this)]
      (om/transact! this `[(transaction/edit ~(-> input-transaction
                                                  (lib.t/diff-transaction init-state)
                                                  (assoc :transaction/uuid (:transaction/uuid input-transaction))
                                                  (assoc :db/id (:db/id input-transaction))
                                                  (assoc :mutation-uuid (d/squuid))))
                           (transactions/deselect)
                           ;; TODO: Are all these needed?
                           ;; Copied from AddTransaction.
                           :query/selected-transaction
                           :query/dashboard
                           :query/all-projects
                           :query/transactions])))

  (init-selected-state [this props]
    (let [transaction (update props :transaction/tags (fn [tags] (sort-by :tag/name (map #(select-keys % [:tag/name]) tags))))]
      (om/update-state! this assoc
                        :input-transaction transaction
                        :init-state transaction)))

  (select-transaction [this]
    (let [{:keys [db/id] :as props} (om/props this)]
      (.init-selected-state this props)
      (om/transact! this `[(transactions/select-transaction ~{:transaction-dbid id})
                            :query/selected-transaction])))

  (componentWillReceiveProps [this new-props]
    (when (:is-selected (om/get-computed this))
      (.init-selected-state this new-props)))

  ;; Render a normal transaction (not in edit mode)
  (render-normal [this]
    (let [{:keys [transaction/date
                  transaction/currency
                  transaction/amount
                  transaction/uuid
                  transaction/type
                  transaction/conversion
                  transaction/title]
           :as   transaction} (om/props this)
          {:keys [user on-tag-click]} (om/get-computed this)]
      (dom/li
        #js {:draggable   true
             :onClick    #(.select-transaction this)
             :onDragStart #(utils/on-drag-transaction-start this (str uuid) %)}
        (dom/div #js {:className "row collapse expanded"}
          (dom/div #js {:className "columns small-6 medium-3 large-2"}
            (dom/span nil (str (f/month-name (:date/month date)) " " (:date/day date))))
          (dom/div #js {:className "columns small-6 medium-3 large-2"}
            (if-let [rate (:conversion/rate conversion)]
              (dom/div
                nil
                (dom/small #js {:className "currency-code"}
                           (str (or (:currency/code (:user/currency user))
                                    (:currency/symbol-native (:user/currency user))) " "))
                (if (= (:db/ident type) :transaction.type/expense)
                  (dom/strong #js {:className "label alert"
                                   :style #js {:padding "0.2em 0.3em"}}
                              (gstring/format (str "-%.2f") (/ amount rate)))
                  (dom/strong #js {:className "label success"
                                   :style #js {:padding "0.2em 0.3em"}}
                              (gstring/format (str "%.2f") (/ amount rate)))))
              (dom/i #js {:className "fa fa-spinner fa-spin"})))
          (dom/div #js {:className "columns small-6 medium-3 large-1"}
            (dom/small #js {:className "currency-code"}
                       (str (or (:currency/symbol-native currency)
                                (:currency/code currency)) " "))
            (dom/small nil (dom/strong nil (gstring/format (str "%.2f") amount))))

          (dom/div #js {:className "columns small-12 medium-3 large-2 l"}
            (dom/pre nil (or title " ")))

          (apply dom/div #js {:className "columns small-10 large-4"}
                 (map-all (:transaction/tags transaction)
                          (fn [tag]
                            (utils/tag tag {:on-click #(do
                                                        (.stopPropagation %)
                                                        (on-tag-click tag))}))))
          (dom/div #js {:className "columns small-2 large-1 text-right"}
            (dom/a #js {:className "edit-transaction secondary"
                        :onClick #(.select-transaction this)}
                   (dom/i #js {:className "fa fa-fw fa-pencil"})))))))

  ;; Render a transaction in edit mode.
  (render-selected [this]
    (let [{:keys [input-transaction
                  init-state
                  add-tag?
                  input-tag]} (om/get-state this)
          {:keys [transaction/date
                  transaction/amount
                  transaction/currency
                  transaction/title
                  transaction/tags]} input-transaction
          {:keys [currencies]} (om/get-computed this)
          edited? (not= init-state input-transaction)]
      (html
        [:li.is-selected
         (opts {:key [uuid]
                :id  (str uuid)})
         [:div.row.small-collapse.medium-uncollapse.expanded
          [:div.columns.small-12.medium-3.large-2
           (opts {:key [uuid]})
           (->Datepicker
             {:key       [uuid]
              :input-only? true
              :value     (format/ymd-string->js-date
                           (:date/ymd date))
              :on-change #(om/update-state!
                           this assoc-in [:input-transaction :transaction/date :date/ymd]
                           (format/date->ymd-string %))})]
          [:div.columns.small-8.medium-3.large-2
           [:input
            {:value     (or amount "")
             :type      "number"
             :on-change #(om/update-state! this assoc-in [:input-transaction :transaction/amount] (.-value (.-target %)))}]]

          [:div.columns.small-4.medium-2.large-1
           [:select
            {:value     (:currency/code currency)
             :on-change #(om/update-state! this assoc-in [:input-transaction :transaction/currency :currency/code] (.-value (.-target %)))}
            (map-all
              currencies
              (fn [c]
                [:option
                 {:key   [(:currency/code c)]
                  :value (:currency/code c)}
                 (:currency/code c)]))]]
          [:div.columns.small-12.medium-4.large-2
           [:input
            {:value     (or title "")
             :type      "text"
             :on-change #(om/update-state! this assoc-in [:input-transaction :transaction/title] (.-value (.-target %)))}]]

          [:div.columns.small-9.medium-10.large-4
           [:div.label.secondary.tag
            [:a.button
             {:on-click #(om/update-state! this assoc :add-tag? true)}
             [:small [:strong "add..."]]]]
           (map-all
             tags
             (fn [t]
               (utils/tag t {:on-delete #(.delete-tag this t)})))
           (when add-tag?
             [:div
              (utils/click-outside-target #(om/update-state! this assoc :add-tag? false))
              [:ul.dropdown-menu
               (opts {:style {:position :absolute}})
               [:li
                (utils/tag-input
                  {:input-tag       input-tag
                   :on-change       #(om/update-state! this assoc :input-tag %)
                   :placeholder     "Add tag..."
                   :no-render-tags? true
                   :input-only?     true
                   :on-add-tag      #(.add-tag this %)})]]])]
          [:div.columns.small-3.medium-2.large-1.text-right
           [:a
            (opts (merge
                    {:style    (:margin "0.5em")
                     :on-click #(.save-edit this)}
                    (when-not edited?
                      {:class "secondary"})))
            [:i.fa.fa-fw.fa-check]]
           [:a
            {:on-click #(om/transact! this `[(transactions/deselect)
                                             :query/selected-transaction])}
            [:i.fa.fa-fw.fa-close]]]]])))
  (render [this]
    (let [{:keys [is-selected]} (om/get-computed this)]
      (if is-selected
        (.render-selected this)
        (.render-normal this)))))

(def ->Transaction (om/factory Transaction {:keyfn :db/id}))

(defn get-selected-transaction [props]
  (get-in props [:ui.component.transactions/selected-transaction]))

(defn filter-settings [component]
  (let [{:keys [tag-filter date-filter]} (om/get-state component)]
    (html
      [:div.transaction-filters
       [:div.row.collapse
        [:div.columns.small-3
         (filter/->TagFilter (om/computed {:tags (:filter/include-tags tag-filter)}
                                          {:on-change #(do
                                                        (om/update-state! component assoc :tag-filter {:filter/include-tags %})
                                                        (om/update-query! component assoc-in [:params :filter] (.filter component)))}))]
        [:div.columns.small-9
         (filter/->DateFilter (om/computed {:filter date-filter}
                                           {:on-change #(do
                                                         (om/update-state! component assoc :date-filter %)
                                                         (om/update-query! component assoc-in [:params :filter] (.filter component)))}))]]])))

(defui AllTransactions
  static om/IQueryParams
  (params [_]
    {:filter {}
     :transaction (om/get-query Transaction)})
  static om/IQuery
  (query [_]
    ['({:query/transactions ?transaction} {:filter ?filter})
     {:query/current-user [:user/uuid
                           {:user/currency [:currency/code
                                            :currency/symbol-native]}]}
     {:query/selected-transaction
      [{:ui.component.transactions/selected-transaction
        [:db/id
         :transaction/uuid
         :transaction/title
         :transaction/amount
         {:transaction/currency [:currency/code :currency/symbol-native]}
         {:transaction/tags [:tag/name]}
         {:transaction/date [:date/ymd]}
         {:transaction/project [:project/uuid :project/name]}
         {:transaction/type [:db/ident]}
         :transaction/conversion]}]}
     {:query/all-currencies [:currency/code]}
     {:query/all-projects [:project/uuid
                           :project/name]}
     {:proxy/add-transaction (om/get-query AddTransaction)}])

  Object
  (initLocalState [_]
    {:input-tag    ""
     :input-filter {}})

  (componentWillUnmount [this]
    (.deselect-transaction this))

  (select-transaction [this transaction]
    (om/transact! this `[(transactions/select ~{:transaction transaction})]))

  (deselect-transaction [this]
    (om/transact! this `[(transactions/deselect)]))

  (filter [this]
    (let [{:keys [date-filter tag-filter]} (om/get-state this)]
      (merge tag-filter date-filter)))

  (render [this]
    (let [{transactions          :query/transactions
           currencies :query/all-currencies
           user                  :query/current-user
           sel-transaction-props :query/selected-transaction
           add-transaction       :proxy/add-transaction} (om/props this)
          selected-transaction (get-selected-transaction sel-transaction-props)
          {:keys [add-transaction?]} (om/get-state this)
          input-filter (.filter this)]
      (html
        [:div#txs
         (opts {:style {:padding "1em"}})

         (if (or (seq transactions)
                 (some? (or (seq (:filter/include-tags input-filter))
                            (seq (:filter/exclude-tags input-filter))
                            (:filter/start-date input-filter)
                            (:filter/end-date input-filter)
                            (:filter/last-x-days input-filter))))
           [:div
            (filter-settings this)
            [:div#all-transactions
             (if (seq transactions)
               [:div.transactions
                ;(when selected-transaction
                ;  (utils/click-outside-target #(om/transact! this `[(transactions/deselect)
                ;                                                    :query/selected-transaction])))
                [:div.transaction-list
                 (opts {:style {:width "100%"}})
                 [:ul.no-bullet
                  (map (fn [props]
                         (->Transaction
                           (om/computed props
                                        {:user         user
                                         :currencies   currencies
                                         :is-selected  (= (:db/id selected-transaction)
                                                          (:db/id props))
                                         :on-tag-click #(do
                                                         (om/update-state! this update-in [:tag-filter :filter/include-tags] utils/add-tag %)
                                                         (om/update-query! this assoc-in [:params :filter] (.filter this)))})))
                       (sort-by #(get-in % [:transaction/date :date/timestamp]) > transactions))]]]
               [:div.empty-message
                [:div.lead
                 [:i.fa.fa-search.fa-fw]
                 "No transactions found with filters."]])]]
           [:div
            [:a.button
             (opts {:style {:visibility :hidden}})
             "Button"]
            [:div.empty-message.text-center
             [:i.fa.fa-usd.fa-4x]
             [:i.fa.fa-eur.fa-4x]
             [:i.fa.fa-yen.fa-4x]
             ;[:i.fa.fa-th-list.fa-5x]
             [:div.lead
              "Information underload, no transactions are added."
              [:br]
              [:br]
              "Start tracking your money and "
              [:a.link
               {:on-click #(om/update-state! this assoc :add-transaction? true)}
               "add a transaction"]
              "."]]])
         (when add-transaction?
           (let [on-close #(om/update-state! this assoc :add-transaction? false)]
             (utils/modal {:content  (->AddTransaction
                                       (om/computed add-transaction
                                                    {:on-close on-close}))
                           :on-close on-close})))]))))

(def ->AllTransactions (om/factory AllTransactions))