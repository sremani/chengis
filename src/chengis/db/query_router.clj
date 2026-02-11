(ns chengis.db.query-router
  "Routes database queries to primary or read replica.
   When a replica is configured, read-ds returns the replica datasource.
   When no replica, read-ds returns the primary (zero behavior change).
   write-ds always returns the primary datasource.")

(defrecord RoutedDatasource [primary replica])

(defn routed-datasource
  "Create a RoutedDatasource wrapping primary and optional replica."
  [primary replica]
  (->RoutedDatasource primary replica))

(defn read-ds
  "Get the read datasource. Returns replica if available, primary otherwise.
   Safe to call with any datasource (non-RoutedDatasource passes through)."
  [ds]
  (if (instance? RoutedDatasource ds)
    (or (:replica ds) (:primary ds))
    ds))

(defn write-ds
  "Get the write (primary) datasource.
   Safe to call with any datasource (non-RoutedDatasource passes through)."
  [ds]
  (if (instance? RoutedDatasource ds)
    (:primary ds)
    ds))

(defn has-replica?
  "Check if the datasource has a read replica configured."
  [ds]
  (and (instance? RoutedDatasource ds)
       (some? (:replica ds))))
