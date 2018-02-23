package org.flinkanonymity.jobs;

import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.shaded.com.google.common.collect.Iterables;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.GlobalWindows;
import org.apache.flink.streaming.api.windowing.triggers.PurgingTrigger;
import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.state.ReducingState;
import org.apache.flink.api.common.state.ReducingStateDescriptor;
import org.apache.flink.util.Collector;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.api.common.typeutils.base.IntSerializer;
import org.apache.flink.api.common.typeutils.base.StringSerializer;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.windows.Window;
import org.apache.flink.streaming.api.windowing.windows.GlobalWindow;
import org.apache.flink.util.OutputTag;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
//import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction.Context;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.functions.ProcessFunction;

import org.flinkanonymity.datatypes.*;
import org.flinkanonymity.sources.AdultDataSource;

import java.util.HashMap;

public class KeyedJob {
    // Set up QID and Hashmap for global use.
    static QuasiIdentifier QID;
    static HashMap<String, Bucket> hashMap;
    static int k = 10;
    static int l = 5;
    static int p = 50;

    public static void main(String[] args) throws Exception {
        ParameterTool params = ParameterTool.fromArgs(args);
        // final String filePath = params.getRequired("input");

        // Set file paths
        String dataFilePath = "../sample-data/arx_adult/adult_sensitive.csv";
        String sex_hierarchy = "../sample-data/arx_adult/adult_hierarchy_sex.csv";
        String age_hierarchy = "../sample-data/arx_adult/adult_hierarchy_age.csv";
        String race_hierarchy = "../sample-data/arx_adult/adult_hierarchy_race.csv";
        String marst_hierarchy = "../sample-data/arx_adult/adult_hierarchy_marital-status.csv";
        String educ_hierarchy = "../sample-data/arx_adult/adult_hierarchy_education.csv";
        String country_hierarchy = "../sample-data/arx_adult/adult_hierarchy_native-country.csv";
        // String workclass_hierarchy = "../sample-data/arx_adult/adult_hierarchy_workclass.csv";
        // String occ_hierarchy = "../sample-data/arx_adult/adult_hierarchy_occupation.csv";
        // String salary_hierarchy = "../sample-data/arx_adult/adult_hierarchy_salary-class.csv";

        // Initialize generalizations
        Generalization age = new Generalization("age", age_hierarchy, 1);
        Generalization sex = new Generalization("sex", sex_hierarchy, 0);
        Generalization race = new Generalization("race", race_hierarchy, 0);
        Generalization educ = new Generalization("educ", educ_hierarchy,2);
        Generalization marst = new Generalization("marst", marst_hierarchy,0);
        //Generalization workclass = new Generalization("workclass", workclass_hierarchy,1);
        Generalization country = new Generalization("country", country_hierarchy,1);


        // Generalization race = new Generalization("race", educ_hierarchy,1);

        // Initialize QuasiIdentifier
        QID = new QuasiIdentifier(age, sex, race, educ, marst, country);
        hashMap = new HashMap<>();


        // Setting up Environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(p);
        env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime);

        DataStream<AdultData> data = env.addSource(new AdultDataSource(dataFilePath));

        // DataStreamSink<AdultData> output = new DataStreamSink<AdultData>();
        // output.setParallelism(1);

        // Generalize Quasi Identifiers
        DataStream<AdultData> genData = data.map(new Generalize());

        DataStream<Tuple2<AdultData, Long>> tsGenData = genData
                .keyBy(new QidKey())
                .process(new ProcessTimestamp());

        tsGenData.print();

/*
        KeyedStream<AdultData, String> keyedGenData = genData.keyBy(new KeySelector<AdultData, String>() {
            public String getKey(AdultData tuple) {
                String TupleQuasiString = tuple.QuasiToString(QID);
                return TupleQuasiString;
            }
        });
*/
/*
        DataStream<Integer> output = genData
                .keyBy(new KeySelector<AdultData, String>() {
                    public String getKey(AdultData tuple) {
                        String TupleQuasiString = tuple.QuasiToString(QID);
                        return TupleQuasiString;
                    }
                })
                .countWindow(k)
                .apply(new WindowFunction<AdultData, Integer, String, GlobalWindow>() {
                    public void apply (String key, GlobalWindow window, Iterable<AdultData> values, Collector<Integer> out) throws Exception {
                        int count = 0;
                        for (AdultData t: values) {
                            count += 1;
                        }
                        out.collect (new Integer(count));
                    }
                });
*/

        DataStream<AdultData> output = genData
                .keyBy(new QidKey())
                //.countWindow(k)
                .window(GlobalWindows.create())
                .trigger(PurgingTrigger.of(lDiversityTrigger.of(k, l)))
                .process(new Release());
                //.apply(new KAnonymize());

        output.print();

