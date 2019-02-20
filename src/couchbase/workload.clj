(ns couchbase.workload
  (:require [clojure.tools.logging :refer :all]
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
            [knossos.model :as model]))

;; =============
;; Helper macros
;; =============

(defmacro let-and-merge
  "Take pairs of parameter-name parameter-value and merge these into a map. Bind
  all previous parameter names to their value to allow parameters values
  dependent on previous values."
  ([] {})
  ([param value & more] `(merge {(keyword (name '~param)) ~value}
                                (let [~param ~value]
                                  (let-and-merge ~@more)))))

(defmacro with-register-base
  "Apply a set of shared parameters used across the register workloads before
  merging the custom parameters"
  ([opts & more]
   `(let-and-merge
        ~'oplimit               (or (~opts :oplimit)     1200)
        ~'rate                  (or (~opts :rate)        1/3)
        ~'doc-count             (or (~opts :doc-count)   40)
        ~'doc-threads           (or (~opts :doc-threads) 3)
        ~'concurrency           (* ~'doc-count ~'doc-threads)
        ~'autofailover-timeout  (or (~opts :autofailover-timeout)  6)
        ~'autofailover-maxcount (or (~opts :autofailover-maxcount) 3)
        ~'client                (clients/register-client (cbclients/basic-client))
        ~'model                 (model/cas-register :nil)
        ~'checker   (checker/compose
                     (merge
                      {:indep (independent/checker
                               (checker/compose
                                {:timeline (timeline/html)
                                 :linear (checker/linearizable)}))}
                      (if (~opts :perf-graphs)
                        {:perf (checker/perf)})))
        ~@more)))

;; ==================
;; Register workloads
;; ==================

(defn Register-workload
  "Basic register workload"
  [opts]
  (with-register-base opts
    replicas      (or (opts :replicas) 1)
    replicate-to  (or (opts :replicate-to) 0)
    nemesis       nemesis/noop
    generator     (->> (independent/concurrent-generator doc-threads (range)
                         (fn [k]
                           (->> (gen/mix [(fn [_ _] {:type :invoke :f :read  :value nil})
                                          (fn [_ _] {:type :invoke :f :write :value (rand-int 5)})
                                          (fn [_ _] {:type :invoke :f :cas   :value [(rand-int 5) (rand-int 5)]})])
                                (gen/stagger (/ rate)))))
                       (gen/limit oplimit)
                       (gen/clients))))

(defn Partition-workload
  "Paritions the network by isolating nodes from each other, then will recover if autofailover happens"
  [opts]
  (with-register-base opts
    cycles         (or (opts :cycles) 1)
    replicas       (or (opts :replicas) 1)
    replicate-to   (or (opts :replicate-to) 0)
    autofailover   (if (nil? (opts :autofailover)) false (opts :autofailover))
    disrupt-time   (or (opts :disrupt-time) 20)
    recovery-type  (or (keyword (opts :recovery-type)) :delta)
    disrupt-count  (or (opts :disrupt-count) 1)
    should-autofailover (and autofailover (= disrupt-count 1) (> disrupt-time autofailover-timeout) (>= (count (:nodes opts)) 3))
    nemesis        (cbnemesis/couchbase)
    generator      (->> (independent/concurrent-generator doc-threads (range)
                                                          (fn [k]
                                                            (->> (gen/mix [(fn [_ _] {:type :invoke :f :read  :value nil})
                                                                           (fn [_ _] {:type :invoke :f :write :value (rand-int 50)})])
                                                                 (gen/stagger (/ rate)))))
                        (gen/nemesis (gen/seq
                                       (reduce into [] (repeat cycles (if should-autofailover
                                                                        [(gen/sleep 5)
                                                                         {:type :info :f :partition-network
                                                                          :f-opts
                                                                                {:partition-type :isolate-completely}
                                                                          :targeter-opts
                                                                                {:type :random-subset
                                                                                 :count disrupt-count
                                                                                 :condition [[:active :connected]]}}
                                                                         {:type :info :f :wait-for-autofailover
                                                                          :targeter-opts
                                                                                {:type :random
                                                                                 :condition [[:active :connected]]}}
                                                                         (gen/sleep (- disrupt-time autofailover-timeout))
                                                                         {:type :info :f :heal-network}
                                                                         (gen/sleep 5)
                                                                         {:type :info :f :recover
                                                                          :f-opts
                                                                                {:recovery-type recovery-type}
                                                                          :targeter-opts
                                                                                {:type :all
                                                                                 :condition [[:failed :connected]]}}
                                                                         (gen/sleep 5)]

                                                                        [(gen/sleep 5)
                                                                         {:type :info :f :partition-network
                                                                          :f-opts
                                                                                {:partition-type :isolate-completely}
                                                                          :targeter-opts
                                                                                {:type :random-subset
                                                                                 :count disrupt-count
                                                                                 :condition [[:active :connected]]}}
                                                                         (gen/sleep disrupt-time)
                                                                         {:type :info :f :heal-network}
                                                                         (gen/sleep 5)]
                                                                        )
                                                               ))))
                        (gen/limit oplimit))))

