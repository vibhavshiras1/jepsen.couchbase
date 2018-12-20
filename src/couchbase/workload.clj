(ns couchbase.workload
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [couchbase [checker   :as cbchecker]
                       [clients   :as clients]
                       [cbclients :as cbclients]
                       [nemesis   :as cbnemesis]]
            [jepsen [checker     :as checker]
                    [generator   :as gen]
                    [independent :as independent]
                    [nemesis     :as nemesis]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.nemesis.time :as nt]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]))

;; Shared parameters across register workloads
(defn register-base [opts]
  {:client  (clients/register-client (cbclients/basic-client))
   :model   (model/cas-register)
   :checker (checker/compose
             (merge
              {:indep (independent/checker
                       (checker/compose
                        {:timeline (timeline/html)
                         :linear (checker/linearizable)}))}
              (if (opts :perf-graphs)
                {:perf (checker/perf)})))})
  

(defn Register-workload
  "Basic register workload"
  [opts]
  (let [oplimit (or (opts :oplimit) 1000)
        rate    (or (opts :rate)    0.5)
        nemesis nemesis/noop
        gen     (->> (independent/concurrent-generator 3 (range)
                       (fn [k]
                         (->> (gen/mix [(fn [_ _] {:type :invoke :f :read  :value nil})
                                        (fn [_ _] {:type :invoke :f :write :value (rand-int 5)})
                                        (fn [_ _] {:type :invoke :f :cas   :value [(rand-int 5) (rand-int 5)]})])
                              (gen/stagger (/ rate)))))
                     (gen/limit oplimit)
                     (gen/clients))]
    (merge (register-base opts)
           {:autofailover true
            :concurrency  60
            :replicas     1
            :replicate-to 0
            :nemesis      nemesis
            :generator    gen})))

(defn Failover-workload
  "Trigger non-linearizable (but expected) behaviour where mutations are lost if
  the active is failed over and they have not yet been replicated"
  [opts]
  (let [oplimit (or (opts :oplimit) 1000)
        rate    (or (opts :rate)    1/3)
        nemesis (nemesis/partition-random-node)
        gen     (->> (independent/concurrent-generator 3 (range)
                       (fn [k]
                         (->> (gen/mix [(fn [_ _] {:type :invoke, :f :read, :value nil})
                                        (fn [_ _] {:type :invoke, :f :write :value (rand-int 50)})])
                              (gen/stagger (/ rate)))))
                     (gen/limit oplimit)
                     (gen/nemesis (gen/seq [(gen/sleep 5)
                                            {:type :info :f :start}
                                            (gen/sleep 30)])))]
    (merge (register-base opts)
           {:autofailover true
            :concurrency  120
            :replicas     1
            :replicate-to 0
            :nemesis      nemesis
            :generator    gen})))

(defn MB30048-workload
  "Trigger non-linearizable behaviour where indeterminate operations are visible
  for some period of time, before disappearing"
  [opts]
  (let [oplimit (or (opts :oplimit) 1000)
        rate    (or (opts :rate)    1/3)
        nemesis (nemesis/partition-random-node)
        gen     (->> (independent/concurrent-generator 3 (range)
                       (fn [k]
                         (->> (gen/mix [(fn [_ _] {:type :invoke, :f :read, :value nil})
                                        (fn [_ _] {:type :invoke, :f :write :value (rand-int 50)})])
                              (gen/stagger (/ rate)))))
                     (gen/limit oplimit)
                     (gen/nemesis (gen/seq [(gen/sleep 5)
                                            {:type :info :f :start}
                                            (gen/sleep 30)])))]
    (merge (register-base opts)
           {:autofailover true
            :concurrency  120
            :replicas     1
            :replicate-to 1
            :nemesis      nemesis
            :generator    gen})))

(defn MB28525-workload
  "Trigger non-linearizable behaviour where successful mutations with replicate-to=1
  are lost due to promotion of the 'wrong' replica upon failover of the active"
  [opts]
  (let [oplimit (or (opts :oplimit) 1200)
        rate    (or (opts :rate)    1/3)
        nemesis (cbnemesis/partition-then-failover)
        gen     (->> (independent/concurrent-generator 3 (range)
                       (fn [k]
                         (->> (gen/mix [(fn [_ _] {:type :invoke, :f :read, :value nil})
                                        (fn [_ _] {:type :invoke, :f :write :value (rand-int 50)})])
                              (gen/stagger (/ rate)))))
                     (gen/limit oplimit)
                     (gen/nemesis (gen/seq [(gen/sleep 10)
                                            {:type :info :f :start-partition}
                                            (gen/sleep 5)
                                            {:type :info :f :start-failover}
                                            (gen/sleep 30)])))]
    (merge (register-base opts)
           {:autofailover false
            :concurrency  120
            :replicas     2
            :replicate-to 1
            :nemesis      nemesis
            :generator    gen})))

