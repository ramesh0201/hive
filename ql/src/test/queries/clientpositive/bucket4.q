--! qt:dataset:src
set hive.explain.user=false;
set hive.input.format=org.apache.hadoop.hive.ql.io.HiveInputFormat;
;

set hive.exec.reducers.max = 1;
set hive.cbo.fallback.strategy=NEVER;

CREATE TABLE bucket4_1(key int, value string) CLUSTERED BY (key) SORTED BY (key) INTO 2 BUCKETS;

explain extended
insert overwrite table bucket4_1
select * from src;

insert overwrite table bucket4_1
select * from src;

explain
select * from bucket4_1 tablesample (bucket 1 out of 2) s;

select * from bucket4_1 tablesample (bucket 1 out of 2) s;
