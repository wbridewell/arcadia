(ns arcadia.component.motion-planner
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component]]))

;; Actions, or more accurately, requests to initiate an action, do not operate
;; directly on the environment. Instead, once they are focused on, they must be
;; translated into a format that can be processed by the environment. Here, for
;; instance, the request to push a button is turned into an element that has
;; an action-command (a button name) that ARCADIA then passes to the environment
;; to initiate the effects.

;; NOTE:
;; It takes roughly 150 ms to plan a motor response, and under time pressure,
;; people can react that quickly, but often there is an extra 80 ms delay that
;; may make room for alternative responses to compete.
;;
;; Haith, A. M., Pakpoor, J., & Krakauer, J. W. (2016). Independence of
;; Movement Preparation and Movement Initiation. Journal of Neuroscience,
;; 36(10), 3007–3015.

;; NOTE:
;; Only actions that have a corresponding environment action will be delayed.
;; For instance, another component could engage in processing just based on
;; the action request. That happens with "memorize," which is used as a quick
;; and dirty approach to getting elements into some nebulous notion of
;; working memory. So memorization may not be delayed, but multiple button
;; presses will be subject to delay because the environmental action will be
;; withheld for a specific period of time.


(def ^:private delay-interval 150)

(def ^:private cycle-time 25)

(defn- push-button [e]
  (when e
    {:name "push-button"
     :arguments {:action-command (-> e :arguments :button-id)}
     :world nil
     :type "environment-action"}))

;; map predicates that recognize particular types of action requests to
;; functions that create particular environmental actions.
;;
;; NOTE: this map should be defined in the model file with the tasks, but it's
;; here now for testing purposes.

(def request-schema-maps
  [{:predicate #(and (= (:type %) "action") (= (:name %) "push-button"))
    :schema #(push-button %1)}])

(defn- env-action [r m]
  (first (remove nil? (map #(when ((:predicate %) r) (:schema %)) m))))

(defn- request-to-act [r m]
  (when-let [s (env-action r m)]
    (s r)))

(defn- report-conflict [winner losers]
  {:name "conflict-resolution"
   :arguments {:kind "action" :winner winner :losers losers}
   :world nil
   :type "instance"})

(defn- wm-tasks [content]
  (filter #(and (= (:name %) "task")
                (= (:world %) "task-wm"))
          content))

(defn- handles [tasks]
  (set (map #(keyword (-> % :arguments :handle)) tasks)))

(defn- resolve-conflict [alts content]
  ;; check task handle against action handles and see which one should
  ;; be preferred.
  (let [[a c] ((juxt filter remove) #((handles (wm-tasks content)) (-> % :arguments :task)) alts)]
    [(first a) (concat (rest a) c)]))


(defrecord MotionPlanner [buffer action conflicts delay]
  Component
  (receive-focus
    [component focus content]
    (reset! (:action component) nil)
    (when (pos? @(:delay component))
      (swap! (:delay component) - cycle-time))
    (when (<= @(:delay component) 0)
      (let [[a cs] (resolve-conflict @(:buffer component) content)]
        (reset! (:action component) a)
        (reset! (:conflicts component) cs)
        (reset! (:buffer component) [])))
    (when (= (:type focus) "action")
      (swap! (:buffer component) conj focus)
      (when (<= @(:delay component) 0)
        (reset! (:delay component) delay-interval))))

  (deliver-result
    [component]
    (when-let [env-act (and @(:action component) (request-to-act @(:action component) request-schema-maps))]
      (list env-act (report-conflict @(:action component) @(:conflicts component))))))


(defmethod print-method MotionPlanner [comp ^java.io.Writer w]
  (.write w (format "MotionPlanner{}")))

;; use a vector for the buffer to get oldest elements in the front.
(defn start []
  (->MotionPlanner (atom []) (atom nil) (atom nil) (atom 0)))