(defn Set-workload
  "Generic set workload. We model the set with a bucket, adding an item to the
  set corresponds to inserting a key. To read the set we use a dcp client to
  stream all mutations, keeping track of which keys exist"
  [opts]
  (let [addclient (cbclients/batch-insert-pool)
        delclient nil
        dcpclient (cbclients/simple-dcp-client)

        oplimit   (or (opts :oplimit) 50000)
        client    (clients/set-client addclient delclient dcpclient)]
    {:concurrency 250
     :batch-size  50
     :pool-size   4
     :client      client
     :checker     (checker/compose
                   (merge
                    {:set (checker/set)}
                    (if (opts :perf-graphs)
                      {:perf (checker/perf)})))
     :generator   (gen/phases
                    (->> (range)
                         (map (fn [x] {:type :invoke :f :add :value x}))
                         (gen/seq)
                         (gen/clients)
                         (gen/limit oplimit))
                    (gen/sleep 3)
                    (gen/clients (gen/once {:type :invoke :f :read :value nil})))}))

(defn WhiteRabbit-workload
  "Trigger lost inserts due to one of several white-rabbit variants"
  [opts]
  (let [addclient (cbclients/batch-insert-pool)
        delclient nil
        dcpclient (cbclients/simple-dcp-client)
        

        oplimit  (or (opts :oplimit) 2500000)
        client   (clients/set-client addclient delclient dcpclient)
        nemesis  (cbnemesis/rebalance-in-out)
        checker  (checker/compose
                  (merge
                   {:set (checker/set)}
                   (if (opts :perf-graphs)
                     {:perf (checker/perf)})))
        gen      (gen/phases
                   (->> (range)
                        (map (fn [x] {:type :invoke :f :add :value x}))
                        (gen/seq)
                        (gen/nemesis (gen/seq (cycle [(gen/sleep 10)
                                                      {:type :info :f :start}
                                                      (gen/sleep 10)
                                                      {:type :info :f :stop}])))
                        (gen/limit oplimit))
                   (gen/nemesis (gen/once {:type :info :f :stop}))
                   (gen/clients (gen/once {:type :invoke :f :read :value nil})))]
    {:custom-vbucket-count 64
     :concurrency 500
     :batch-size  50
     :pool-size   6
     :client      client
     :nemesis     nemesis
     :checker     checker
     :generator   gen}))

(defn MB29369-workload
  "Workload to trigger lost inserts due to cursor dropping bug MB29369"
  [opts]
  (let [addclient (cbclients/batch-insert-pool)
        delclient nil
        dcpclient (cbclients/dcp-client)
        
        ;; Around 100 Kops per node should be sufficient to trigger cursor dropping with
        ;; 100 MB per node bucket quota and ep_cursor_dropping_upper_mark reduced to 30%.
        ;; Since we need the first 2/3 of the ops to cause cursor dropping, we need 150 K
        ;; per node
        oplimit   (or (opts :oplimit)
                      (* (count (opts :nodes)) 150000))

        client   (clients/set-client addclient delclient dcpclient)
        nemesis  (cbnemesis/slow-dcp (:dcpclient client))
        checker  (checker/compose
                  (merge
                   {:set (checker/set)}
                   (if (opts :perf-graphs)
                     {:perf (checker/perf)})))

        gen      (gen/phases
                   ;; Make DCP slow and write 2/3 of the ops; oplimit should be chosen
                   ;; such that this is sufficient to trigger cursor dropping.
                   ;; ToDo: It would be better if we could monitor ep_cursors_dropped
                   ;;       to ensure that we really have dropped cursors.
                   (->> (range 0 (int (* 2/3 oplimit)))
                        (map (fn [x] {:type :invoke :f :add :value x}))
                        (gen/seq)
                        (gen/nemesis (gen/once {:type :info :f :start})))
                   ;; Make DCP fast again
                   (gen/nemesis (gen/once {:type :info :f :stop}))
                   ;; The race condition causing lost mutations occurs when we stop
                   ;; backfilling, so the dcp stream needs catch up. We pause
                   ;; sending new mutations until we have caught up with half the
                   ;; existing ones in order to speed this up
                   (gen/once
                     (fn []
                       (while (< (count @(:store dcpclient)) (* 1/3 oplimit))
                         (Thread/sleep 5))
                       nil))
                   ;; Write the remainder of the ops.
                   (->> (range (int (* 2/3 oplimit)) oplimit)
                        (map (fn [x] {:type :invoke :f :add :value x}))
                        (gen/seq)
                        (gen/clients))
                   (gen/once
                    (fn [] (info "DCP Mutations received thus far:" (count @(:store dcpclient))) nil))
                   ;; Wait for the cluster to settle before issuing final read
                   (gen/sleep 3)
                   (gen/clients (gen/once {:type :invoke :f :read :value :nil})))]
    {:custom-cursor-drop-marks [20 30]
     :replicas     0
     :replicate-to 0
     :concurrency  250
     :batch-size   20
     :pool-size    8
     :client       client
     :nemesis      nemesis
     :checker      checker
     :generator    gen}))

