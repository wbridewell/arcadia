(ns arcadia.component.covert-return-inhibitor
  "There has been considerable research on the phenomenon known as inhibition
  of return, which refers to the likelihood that people will view other
  areas of their visual fields instead of continually returning to the same
  region or object.

  This component implements a similar effect for covert visual attention. The
  duration of the effect is unknown but thought to be relatively short. Here,
  the assumption is that the inhibition is dropped once the system
  focuses on another area in the visual field.

  Focus Responsive
    * object
    * fixation

  Focusing on an object initiates covert inhibition of return, which
  continues until a fixation receives focus.

  Default Behavior
  Produce a fixation inhibition to the last seen object if visual attention
  has not shifted to another area in the visual field.

  Produces
   * fixation-inhibition
       includes :xpos and :ypos indicating the center of the object at the
       inhibited region, a :reason that identifies this as a
       \"covert-return-inhibition\", and a :mode that says whether regions that
       \"include\" or \"exclude\" this point should be inhibited.

  Refer to:

  Inhibition of Return in the Covert Deployment of Attention: Evidence from Human Electrophysiology
  John J. McDonald, Clayton Hickey, Jessica J. Green, and Jennifer C. Whitman
  Journal of Cognitive Neuroscience 2009 21:4, 725-733

  Claim that covert attention is inhibited for a very short period of time.
  (a few hundred ms, but possibly until something else is seen)

  Claim to show the first evidence that IOR operates over covert attention
  and isn't only a phenomenon of overt visual attention (measured by
  eye movement).

  mode: exclude - inhibit regions that exclude the point
        include - inhibit regions that include the point"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.vision.regions :as reg]
            [arcadia.utility [general :as g] [objects :as obj]]))

(defn make-inhibition [location component]
  {:name "fixation-inhibition"
   :arguments {:region location
               :mode "include"
               :reason "covert-return-inhibition"}

   :world nil
   :source component
   :type "instance"})


(defrecord CovertReturnInhibitor [buffer]
  Component
  (receive-focus
   [component focus content]
    ;; unclear what cancels inhibition. probably not just a cycle count.
    ;; the original paper doesn't count scene dynamics, object tracking, etc.
    ;; we're going to go with "until something else is seen".

    ;; Once CRI has triggered, keep it going until there is another fixation.
    ;; So each cycle, check whether
    ;;    (a) there's a new object or
    ;;    (b) there's a CRI left over from the last cycle.
   (reset! buffer nil)
   (let [old-CRI (g/find-first #(and (= (:name %) "fixation-inhibition")
                                     (= (-> % :arguments :reason) "covert-return-inhibition"))
                               content)]
     (cond
       (and (= (:name focus) "object")
            (= (:world focus) nil))
       (some-> (obj/get-region focus content) reg/center (make-inhibition component)
               (->> (reset! buffer)))

       (= (:name focus) "fixation")
       (reset! buffer nil)

       old-CRI
       (reset! buffer old-CRI))))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method CovertReturnInhibitor [comp ^java.io.Writer w]
  (.write w (format "CovertReturnInhibitor{}")))

(defn start []
  (->CovertReturnInhibitor (atom nil)))