(defn MB28525-workload
  "Trigger non-linearizable behaviour where successful mutations with replicate-to=1
  are lost due to promotion of the 'wrong' replica upon failover of the active"
  [opts]
  (with-register-base opts
    replicas     (or (opts :replicas) 2)
    replicate-to (or (opts :replicate-to) 1)
    autofailover (if (nil? (opts :autofailover)) false (opts :autofailover))
    nemesis      (cbnemesis/partition-then-failover)
    generator    (->> (independent/concurrent-generator doc-threads (range)
                       (fn [k]
                         (->> (gen/mix [(fn [_ _] {:type :invoke, :f :read, :value nil})
                                        (fn [_ _] {:type :invoke, :f :write :value (rand-int 50)})])
                              (gen/stagger (/ rate)))))
                     (gen/limit oplimit)
                     (gen/nemesis (gen/seq [(gen/sleep 10)
                                            {:type :info :f :start-partition}
                                            (gen/sleep 5)
                                            {:type :info :f :start-failover}
                                            (gen/sleep 30)])))))

(defn Sequential-Rebalance-workload
  "Rebalance a nodes out and back into the cluster sequentially"
  [opts]
  (with-register-base opts
    replicas      (or (opts :replicas)      1)
    replicate-to  (or (opts :replicate-to)  0)
    disrupt-count (or (opts :disrupt-count) 1)
    autofailover  (if (nil? (opts :autofailover)) false (opts :autofailover))
    nemesis       (cbnemesis/rebalance-out-in)
    generator     (->> (independent/concurrent-generator doc-threads (range)
                         (fn [k]
                           (->> (gen/mix [(fn [_ _] {:type :invoke :f :read  :value nil})
                                          (fn [_ _] {:type :invoke :f :write :value (rand-int 50)})])
                                (gen/stagger (/ rate)))))
                       (gen/nemesis (gen/seq (cycle (flatten [(repeatedly disrupt-count
                                                                          #(do [(gen/sleep 5)
                                                                                {:type :info :f :rebalance-out}]))
                                                              (gen/sleep 10)
                                                              (repeatedly disrupt-count
                                                                          #(do [(gen/sleep 5)
                                                                                {:type :info :f :rebalance-in}]))
                                                              (gen/sleep 10)]))))
                       (gen/limit oplimit))))

(defn Bulk-Rebalance-workload
  "Rebalance multiple nodes out and back into the cluster simultaneously"
  [opts]
  (with-register-base opts
    replicas      (or (opts :replicas)      1)
    replicate-to  (or (opts :replicate-to)  0)
    disrupt-count (or (opts :disrupt-count) 1)
    autofailover  (if (nil? (opts :autofailover)) false (opts :autofailover))
    nemesis       (cbnemesis/rebalance-out-in)
    generator     (->> (independent/concurrent-generator doc-threads (range)
                         (fn [k]
                           (->> (gen/mix [(fn [_ _] {:type :invoke :f :read  :value nil})
                                          (fn [_ _] {:type :invoke :f :write :value (rand-int 50)})])
                                (gen/stagger (/ rate)))))
                       (gen/nemesis (gen/seq (cycle [(gen/sleep 5)
                                                     {:type :info :f :rebalance-out :count disrupt-count}
                                                     (gen/sleep 15)
                                                     {:type :info :f :rebalance-in  :count disrupt-count}
                                                     (gen/sleep 10)])))
                       (gen/limit oplimit))))


