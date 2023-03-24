(ns arcadia.component.highlighter.gaze-target
  "When a gaze action targets an object, this component starts firing to ensure
  attention is on the object after the action ends. It may take time to focus
  attention on the object because the system cannot focus on proto-objects on
  things during a saccade. To accommodate this, the component keeps firing
  until an object file becomes the focus of attention. That indicates that
  something was successfully fixated upon after the gaze action.

  Focus Responsive
    * object

  Stops requesting a fixation to the gaze-related object when an object
  receives focus.

  Default Behavior
  If there is a gaze action request, request a fixation to the proto-objects
  associated with the gaze. Continue to make this request until some object
  file is the focus of attention.

  Produces
   * fixation
       includes an :object element that should receive fixation and a :reason
       that specifies this to be a \"gaze\" related shift of covert attention."
  (:require [arcadia.utility.objects :as obj]
            [arcadia.component.core :refer [Component]]
            [arcadia.utility.general :as g]))
;; NOTE: References to "scan" are for a model that is still in progress.

(defn- make-fixation [object]
  {:name "fixation"
   :arguments {:object object
               :reason "gaze"}
   :world nil
   :type "instance"})

(defn- updated-gaze
  "Returns an updated version of the object associated with a gaze element if
  any exists."
  [content]
  (obj/updated-object (:target (:arguments (g/find-first #(and (= (:name %) "gaze")
                                                               (:saccading? (:arguments %)))
                                                         content)))
                      content))

(defn- updated-fixation
  "Returns an updated version of the object associated with a gaze fixation
  request unless the focus is on an object file."
  [focus content]
  (when (not (= (:name focus) "object"))
    (obj/updated-object (:object (:arguments (g/find-first #(and (= (:name %) "fixation")
                                                                 (= (:reason (:arguments %)) "gaze"))
                                                           content)))
                        content)))

(defrecord GazeTargetHighlighter [buffer]
  Component
  (receive-focus
    [component focus content]
    (let [target (or (updated-gaze content)
                     (updated-fixation focus content))]
      (condp = (:name target)
        "object" (reset! (:buffer component) (make-fixation target))
        "scan" (reset! (:buffer component) target)
        (reset! (:buffer component) nil))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method GazeTargetHighlighter [comp ^java.io.Writer w]
  (.write w (format "GazeTargetHighlighter{}")))

(defn start []
  (->GazeTargetHighlighter (atom nil)))
