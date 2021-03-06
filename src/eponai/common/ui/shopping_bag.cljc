(ns eponai.common.ui.shopping-bag
  (:require
    [eponai.common.ui.dom :as dom]
    [om.next :as om :refer [defui]]
    [eponai.common.ui.elements.css :as css]
    [eponai.common.ui.elements.grid :as grid]
    [eponai.common.ui.utils :as utils]
    [eponai.common.ui.router :as router]
    [taoensso.timbre :refer [debug]]
    [eponai.client.routes :as routes]
    [eponai.common.ui.elements.menu :as menu]
    [eponai.common.ui.icons :as icons]
    [eponai.web.ui.photo :as photo]
    [eponai.common.ui.elements.callout :as callout]
    [eponai.web.ui.button :as button]
    [eponai.common.mixpanel :as mixpanel]
    [eponai.common.ui.product :as product]
    [eponai.web.ui.content-item :as ci]
    [eponai.client.auth :as auth]
    [eponai.common.shared :as shared]
    [eponai.common.analytics.google :as ga]))

(defn items-by-store [items]
  (group-by #(get-in % [:store.item/_skus :store/_items]) items))

(defn compute-item-price [items]
  (reduce + (map :store.item/price items)))

(defn store-element [s]
  (let [store-name (get-in s [:store/profile :store.profile/name])]
    (dom/div
      (->> (css/add-class :store-container)
           (css/align :center))

      (dom/a
        {:href (routes/store-url s :store)}
        (photo/store-photo s {:transformation :transformation/thumbnail})
        (dom/strong
          (css/add-class :store-name) store-name)))))

(defn sku-menu-item [component sku]
  (let [item (get sku :store.item/_skus)
        {:store.item/keys [price photos]
         product-id       :db/id
         item-name        :store.item/name :as product} item
        {:store.item.photo/keys [photo]} (first (sort-by :store.item.photo/index photos))]
    (menu/item
      (css/add-class :sl-productlist-item--row)
      (grid/row
        (->> (css/align :middle)
             (css/add-class :item)
             (css/add-class :sl-productlist-item--cell))
        (grid/column
          (grid/column-size {:small 2 :medium 1})
          (photo/product-preview product {:transformation :transformation/thumbnail}))

        (grid/column
          (grid/column-size {:small 8})

          (dom/div nil
                   (dom/a
                     (->> {:href (product/product-url item)}
                          (css/add-class :name))
                     (dom/span nil item-name)))
          (dom/div nil
                   (dom/span (css/add-class :variation) (:store.item.sku/variation sku))))



        (grid/column
          (->>
            (css/add-class :shrink)
            (css/align :right)
            (css/add-class :sl-productlist-item--cell))
          (button/default-hollow
            {:onClick #(.remove-item component sku)}
            (dom/span nil "Remove"))
          )
        (grid/column
          (->> (css/text-align :right)
               (css/add-class :sl-productlist-item--cell))
          (dom/div nil
                   (dom/span
                     (css/add-class :price)
                     (utils/two-decimal-price price))))

        ))))

(defn store-items-element [component skus-by-store]
  (let [{:query/keys [auth]} (om/props component)]
    (dom/div
      nil
      (map
        (fn [[s skus]]
          (let [store-is-open? (= :status.type/open (-> s :store/status :status/type))]
            (when store-is-open?
              (dom/div
                (->> (css/callout)
                     (css/add-class :cart-checkout-item))
                (store-element s)
                (menu/vertical
                  nil
                  (map #(sku-menu-item component %) skus))
                (grid/row
                  (->> (css/align :middle)
                       (css/text-align :right)
                       (css/add-class :item))
                  (let [item-price (compute-item-price (map #(get % :store.item/_skus) skus))
                        shipping-price 0]
                    (grid/column
                      nil
                      (dom/p (css/add-class :total-price)
                             (dom/span nil "Total: ")
                             (dom/strong nil (utils/two-decimal-price (+ item-price shipping-price))))
                      (button/button-cta
                        {:onClick #(.checkout component s skus)}
                        (dom/span nil "Checkout")))))))))
        skus-by-store))))

(defui ShoppingBag
  static om/IQuery
  (query [_]
    [{:query/cart [{:user/_cart [:db/id]}
                   {:user.cart/items [:store.item.sku/variation
                                      :db/id
                                      :store.item.sku/inventory
                                      {:store.item/_skus [:store.item/price
                                                          {:store.item/photos [{:store.item.photo/photo [:photo/id]}
                                                                               :store.item.photo/index]}
                                                          :store.item/name
                                                          {:store/_items [:db/id
                                                                          {:store/status [:status/type]}
                                                                          {:store/profile [:store.profile/name
                                                                                           {:store.profile/photo [:photo/id]}]}]}]}]}]}
     {:query/featured-items (om/get-query ci/ProductItem)}
     {:query/auth [:db/id :user/email {:user/profile [:user.profile/name]}]}
     :query/current-route])
  Object
  (remove-item [this sku]
    (debug "SKU REMOVE: " sku)
    (let [product (:store.item/_skus sku)]
      (ga/send-remove-from-bag product sku)
      (om/transact! this [(list 'shopping-bag/remove-item
                                {:sku (:db/id sku)})
                          :query/cart])))
  (componentWillReceiveProps [this p]
    (let [{:keys [did-mount?]} (om/get-state this)]
      (if-not did-mount?
        (om/update-state! this assoc :did-mount? true))))
  (checkout [this store skus]
    (let [{:query/keys [auth]} (om/props this)]
      (mixpanel/track "Checkout shopping bag" {:store-id   (:db/id store)
                                               :signed-in  (some? auth)
                                               :user-id    (:db/id auth)
                                               :user-name  (get-in auth [:user/profile :user.profile/name] "")
                                               :store-name (get-in store [:store/profile :store.profile/name])
                                               :item-count (count skus)
                                               :item-price (apply + (map #(get-in % [:store.item/_skus :store.item/price]) skus))})
      (if (some? auth)
        (routes/set-url! this :checkout {:store-id (:db/id store)})
        (auth/show-login (shared/by-key this :shared/login)))))

  (render [this]
    (let [{:query/keys [current-route cart locations featured-items]} (om/props this)
          {:keys [user.cart/items]} cart
          store-is-open? (fn [s] (= :status.type/open (-> s :store/status :status/type)))
          skus-by-store (filter #(store-is-open? (key %)) (items-by-store items))]
      (dom/div
        (cond->> {:id "sulo-shopping-bag-content"}
                 (empty? skus-by-store)
                 (css/add-class :empty))

        (grid/row-column
          nil
          (dom/h1 nil "Shopping bag"))
        (if (not-empty skus-by-store)
          (grid/row-column nil
                           (store-items-element this skus-by-store))

          (dom/div
            (css/add-class :empty-container)
            (grid/row-column
              (->> (css/text-align :center)
                   (css/add-class :cart-empty))
              (dom/div
                (css/add-class :empty-container)
                (dom/p (css/add-class :shoutout) "Your shopping bag is empty"))
              (icons/empty-shopping-bag)
              (button/button
                (button/sulo-dark (button/hollow {:href (routes/url :live {:locality (:sulo-locality/path locations)})}))
                (dom/span nil "Go to the market - start shopping")))))


        (callout/callout
          (css/add-classes [:section :content :featured])
          (grid/row-column
            nil
            (dom/div
              (css/add-class :section-title)
              (dom/h3 nil "Other gems")))
          (grid/row
            (->>
              (grid/columns-in-row {:small 3 :medium 4 :large 7}))
            (map
              (fn [p]
                (grid/column
                  (css/add-class :new-arrival-item)
                  (ci/->ProductItem (om/computed p
                                                 {:current-route current-route
                                                  :open-url?     true}))))
              (take 7 featured-items))))))))

(def ->ShoppingBag (om/factory ShoppingBag))

(router/register-component :shopping-bag ShoppingBag)