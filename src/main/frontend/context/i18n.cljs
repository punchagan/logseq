(ns frontend.context.i18n
  "This ns is a system component that handles translation for the entire
  application. The ns dependencies for this ns must be small since it is used
  throughout the application."
  (:require [frontend.dicts :as dicts]
            [tongue.core :as tongue]
            [cljs.core :refer [PersistentVector]]
            [frontend.state :as state]))

(def dicts (merge dicts/dicts {:tongue/fallback :en}))

(extend-type PersistentVector
  tongue/IInterpolate
  (interpolate-named [v _dicts _locale interpolations]
    (mapv (fn [x]
            (if (and (keyword? x)
                     (= "arg" (namespace x)))
              (get interpolations x)
              x)) v))

  (interpolate-positional [v _dicts _locale interpolations]
    (mapv (fn [x]
            (if (and (vector? x)
                     (= :arg (first x)))
              (nth interpolations (second x))
              x)) v)))

(def translate
  (tongue/build-translate dicts))

(defn t
  [& args]
  (let [preferred-language (keyword (state/sub :preferred-language))]
    (apply translate preferred-language args)))

(defn- fetch-local-language []
  (.. js/window -navigator -language))

;; TODO: Fetch preferred language from backend if user is logged in
(defn start []
  (let [preferred-language (state/sub :preferred-language)]
    (when (nil? preferred-language)
      (state/set-preferred-language! (fetch-local-language)))))