(defn Swap-Rebalance-workload
  "Swap rebalance nodes within a cluster"
  [opts]
  (with-register-base opts
    replicas      (or (opts :replicas)      1)
    replicate-to  (or (opts :replicate-to)  0)
    disrupt-count (or (opts :disrupt-count) 1)
    autofailover  (if (nil? (opts :autofailover)) false (opts :autofailover))
    nemesis       (cbnemesis/swap-rebalance disrupt-count)
    generator     (->> (independent/concurrent-generator doc-threads (range)
                         (fn [k]
                           (->> (gen/mix [(fn [_ _] {:type :invoke :f :read  :value nil})
                                          (fn [_ _] {:type :invoke :f :write :value (rand-int 50)})])
                                (gen/stagger (/ rate)))))
                       (gen/nemesis (gen/seq (cycle [(gen/sleep 5)
                                                     {:type :info :f :swap}
                                                     (gen/sleep 5)])))
                       (gen/limit oplimit))))

(defn Fail-Rebalance-workload
  "Kill memcached during a rebalance"
  [opts]
  (with-register-base opts
    replicas      (or (opts :replicas)      1)
    replicate-to  (or (opts :replicate-to)  0)
    disrupt-count (or (opts :disrupt-count) 1)
    autofailover  (if (nil? (opts :autofailover)) false (opts :autofailover))
    nemesis       (cbnemesis/fail-rebalance)
    generator     (->> (independent/concurrent-generator doc-threads (range)
                         (fn [k]
                           (->> (gen/mix [(fn [_ _] {:type :invoke :f :read  :value nil})
                                          (fn [_ _] {:type :invoke :f :write :value (rand-int 50)})])
                                (gen/stagger (/ rate)))))
                       (gen/nemesis (gen/seq (cycle [(gen/sleep 5)
                                                     {:type :info :f :start :count disrupt-count}
                                                     (gen/sleep 5)])))
                       (gen/limit oplimit))))

(defn Failover-workload
  "Failover and recover"
  [opts]
  (with-register-base opts
    cycles         (or (opts :cycles) 1)
    replicas     (or (opts :replicas)     1)
    replicate-to (or (opts :replicate-to) 0)
    recovery-type (or (keyword (opts :recovery-type)) :delta)
    failover-type (or (keyword (opts :failover-type)) :hard)
    disrupt-count (or (opts :disrupt-count) 1)
    nemesis      (cbnemesis/couchbase)
    generator    (->> (independent/concurrent-generator doc-threads (range)
                        (fn [k]
                          (->> (gen/mix [(fn [_ _] {:type :invoke :f :read  :value nil})
                                         (fn [_ _] {:type :invoke :f :write :value (rand-int 50)})])
                               (gen/stagger (/ rate)))))
                      (gen/nemesis (gen/seq
                                     (reduce into []
                                             (repeat cycles [(gen/sleep 5)
                                                             {:type :info :f :failover
                                                              :f-opts
                                                                    {:failover-type failover-type}
                                                              :targeter-opts
                                                                    {:type :random-subset
                                                                     :count disrupt-count
                                                                     :condition [[:active :connected]]}}
                                                             (gen/sleep 10)
                                                             {:type :info :f :recover
                                                              :f-opts
                                                                    {:recovery-type recovery-type}
                                                              :targeter-opts
                                                                    {:type :all
                                                                     :condition [[:failed :connected]]}}
                                                             (gen/sleep 5)]))))
                      (gen/limit oplimit))))

(defn Graceful-Failover-workload
  "Gracefully failover and recover random nodes"
  [opts]
  (with-register-base opts
    replicas     (or (opts :replicas)     1)
    replicate-to (or (opts :replicate-to) 0)
    autofailover (if (nil? (opts :autofailover)) false (opts :autofailover))
    recovery     (or (opts :recovery-type) :delta)
    nemesis      (cbnemesis/graceful-failover rand-nth recovery)
    generator    (->> (independent/concurrent-generator doc-threads (range)
                        (fn [k]
                          (->> (gen/mix [(fn [_ _] {:type :invoke :f :read  :value nil})
                                         (fn [_ _] {:type :invoke :f :write :value (rand-int 50)})])
                               (gen/stagger (/ rate)))))
                      (gen/nemesis (gen/seq (cycle [(gen/sleep 5)
                                                    {:type :info :f :start}
                                                    (gen/sleep 10)
                                                    {:type :info :f :stop}
                                                    (gen/sleep 5)])))
                      (gen/limit oplimit))))