        //genData.print();
        env.execute();
    }

    public static class ProcessTimestamp extends ProcessFunction<AdultData, Tuple2<AdultData,Long>> {
        // Transfrom a DataStream of AdultData elements into a stream of AdultData elements with ingestion timestamp
        @Override
        public void processElement(AdultData value, Context ctx, Collector<Tuple2<AdultData,Long>> out) throws Exception {
            out.collect(new Tuple2<AdultData, Long>(value, ctx.timestamp()));
        }
    }


    public static class lDiversityTrigger<W extends Window> extends Trigger<Object, W> {

        private final int l, k;
        private String sensitive = "sensitive_class";


        private final MapStateDescriptor<String, Integer> stateMap =
                new MapStateDescriptor<>("map", StringSerializer.INSTANCE, IntSerializer.INSTANCE);

        private final ReducingStateDescriptor<Long> stateDesc =
                new ReducingStateDescriptor<>("count", new Sum(), LongSerializer.INSTANCE);


        private lDiversityTrigger(int k, int l) {
            // Constructor
            this.k = k;
            this.l = l;
        }

        public static <W extends Window> lDiversityTrigger<W> of(int k, int l) {
            return new lDiversityTrigger<>(k, l);
        }

        @Override
        public TriggerResult onElement(Object element, long timestamp, W window, TriggerContext ctx) throws Exception {
            // Object element is the tuple
            AdultData ad = (AdultData)element;
            // Get sensitive data which will serve as key
            String sensitiveData = ad.getAttribute(this.sensitive);
            // Get the MapState, which is a map with keys and counters.
            MapState<String, Integer> diversityMap = ctx.getPartitionedState(stateMap);
            ReducingState<Long> count = ctx.getPartitionedState(stateDesc);

            // Add 1 to the counter to keep track of number of elements in window.
            count.add(1L);

            // Add a tuple with the sensitiveData as key, in order to keep track of diversity.
            diversityMap.put(sensitiveData, 1);

            if (count.get() >= this.k){
                // If number of tuples > k (If k-anonymous)
                if(Iterables.size(diversityMap.keys()) >= this.l){
                    // If number of keys > l (If l-diverse)
                    diversityMap.clear();
                    count.clear();
                    return TriggerResult.FIRE;
                }
            }
            return TriggerResult.CONTINUE;
        }

        @Override
        public TriggerResult onEventTime(long time, W window, TriggerContext ctx) {
            return TriggerResult.CONTINUE;
        }

        @Override
        public TriggerResult onProcessingTime(long time, W window, TriggerContext ctx) throws Exception {
            return TriggerResult.CONTINUE;
        }

        @Override
        public void clear(W window, TriggerContext ctx) throws Exception {
            ctx.getPartitionedState(stateMap).clear();
        }
        private static class Sum implements ReduceFunction<Long> {
            private static final long serialVersionUID = 1L;

            @Override
            public Long reduce(Long value1, Long value2) throws Exception {
                return value1 + value2;
            }

        }

    }

    public static class Release extends ProcessWindowFunction<AdultData, AdultData, String, GlobalWindow> {
        @Override
        public void process(String key, Context context, Iterable<AdultData> elements, Collector<AdultData> out) throws Exception {
            System.out.println("Releasing bucket! " + key);
            for (AdultData t: elements) {
                out.collect (t);
            }
            System.out.println("Number of records: " + Iterables.size(elements));
            System.out.println("CurrentProcessingTime: " + context.currentProcessingTime());
            System.out.println("CurrentWindow: " + context.window());
        }
    }




    public static class QidKey implements KeySelector<AdultData, String> {
        @Override
        public String getKey(AdultData tuple) {
            String TupleQuasiString = tuple.QuasiToString(QID);
            return TupleQuasiString;
        }
    }

    public static class Generalize implements MapFunction<AdultData, AdultData>{
        @Override
        public AdultData map(AdultData adult) throws Exception{
            return QID.generalize(adult);
        }
    }

    public static class LDiversify implements FlatMapFunction<AdultData, AdultData>{
        @Override
        public void flatMap(AdultData tuple, Collector<AdultData> out) throws Exception {
            // get bucket
            String TupleQuasiString = tuple.QuasiToString(QID);
            if (!hashMap.containsKey(TupleQuasiString)) { // If this bucket has never been reached before
                hashMap.put(TupleQuasiString, new LBucket(TupleQuasiString)); // Create a bucket. Sensitive dataname could also be specified, default is sensitive_class.
            }
            LBucket lb = (LBucket) hashMap.get(TupleQuasiString); // Get the bucket
            lb.add(tuple);

            if (lb.isKAnonymous(k)) { // if bucket satisfies k-anonymity
                if (lb.isLDiverse(l)){
                    AdultData[] tuples = lb.dropBuffer(); // get tuples and drop bucket
                    System.out.println("Releasing bucket! " + tuples[0].QuasiToString(QID));
                    for (AdultData t : tuples) { // output tuples
                        out.collect(t);
                    }
                }
            }
        }
    }
}