(defn MB29480-workload
  "Workload to trigger lost deletes due to cursor dropping bug MB29480"
  [opts]
  (let [addclient (cbclients/batch-insert-pool)
        delclient (cbclients/basic-client)
        dcpclient (cbclients/dcp-client)

        ;; Around 100 Kops per node should be sufficient to trigger cursor dropping with
        ;; 100 MB per node bucket quota and ep_cursor_dropping_upper_mark reduced to 30%.
        oplimit   (or (opts :oplimit)
                      (+ (* (count (opts :nodes)) 100000) 50000))
        client    (clients/set-client addclient delclient dcpclient)
        nemesis   (nemesis/compose {{:slow-dcp :start
                                     :fast-dcp :stop} (cbnemesis/slow-dcp dcpclient)
                                    #{:compact}       (cbnemesis/trigger-compaction)
                                    #{:bump}          (nt/clock-nemesis)})
        
        checker   (checker/compose
                   (merge
                    {:set (cbchecker/extended-set-checker)}
                    (if (opts :perf-graphs)
                      {:perf (checker/perf)})))

        gen       (gen/phases
                     ;; First create 10000 keys and let the client see them
                     (->> (range 0 10000)
                          (map (fn [x] {:type :invoke :f :add :value x}))
                          (gen/seq)
                          (gen/clients))
                     ;; Then slow down dcp and make sure the queue is filled
                     (->> (range 10000 50000)
                          (map (fn [x] {:type :invoke :f :add :value x}))
                          (gen/seq)
                          (gen/nemesis (gen/once {:type :info :f :slow-dcp})))
                     ;; Now delete the keys
                     (->> (range 0 50000)
                          (map (fn [x] {:type :invoke :f :del :value x}))
                          (gen/seq)
                          (gen/clients))
                     ;; Now trigger tombstone purging. We need to bump time by at least 3
                     ;; days to allow metadata to be purged
                     (gen/nemesis (gen/seq [{:type :info :f :bump
                                             :value (zipmap (opts :nodes) (repeat 260000000))}
                                            (gen/sleep 1)
                                            {:type :info :f :compact}]))
                     ;; Wait some time to allow compaction to run
                     (gen/sleep 20)
                     ;; Ensure cursor dropping by spamming inserts while dcp is still slow
                     (->> (range 50000 oplimit)
                          (map (fn [x] {:type :invoke :f :add :value x}))
                          (gen/seq)
                          (gen/clients))

                     ;; Final read
                     (gen/sleep 3)
                     (gen/clients (gen/once {:type :invoke :f :read :value nil})))]
    {:custom-cursor-drop-marks [20 30]
     :replicas     0
     :replicate-to 0
     :concurrency  250
     :batch-size   50
     :pool-size    4
     :client       client
     :nemesis      nemesis
     :checker      checker
     :generator    gen}))

(def workloads
  (array-map
    "Register"     Register-workload
    "Failover"     Failover-workload
    "MB30048"      MB30048-workload
    "MB28525"      MB28525-workload
    "Set"          Set-workload
    "WhiteRabbit"  WhiteRabbit-workload
    "MB29369"      MB29369-workload
    "MB29480"      MB29480-workload))