(defn Disk-Failure-workload
  "Simulate a disk failure. This workload will not function correctly with docker containers."
  [opts]
  (with-register-base opts
    replicas      (or (opts :replicas)      2)
    replicate-to  (or (opts :replicate-to)  0)
    disrupt-count (or (opts :disrupt-count) 1)
    autofailover  (if (nil? (opts :autofailover)) false (opts :autofailover))
    recovery      (or (opts :recovery) :delta)
    nemesis       (cbnemesis/disk-failure)
    generator     (->> (independent/concurrent-generator doc-threads (range)
                         (fn [k]
                           (->> (gen/mix [(fn [_ _] {:type :invoke :f :read  :value nil})
                                          (fn [_ _] {:type :invoke :f :write :value (rand-int 50)})])
                                (gen/limit 20)
                                (gen/stagger (/ rate)))))
                       (gen/nemesis (gen/seq [(gen/sleep 15)
                                              {:type :info :f :start :count disrupt-count}]))
                       (gen/limit oplimit))))

;; =============
;; Set Workloads
;; =============

(defn Set-workload
  "Generic set workload. We model the set with a bucket, adding an item to the
  set corresponds to inserting a key. To read the set we use a dcp client to
  stream all mutations, keeping track of which keys exist"
  [opts]
  (let-and-merge
      addclient     (cbclients/batch-insert-pool)
      delclient     nil
      dcpclient     (cbclients/simple-dcp-client)

      oplimit       (or (opts :oplimit)      50000)
      client        (clients/set-client addclient delclient dcpclient)
      concurrency   250
      batch-size    50
      pool-size     4
      replicas      (or (opts :replicas) 0)
      replicate-to  (or (opts :replicate-to) 0)
      autofailover  (if (nil? (opts :autofailover)) true (opts :autofailover))
      autofailover-timeout  (or (opts :autofailover-timeout)  6)
      autofailover-maxcount (or (opts :autofailover-maxcount) 3)

      checker       (checker/compose
                      (merge
                        {:set (checker/set)}
                        (if (opts :perf-graphs)
                          {:perf (checker/perf)})))
      generator     (gen/phases
                       (->> (range)
                            (map (fn [x] {:type :invoke :f :add :value x}))
                            (gen/seq)
                            (gen/clients)
                            (gen/limit oplimit))
                       (gen/sleep 3)
                       (gen/clients (gen/once {:type :invoke :f :read :value nil})))))

(defn Set-kill-memcached-workload
  "Set workload that repeatedly kills memcached while hammering inserts against
  the cluster"
  [opts]
  (let-and-merge
      addclient             (cbclients/batch-insert-pool)
      delclient             nil
      dcpclient             (cbclients/simple-dcp-client)
      oplimit               (or (opts :oplimit) 250000)
      concurrency           500
      batch-size            50
      pool-size             6
      custom-vbucket-count  64
      replicas              (or (opts :replicas) 1)
      replicate-to          (or (opts :replicate-to) 0)
      disrupt-count         (or (opts :disrupt-count) 1)
      autofailover          (if (nil? (opts :autofailover)) false (opts :autofailover))
      autofailover-timeout  (or (opts :autofailover-timeout)  6)
      autofailover-maxcount (or (opts :autofailover-maxcount) 3)
      client                (clients/set-client addclient delclient dcpclient)

      nemesis               (cbnemesis/kill-memcached)
      checker               (checker/compose
                             (merge
                              {:set (checker/set)}
                              (if (opts :perf-graphs)
                                {:perf (checker/perf)})))
      generator             (gen/phases
                             (->> (range)
                                  (map (fn [x] {:type :invoke :f :add :value x}))
                                  (gen/seq)
                                  (gen/nemesis (gen/seq (cycle [(gen/sleep 10)
                                                                {:type :info :f :kill :count disrupt-count}
                                                                (gen/sleep 20)])))
                                  (gen/limit oplimit))
                             (gen/clients (gen/once {:type :invoke :f :read :value nil})))))

