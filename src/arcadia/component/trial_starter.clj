(ns arcadia.component.trial-starter
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.descriptors :as d]))

(defn- push-button []
  {:name "push-button"
   :arguments {:button-id :go-button}
   :type "action"
   :world nil})

(defrecord TrialStarter [buffer flag parameters]
  Component
  (receive-focus
    [component focus content]
    (when @(:buffer component) (reset! (:buffer component) nil))
   ;; we may need to change this cond if we separate color from text in the way
   ;; that Glaser and Glaser do
    (cond (d/element-matches? focus :name "push-button" :button-id :go-button)
         ;; if our button push is the focus, record that we pushed the button.
          (reset! flag true)

          (and (false? @flag) (d/not-any-element content :name "image-segmentation" :segments seq))
         ;; if there are no segments (intertrial) and we haven't pushed the button yet,
         ;; push the button.
          (reset! (:buffer component) (push-button))

          (and (true? @flag) (d/some-element content :name "image-segmentation" :segments seq))
         ;; if there are text segments, reset that we haven't pushed the button.
         ;; this indicates that the trial has started.
          (reset! flag false)))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method TrialStarter [comp ^java.io.Writer w]
  (.write w (format "TrialStarter{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->TrialStarter (atom nil) (atom false) p)))