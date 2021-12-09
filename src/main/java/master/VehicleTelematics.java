package master;


import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.EventTimeSessionWindows;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.Preconditions;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Java program using Flink for analysing mobility patterns of vehicles
 */
public class VehicleTelematics {

    // *************************************************************************
    // PROGRAM
    // *************************************************************************

    public static void main(String[] args) throws Exception {
        String input = args.length >= 1 ?  args[0] : null;
        String output = args.length >= 2 ?  args[1] : null;

        // Checking input parameters
        Preconditions.checkNotNull(input, "Input DataStream should not be null.");
        Preconditions.checkNotNull(output, "Output should not be null.");

        // set up the execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        WatermarkStrategy<Event> strategy = WatermarkStrategy
                .<Event>forMonotonousTimestamps()
                .withTimestampAssigner((event, timestamp) -> event.get("time")*1000);

        // get input data
        DataStreamSource<String> inputStream = env.readTextFile(input);

        SingleOutputStreamOperator<Event> events = inputStream
                .map(Event::new).name("toEvent")
                .assignTimestampsAndWatermarks(strategy);

        //SpeedRadar: detects cars that overcome the speed limit of 90 mph.
        SingleOutputStreamOperator<EventSpeedRadar> speedRadar = events
                .filter(event -> event.get("spd") > 90).name("filterSpeeding")
                .map(EventSpeedRadar::new).name("toEventSpeedRadar");

        //AverageSpeedControl: detects cars with an average speed higher than 60 mph between
        //segments 52 and 56 (both included) in both directions.
        SingleOutputStreamOperator<EventAverage> averageSpeedControl = events
                .filter(event -> event.get("seg") >= 52 &&  event.get("seg") <=56 ).name("filterSegments")
                .keyBy(Event::getKeyForAverage)
                .window(EventTimeSessionWindows.withGap(Time.seconds(31)))
                .process(new AverageProcess()).name("processAverageControl");

        //AccidentReporter: detects stopped vehicles on any segment.
        SingleOutputStreamOperator<EventAcccident> accidentReporter = events
                .filter(event -> event.get("spd") == 0 ).name("filterStopped")
                .keyBy(event -> event.get("vid"))
                .window(SlidingEventTimeWindows.of(Time.seconds(120), Time.seconds(30)))
                .process(new AccidentProcess()).name("processAccidents");

        // emit results
        speedRadar.writeAsCsv(Paths.get(output, "speedfines.csv").toString(), FileSystem.WriteMode.OVERWRITE)
                .setParallelism(1)
                .name("saveSpeedRadar");
        averageSpeedControl.writeAsCsv(Paths.get(output, "avgspeedfines.csv").toString(), FileSystem.WriteMode.OVERWRITE)
                .setParallelism(1)
                .name("saveAvgSpeed");
        accidentReporter.writeAsCsv(Paths.get(output, "accidents.csv").toString(), FileSystem.WriteMode.OVERWRITE)
                .setParallelism(1)
                .name("saveAccidents");

        // execute program
        env.execute("Streaming Vehicle Telematics");
    }

    public static class AverageProcess extends ProcessWindowFunction<Event, EventAverage, Tuple3<Integer, Integer, Integer>, TimeWindow> {

        @Override
        public void process(Tuple3<Integer, Integer, Integer> key,
                            ProcessWindowFunction<Event, EventAverage, Tuple3<Integer, Integer, Integer>, TimeWindow>.Context context,
                            Iterable<Event> iterable, Collector<EventAverage> collector) throws Exception {

            //We are interested in the first and the last events -> cover a longer distance
            Event first = iterable.iterator().next();
            Event last = null;
            for (Event event: iterable) {
                last = event;
            }
            //Complete segment for both directions
            if( (first.get("seg") == 56 && last.get("seg") == 52) ||
                (first.get("seg") == 52 && last.get("seg") == 56) ){
                EventAverage eventAverage = new EventAverage(first,last);
                if( eventAverage.getAvg() > 60){
                    collector.collect(eventAverage);
                }
            }
        }
    }

    public static class AccidentProcess extends ProcessWindowFunction<Event, EventAcccident, Integer, TimeWindow> {

        @Override
        public void process(Integer key,
                            ProcessWindowFunction<Event, EventAcccident, Integer,
                            TimeWindow>.Context context, Iterable<Event> iterable, Collector<EventAcccident> collector) throws Exception {

            //We are interested in the first and fourth events
            Event first = iterable.iterator().next();
            Event fourth = null;
            int count = 0;
            for (Event event: iterable) {
                count++;
                fourth = event;
            }
            //If the window is full - four elements within 90 seconds
            if(count == 4){
                if(fourth.get("time") - first.get("time") == 90){
                    //Send an alert - the events are ordered
                    collector.collect(new EventAcccident(first, fourth));
                }else{
                    List<Event> events = new ArrayList<>(0);
                    for (Event e: iterable) events.add(e);
                    events.sort((a,b) -> a.get("time") - b.get("time") );
                    //Send an alert - the events were ordered first
                    collector.collect(new EventAcccident(events.get(0), events.get(3)));
                }
            }
        }
    }
}