(defn Set-kill-ns_server-workload
  "Set workload that repeatedly kills ns_server while hammering inserts against
  the cluster"
  [opts]
  (let-and-merge
      addclient             (cbclients/batch-insert-pool)
      delclient             nil
      dcpclient             (cbclients/simple-dcp-client)
      oplimit               (or (opts :oplimit) 250000)
      concurrency           500
      batch-size            50
      pool-size             6
      custom-vbucket-count  64
      replicas              (or (opts :replicas) 1)
      replicate-to          (or (opts :replicate-to) 0)
      disrupt-count         (or (opts :disrupt-count) 1)
      autofailover          (if (nil? (opts :autofailover)) false (opts :autofailover))
      autofailover-timeout  (or (opts :autofailover-timeout)  6)
      autofailover-maxcount (or (opts :autofailover-maxcount) 3)
      client                (clients/set-client addclient delclient dcpclient)

      nemesis               (cbnemesis/kill-ns_server)
      checker               (checker/compose
                             (merge
                              {:set (checker/set)}
                              (if (opts :perf-graphs)
                                {:perf (checker/perf)})))
      generator             (gen/phases
                             (->> (range)
                                  (map (fn [x] {:type :invoke :f :add :value x}))
                                  (gen/seq)
                                  (gen/nemesis (gen/seq (cycle [(gen/sleep 10)
                                                                {:type :info :f :kill :count disrupt-count}
                                                                (gen/sleep 20)])))
                                  (gen/limit oplimit))
                             (gen/clients (gen/once {:type :invoke :f :read :value nil})))))

(defn Set-kill-babysitter-workload
  "Set workload that repeatedly kills ns_server while hammering inserts against
  the cluster"
  [opts]
  (let-and-merge
      addclient             (cbclients/batch-insert-pool)
      delclient             nil
      dcpclient             (cbclients/simple-dcp-client)
      oplimit               (or (opts :oplimit) 250000)
      concurrency           1000
      batch-size            50
      pool-size             16
      replicas              (or (opts :replicas)      1)
      replicate-to          (or (opts :replicate-to)  0)
      recovery-type         (or (opts :recovery-type) :delta)
      disrupt-count         (or (opts :disrupt-count) 1)
      disrupt-time          (or (opts :disrupt-time)  30)
      autofailover          (if (nil? (opts :autofailover)) false (opts :autofailover))
      autofailover-timeout  (or (opts :autofailover-timeout)  6)
      autofailover-maxcount (or (opts :autofailover-maxcount) 3)
      client                (clients/set-client addclient delclient dcpclient)

      nemesis               (cbnemesis/kill-babysitter)
      checker               (checker/compose
                             (merge
                              {:set (checker/set)}
                              (if (opts :perf-graphs)
                                {:perf (checker/perf)})))
      generator             (gen/phases
                             (->> (range)
                                  (map (fn [x] {:type :invoke :f :add :value x}))
                                  (gen/seq)
                                  (gen/nemesis
                                  (gen/seq (cycle [(gen/sleep 10)
                                                   {:type :info :f :kill :count disrupt-count}
                                                   (gen/sleep disrupt-time)
                                                   {:type :info :f :restart}
                                                   (gen/sleep 15)
                                                   {:type :info :f :recover}
                                                   (gen/sleep 10)])))
                                  (gen/limit oplimit))
                             (gen/clients (gen/once {:type :invoke :f :read :value nil})))))

