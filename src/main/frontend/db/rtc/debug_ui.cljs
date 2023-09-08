(ns frontend.db.rtc.debug-ui
  "Debug UI for rtc module"
  (:require [frontend.ui :as ui]
            [rum.core :as rum]
            [frontend.db.rtc.core :as rtc-core]
            [cljs.core.async :as async :refer [go <! chan go-loop]]
            [cljs.core.async.interop :refer [p->c]]
            [frontend.db.rtc.op :as op]
            [frontend.state :as state]
            [frontend.db.rtc.ws :as ws]
            [fipp.edn :as fipp]
            [frontend.db.rtc.full-upload-download-graph :as full-upload-download-graph]
            [frontend.util :as util]
            [frontend.handler.notification :as notification]))

(def ^:private debug-state (atom nil))
(def debug-graph-uuid "6478874f-20a7-4335-9379-4cfb1cfa1b25")

(defn- <start
  []
  (go
    (let [repo (state/get-current-repo)
          state (<! (rtc-core/<init))]
      (if-let [graph-uuid (<! (p->c (op/<get-graph-uuid repo)))]
        (do (reset! debug-state state)
            (<! (rtc-core/<loop-for-rtc state graph-uuid (state/get-current-repo)))
            state)
        (do (notification/show! "not a rtc-graph" :error false)
            nil)))))

(defn- <stop
  []
  (async/close! @(:*stop-rtc-loop-chan @debug-state)))

(defn- push-pending-ops
  []
  (async/put! (:client-op-update-chan @debug-state) true))

(defn- <download-graph
  [repo graph-uuid]
  (go
    (let [state (<! (rtc-core/<init))]
      (<! (full-upload-download-graph/<download-graph state repo graph-uuid)))))

(defn- <upload-graph
  []
  (go
    (let [state (<! (rtc-core/<init))
          repo (state/get-current-repo)]
      (<! (full-upload-download-graph/<upload-graph state repo)))))

(rum/defcs rtc-debug-ui <
  rum/reactive
  (rum/local nil ::graph-uuid)
  (rum/local nil ::local-tx)
  (rum/local nil ::ops)
  (rum/local nil ::ws-state)
  (rum/local nil ::download-graph-to-repo)
  [state]
  (let [s (rum/react debug-state)
        rtc-state (and s (rum/react (:*rtc-state s)))]
    [:div
     [:a
      {:on-mouse-down (fn [_] (go
                                (let [repo (state/get-current-repo)
                                      {:keys [local-tx ops]}
                                      (<! (p->c (op/<get-ops&local-tx repo)))
                                      graph-uuid (<! (p->c (op/<get-graph-uuid repo)))]
                                  (reset! (::local-tx state) local-tx)
                                  (reset! (::ops state) (count ops))
                                  (reset! (::graph-uuid state) graph-uuid)
                                  (reset! (::ws-state state) (and s (ws/get-state @(:*ws s)))))))}
      (ui/icon "refresh" {:style {:font-size 20}})]

     [:pre (-> {:graph @(::graph-uuid state)
                :rtc-state rtc-state
                :ws (and s (ws/get-state @(:*ws s)))
                :local-tx @(::local-tx state)
                :pending-ops @(::ops state)}
               (fipp/pprint {:width 20})
               with-out-str)]
     (if (or (nil? s)
             (= :closed rtc-state))
       (ui/button "start" {:class "my-2"
                           :on-click (fn [] (<start))})

       [:div.my-2
        [:div.my-2 (ui/button (str "send pending ops")
                         {:on-click (fn [] (push-pending-ops))})]
        [:div (ui/button "stop" {:on-click (fn [] (<stop))})]])
     [:hr]
     [:div.flex
      (ui/button (str "download graph to")
                 {:class "mr-2"
                  :on-click (fn []
                              (go
                                (when-let [repo @(::download-graph-to-repo state)]
                                  (<! (<download-graph repo debug-graph-uuid))
                                  (notification/show! "download graph successfully"))))})
      (ui/ls-textarea {:on-change (fn [e] (reset! (::download-graph-to-repo state) (util/evalue e)))})]
     [:div.flex.my-2
      (ui/button (str "upload graph") {:on-click (fn []
                                                   (go
                                                     (<! (<upload-graph))
                                                     (notification/show! "upload graph successfully")))})]]))
