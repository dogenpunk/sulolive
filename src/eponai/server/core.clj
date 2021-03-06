(ns eponai.server.core
  (:gen-class)
  (:require
    [com.stuartsierra.component :as component]
    [environ.core :as env]
    [eponai.common.validate]
    [eponai.server.parser.read]
    [eponai.server.parser.mutate]
    [aleph.netty]
    [taoensso.timbre :refer [debug error info]]
    ;; Dev/debug require
    [eponai.server.system :as system]
    [clojure.spec.alpha :as s]
    [eponai.common.mixpanel :as mixpanel]))

(defn- make-system [{::keys [system-fn] :as opts}]
  {:pre [(ifn? (::system-fn opts))]}
  (let [default-port 3000
        env-port (try
                   (Long/parseLong (env/env :port))
                   (catch Exception e
                     nil))
        port (or (:port opts) env-port default-port)
        _ (info "Using port: " port)
        config (merge {:port port :env env/env} opts)]
    (system-fn config)))

(defn -main [& _]
  (try
    (let [is-demo? (some? (env/env :sulo-demo))
          system (component/start-system (make-system (cond-> {::system-fn (if is-demo? system/demo-system system/prod-system)}
                                                              (env/env :server-allow-http)
                                                              (assoc ::system/disable-ssl true))))
          server (get-in system [:system/aleph :server])]
      (mixpanel/set-token (if is-demo? ::mixpanel/token-tests ::mixpanel/token-release))
      (aleph.netty/wait-for-close server))
    (catch Throwable e
      (error "Error in -main: " e)
      (.printStackTrace e)
      (throw e))))

(defn main-release-no-ssl
  []
  (debug "Running repl in production mode without ssl")
  (mixpanel/set-token ::mixpanel/token-release)
  (component/start-system
    (make-system {::system/disable-ssl true
                  ::system-fn system/prod-system})))

(defn make-dev-system
  "For repl, debug and test usages. Takes an optional map. Returns the aleph-server."
  [& [opts]]
  {:pre [(or (nil? opts) (map? opts))]}
  (s/check-asserts true)
  (mixpanel/set-token ::mixpanel/token-dev)
  (make-system (merge {::system-fn system/dev-system} opts)))

(defn system-for-tests [& [{:keys [conn port] :as opts}]]
  {:pre [(or (nil? opts) (map? opts))]}
  (s/check-asserts true)
  (mixpanel/set-token ::mixpanel/token-tests)
  (make-system
    (merge {:port                  (or port 0)
            ::system-fn            system/test-system
            ::system/provided-conn conn}
           opts)))