(defn WhiteRabbit-workload
  "Trigger lost inserts due to one of several white-rabbit variants"
  [opts]
  (let-and-merge
      addclient (cbclients/batch-insert-pool)
      delclient nil
      dcpclient (cbclients/simple-dcp-client)
      oplimit               (or (opts :oplimit) 2500000)
      concurrency           500
      batch-size            50
      pool-size             6
      custom-vbucket-count  64
      replicas              (or (opts :replicas) 0)
      replicate-to          (or (opts :replicate-to) 0)
      autofailover          (if (nil? (opts :autofailover)) true (opts :autofailover))
      autofailover-timeout  (or (opts :autofailover-timeout)  6)
      autofailover-maxcount (or (opts :autofailover-maxcount) 3)
      client                (clients/set-client addclient delclient dcpclient)

      nemesis               (cbnemesis/rebalance-out-in)
      checker               (checker/compose
                             (merge
                              {:set (checker/set)}
                              (if (opts :perf-graphs)
                                {:perf (checker/perf)})))
      generator             (gen/phases
                             (->> (range)
                                  (map (fn [x] {:type :invoke :f :add :value x}))
                                  (gen/seq)
                                  (gen/nemesis (gen/seq (cycle [(gen/sleep 10)
                                                                {:type :info :f :rebalance-out}
                                                                (gen/sleep 10)
                                                                {:type :info :f :rebalance-in}])))
                                  (gen/limit oplimit))
                             (gen/nemesis (gen/once {:type :info :f :stop}))
                             (gen/clients (gen/once {:type :invoke :f :read :value nil})))))

(defn MB29369-workload
  "Workload to trigger lost inserts due to cursor dropping bug MB29369"
  [opts]
  (let-and-merge
      addclient (cbclients/batch-insert-pool)
      delclient nil
      dcpclient (cbclients/dcp-client)
      ;; Around 100 Kops per node should be sufficient to trigger cursor dropping with
      ;; 100 MB per node bucket quota and ep_cursor_dropping_upper_mark reduced to 30%.
      ;; Since we need the first 2/3 of the ops to cause cursor dropping, we need 150 K
      ;; per node
      oplimit       (or (opts :oplimit)
                        (* (count (opts :nodes)) 150000))
      custom-cursor-drop-marks [20 30]
      concurrency   250
      batch-size    20
      pool-size     8
      replicas      (or (opts :replicas) 0)
      replicate-to  (or (opts :replicate-to) 0)
      autofailover  (if (nil? (opts :autofailover)) true (opts :autofailover))
      autofailover-timeout     (or (opts :autofailover-timeout)  6)
      autofailover-maxcount    (or (opts :autofailover-maxcount) 3)
      client        (clients/set-client addclient delclient dcpclient)
      nemesis       (cbnemesis/slow-dcp (:dcpclient client))
      checker       (checker/compose
                     (merge
                      {:set (checker/set)}
                      (if (opts :perf-graphs)
                        {:perf (checker/perf)})))

      generator     (gen/phases
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
                     (gen/clients (gen/once {:type :invoke :f :read :value :nil})))))

(defn MB29480-workload
  "Workload to trigger lost deletes due to cursor dropping bug MB29480"
  [opts]
  (let-and-merge
      addclient     (cbclients/batch-insert-pool)
      delclient     (cbclients/basic-client)
      dcpclient     (cbclients/dcp-client)

      ;; Around 100 Kops per node should be sufficient to trigger cursor dropping with
      ;; 100 MB per node bucket quota and ep_cursor_dropping_upper_mark reduced to 30%.
      oplimit       (or (opts :oplimit)
                        (+ (* (count (opts :nodes)) 100000) 50000))
      custom-cursor-drop-marks [20 30]
      concurrency   250
      batch-size    50
      pool-size     4
      replicas      (or (opts :replicas) 0)
      replicate-to  (or (opts :replicate-to) 0)
      autofailover  (if (nil? (opts :autofailover)) true (opts :autofailover))
      autofailover-timeout     (or (opts :autofailover-timeout)  6)
      autofailover-maxcount    (or (opts :autofailover-maxcount) 3)
      client        (clients/set-client addclient delclient dcpclient)
      nemesis       (nemesis/compose {{:slow-dcp :start
                                       :fast-dcp :stop} (cbnemesis/slow-dcp dcpclient)
                                      #{:compact}       (cbnemesis/trigger-compaction)
                                      #{:bump}          (nt/clock-nemesis)})
      checker       (checker/compose
                     (merge
                      {:set (cbchecker/extended-set-checker)}
                      (if (opts :perf-graphs)
                        {:perf (checker/perf)})))

      generator     (gen/phases
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
                     (gen/clients (gen/once {:type :invoke :f :read :value nil})))))
