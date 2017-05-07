(ns eponai.common.ui.store.stream-settings
  (:require
    [eponai.common.ui.dom :as dom]
    [eponai.common.ui.stream :as stream]
    [eponai.client.parser.message :as msg]
    [eponai.client.chat :as client.chat]
    [om.next :as om :refer [defui]]
    [taoensso.timbre :refer [debug warn]]
    [eponai.common.ui.elements.css :as css]
    [eponai.common.ui.chat :as chat]
    [eponai.common.ui.elements.menu :as menu]
    [eponai.common.ui.elements :as elements]
    #?(:cljs
       [eponai.web.utils :as utils])
    [eponai.common.parser :as parser]
    [eponai.common.ui.elements.callout :as callout]
    [eponai.client.routes :as routes]
    [eponai.common.ui.elements.grid :as grid]))

(defn- get-store [component-or-props]
  (get-in (om/get-computed component-or-props) [:store]))

(defn- get-store-id [component-or-props]
  (:db/id (get-store component-or-props)))


(defui StreamSettings
  static om/IQuery
  (query [_]
    [:query/messages
     {:proxy/stream (om/get-query stream/Stream)}
     {:query/stream [:stream/state
                     :stream/token]}
     {:query/stream-config [:ui.singleton.stream-config/publisher-url]}
     {:query/chat [:chat/store
                   ;; ex chat modes: :chat.mode/public :chat.mode/sub-only :chat.mode/fb-authed :chat.mode/owner-only
                   :chat/modes
                   {:chat/messages [:chat.message/client-side-message?
                                    {:chat.message/user [:user/email {:user/profile [{:user.profile/photo [:photo/path]}]}]}
                                    :chat.message/text
                                    :chat.message/timestamp]}]}
     {:query/auth [:db/id]}])
  chat/ISendChatMessage
  (get-chat-message [this]
    (:chat-message (om/get-state this)))
  (reset-chat-message! [this]
    (om/update-state! this assoc :chat-message ""))
  ;; This chat store listener is a copy of what's in ui.chat
  client.chat/IStoreChatListener
  (start-listening! [this store-id]
    (client.chat/start-listening! (:shared/store-chat-listener (om/shared this)) store-id))
  (stop-listening! [this store-id]
    (client.chat/stop-listening! (:shared/store-chat-listener (om/shared this)) store-id))
  Object
  (componentWillUnmount [this]
    (client.chat/stop-listening! this (get-store-id this)))
  (componentDidUpdate [this prev-props prev-state]
    (let [old-store (get-store-id prev-props)
          new-store (get-store-id (om/props this))]
      (when (not= old-store new-store)
        (client.chat/stop-listening! this old-store)
        (client.chat/start-listening! this new-store))))
  (componentDidMount [this]
    (client.chat/start-listening! this (get-store-id this)))
  (render [this]
    (let [{:keys [store]} (om/get-computed this)
          {:query/keys [stream chat stream-config]
           :as         props} (om/props this)
          stream-state (:stream/state stream)
          stream-token (:stream/token stream)
          chat-message (:chat-message (om/get-state this))
          message (msg/last-message this 'stream-token/generate)]
      (dom/div
        {:id "sulo-stream-settings"}
        ;(grid/row
        ;  (css/align :center)
        ;  (grid/column
        ;    (grid/column-size {:small 12 :medium 6})
        ;    (dom/div
        ;      (css/add-class :stream-status)
        ;      (dom/div
        ;        nil
        ;        (cond (or (nil? stream-state)
        ;                  (= :stream.state/offline stream-state))
        ;              (dom/i {:classes ["status offline fa fa-circle fa-fw"]})
        ;              (= stream-state :stream.state/online)
        ;              (dom/i {:classes ["status online fa fa-check-circle fa-fw"]})
        ;              (= stream-state :stream.state/live)
        ;              (dom/i {:classes ["status live fa fa-wifi fa-fw"]})))
        ;      (dom/div nil
        ;               (dom/span nil "Your stream is ")
        ;               (if (or (nil? stream-state)
        ;                       (= :stream.state/offline stream-state))
        ;                 (dom/span {:classes ["status" "offline"]} "Offline")
        ;                 (dom/span
        ;                   (cond->> {:classes ["status"]}
        ;                            (= stream-state :stream.state/online)
        ;                            (css/add-class :online)
        ;                            (= stream-state :stream.state/live)
        ;                            (css/add-class :live))
        ;                   (condp = stream-state
        ;                     :stream.state/online "Online"
        ;                     :stream.state/live "Live"
        ;                     (warn "Unknown stream-state: " stream-state)))))
        ;      (dom/div
        ;        nil
        ;        (cond
        ;          (= stream-state :stream.state/online)
        ;          (dom/a
        ;            (->> (css/button {:onClick #(om/transact! this [(list 'stream/go-live {:store-id (:db/id store)}) :query/stream])})
        ;                 (css/add-class :highlight))
        ;            (dom/strong nil "Go live!"))
        ;          (or (nil? stream-state) (= stream-state :stream.state/offline))
        ;          (dom/a
        ;            (css/button-hollow {:onClick #(binding [parser/*parser-allow-local-read* false]
        ;                                           (om/transact! this [{:query/stream [:stream/state]}]))})
        ;            (dom/i {:classes ["fa fa-refresh fa-fw"]})
        ;            (dom/strong nil "Refresh"))
        ;          (= stream-state :stream.state/live)
        ;          (dom/a
        ;            (css/button-hollow {:onClick #(om/transact! this [(list 'stream/go-offline {:store-id (:db/id store)}) :query/stream])})
        ;            (dom/strong nil "Stop streaming"))
        ;          :else
        ;          (warn "Unknown stream-state: " stream-state))))))


        (grid/row
          nil
          (grid/column
            (grid/column-size {:small 12 :large 6})
            (dom/div
              (css/add-class :dashboard-section)
              (dom/div
                (css/add-class :section-title)
                (dom/h1 nil (dom/small nil "Stream ")
                        (dom/span
                          (cond->> (css/add-class :label)
                                   (= stream-state :stream.state/offline)
                                   (css/add-class :primary)
                                   (= stream-state :stream.state/online)
                                   (css/add-class :success)
                                   (= stream-state :stream.state/live)
                                   (css/add-class :highlight))
                          (name stream-state)))
                (cond
                  (= stream-state :stream.state/online)
                  (dom/a
                    (->> (css/button {:onClick #(om/transact! this [(list 'stream/go-live {:store-id (:db/id store)}) :query/stream])})
                         (css/add-class :highlight))
                    (dom/strong nil "Go live!"))
                  (or (nil? stream-state) (= stream-state :stream.state/offline))
                  (dom/a
                    (css/button-hollow {:onClick #(binding [parser/*parser-allow-local-read* false]
                                                   (om/transact! this [:query/stream]))})
                    (dom/i {:classes ["fa fa-refresh fa-fw"]})
                    (dom/strong nil "Refresh"))
                  (= stream-state :stream.state/live)
                  (dom/a
                    (css/button-hollow {:onClick #(om/transact! this [(list 'stream/go-offline {:store-id (:db/id store)}) :query/stream])})
                    (dom/strong nil "Stop streaming"))
                  :else
                  (warn "Unknown stream-state: " stream-state)))
              (callout/callout-small
                nil
                (stream/->Stream
                  (om/computed
                    (:proxy/stream props)
                    {:hide-chat?            true
                     :store                 store
                     :wowza-player-opts     {:on-error-retry-forever? true
                                             :on-started-playing      #(when (= stream-state :stream.state/offline)
                                                                         (om/transact! this `[(~'stream/ensure-online
                                                                                                ~{:store-id (:db/id store)})
                                                                                              :query/stream]))}
                     ;; Allow all states, as there might be something wrong with the
                     ;; state change communication.
                     :allowed-stream-states #{:stream.state/offline
                                              :stream.state/live
                                              :stream.state/online}})))))
          (grid/column
            (grid/column-size {:small 12 :large 6})

            (dom/div
              (css/add-class :dashboard-section)
              (dom/div
                (css/add-class :section-title)
                (dom/h1 nil (dom/small nil "Live chat")))
              (callout/callout-small
                (css/add-class :chat-container)
                (dom/div
                  (css/add-class :chat-content)
                  (dom/div
                    (css/add-class :chat-messages)
                    (elements/message-list (chat/get-messages this)))
                  (dom/div
                    (css/add-class :chat-input)
                    (grid/row
                      (css/align :middle)
                      (grid/column
                        nil
                        (dom/input
                          {:type        "text"
                           :placeholder "Say someting..."
                           :value       (or chat-message "")
                           :onKeyDown   #?(:cljs
                                                #(when (utils/enter-pressed? %)
                                                  (chat/send-message this))
                                           :clj identity)
                           :onChange    #(om/update-state! this assoc :chat-message (.-value (.-target %)))}))
                      (grid/column
                        (css/add-class :shrink)
                        (dom/a
                          (->> {:onClick #(chat/send-message this)}
                               (css/button-hollow)
                               (css/add-class :primary))
                          (dom/i {:classes ["fa fa-send-o fa-fw"]}))))))))))
        (grid/row-column
          nil
          (dom/div
            (css/add-class :dashboard-section)
            (callout/callout-small
              nil
              (grid/row
                (grid/columns-in-row {:small 3})
                (grid/column
                  (css/text-align :center)
                  (dom/h2 nil (dom/small nil "Viewers"))
                  (dom/p (css/add-class :stat) 0))
                (grid/column
                  (css/text-align :center)
                  (dom/h2 nil (dom/small nil "Messages/min"))
                  (dom/p (css/add-class :stat) 0))
                (grid/column
                  (css/text-align :center)
                  (dom/h2 nil (dom/small nil "Payments"))
                  (dom/p (css/add-class :stat) 0))))))

        (grid/row
          nil
          (grid/column
            (grid/column-size {:small 12 :large 6})
            (dom/div
              (css/add-class :dashboard-section)
              (dom/div
                (css/add-class :section-title)
                (dom/h1 nil (dom/small nil "Encoder setup")))
              (callout/callout-small
                nil
                (grid/row
                  (css/align :bottom)
                  (grid/column
                    (grid/column-size {:small 12 :medium 8})
                    (dom/label nil "Server URL")
                    (dom/input {:type  "text"
                                :id    "input.stream-url"
                                :value (or (:ui.singleton.stream-config/publisher-url stream-config) "")})))
                (grid/row
                  (css/align :bottom)
                  (grid/column
                    (grid/column-size {:small 12 :medium 8})
                    (dom/label nil "Stream Key")
                    (dom/input {:type        "text"
                                :value       (or stream-token "")
                                ;:defaultValue (if (msg/final? message)
                                ;                (:token (msg/message message))
                                ;                "")
                                :placeholder "Create a stream key..."}))
                  (grid/column
                    nil
                    (dom/a
                      (css/button-hollow {:onClick #(msg/om-transact! this `[(stream-token/generate ~{:store-id (:db/id store)})
                                                                             {:query/stream [:stream/token]}])})
                      (dom/span nil "Create new key")))))))
          (grid/column
            nil
            (dom/div
              (css/add-class :dashboard-section)
              (dom/div
                (css/add-class :section-title)
                (dom/h1 nil (dom/small nil "Setup checklist")))
              (callout/callout
                (css/add-class :setup-checklist)
                (dom/dl
                  nil
                  (dom/dt
                    nil
                    (dom/h2 nil (dom/small nil "Setup encoding software")))
                  (dom/dd
                    nil
                    (dom/p nil
                           (dom/span nil "Before you can start streaming on SULO Live, you need to download encoding software, and then set it up.
                         Learn more about setting up encoders in our ")
                           (dom/a {:href   (routes/url :help/first-stream)
                                   :target "_blank"}
                                  (dom/span nil "First Stream Guide"))
                           (dom/span nil ". You'll need to use the Server URL and Stream key to configure the encoding software.")))
                  (dom/dt
                    nil
                    (dom/h2 nil (dom/small nil "Publish stream")))
                  (dom/dd
                    nil
                    (dom/p nil (dom/span nil "To start streaming, start your encoder.
                  Hit Refresh and the status bar will indicate when your streaming content is being published to our servers.
                  At this point you'll be able to see a preview of your stream, but you're not yet visible to others on SULO Live.")))
                  (dom/dt
                    nil
                    (dom/h2 nil (dom/small nil "Go live")))
                  (dom/dd
                    nil
                    (dom/p nil (dom/span nil "To go live and hangout with your crowd, click 'Go live' and your stream will be visible on your store page.
                  To stop streaming, stop your encoder. Ready to go live again? Any time you send content, hit 'Go live' and you're live!"))))))))
        ))))

(def ->StreamSettings (om/factory StreamSettings))