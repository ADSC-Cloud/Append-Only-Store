package indexingTopology.bolt;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import com.google.common.util.concurrent.AtomicDouble;
import indexingTopology.config.TopologyConfig;
import indexingTopology.NormalDistributionIndexingAndRangeQueryTopology;
import indexingTopology.NormalDistributionIndexingTopology;
import indexingTopology.metadata.FilePartitionSchemaManager;
import indexingTopology.metadata.FileMetaData;
import indexingTopology.streams.Streams;
import indexingTopology.util.BalancedPartition;
import indexingTopology.util.FileScanMetrics;
import indexingTopology.util.RangeQuerySubQuery;
import javafx.util.Pair;

import java.io.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * Created by acelzj on 11/15/16.
 */
public class RangeQueryDeCompositionBolt extends BaseRichBolt {

    private OutputCollector collector;

    private Thread QueryThread;

    private FilePartitionSchemaManager filePartitionSchemaManager;

    private File file;

    private BufferedReader bufferedReader;

    private Semaphore newQueryRequest;

    private static final int MAX_NUMBER_OF_CONCURRENT_QUERIES = 5;

    private long queryId;

    private transient List<Integer> queryServers;

    private List<Integer> indexServers;

    private transient Map<Integer, Long> indexTaskToTimestampMapping;

    private ArrayBlockingQueue<RangeQuerySubQuery> taskQueue;

    private transient Map<Integer, ArrayBlockingQueue<RangeQuerySubQuery>> taskIdToTaskQueue;

    private BalancedPartition balancedPartition;

    private int numberOfPartitions;

    private Double lowerBound;
    private Double upperBound;

    private AtomicDouble minIndexValue;
    private AtomicDouble maxIndexValue;

    private BufferedWriter bufferedWriter;

    public RangeQueryDeCompositionBolt(Double lowerBound, Double upperBound) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        collector = outputCollector;
//        file = new File("/home/acelzj/IndexTopology_experiment/NormalDistribution/input_data");
//
//        try {
//            File file = new File("/home/acelzj/IndexTopology_experiment/NormalDistribution/shuffle_grouping");
//
//            if (!file.exists()) {
//                file.createNewFile();
//            }

//            FileWriter fileWriter = new FileWriter(file,true);
//
//            bufferedWriter = new BufferedWriter(fileWriter);
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        } catch (IOException e) {
//            e.printStackTrace();
//        }

        newQueryRequest = new Semaphore(MAX_NUMBER_OF_CONCURRENT_QUERIES);
        queryId = 0;
        filePartitionSchemaManager = new FilePartitionSchemaManager();

        taskQueue = new ArrayBlockingQueue<RangeQuerySubQuery>(TopologyConfig.TASK_QUEUE_CAPACITY);
//        taskQueue = new LinkedBlockingQueue<RangeQuerySubQuery>(TopologyConfig.FILE_QUERY_TASK_WATINING_QUEUE_CAPACITY);

        Set<String> componentIds = topologyContext.getThisTargets().get(Streams.FileSystemQueryStream).keySet();

        taskIdToTaskQueue = new HashMap<Integer, ArrayBlockingQueue<RangeQuerySubQuery>>();

        queryServers = new ArrayList<Integer>();

        for (String componentId : componentIds) {
            queryServers.addAll(topologyContext.getComponentTasks(componentId));
        }

        createTaskQueues(queryServers);
        
        componentIds = topologyContext.getThisTargets().get(Streams.BPlusTreeQueryStream).keySet();

        indexServers = new ArrayList<Integer>();
        for (String componentId : componentIds) {
            indexServers.addAll(topologyContext.getComponentTasks(componentId));
        }


        minIndexValue = new AtomicDouble(2000);
        maxIndexValue = new AtomicDouble(0);


//        try {
//            bufferedReader = new BufferedReader(new FileReader(file));
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        } catch (IOException e) {
//            e.printStackTrace();
//        }

        setTimestamps();

        numberOfPartitions = queryServers.size();
        balancedPartition = new BalancedPartition(numberOfPartitions, lowerBound, upperBound);

        QueryThread = new Thread(new QueryRunnable());
        QueryThread.start();
    }

    public void execute(Tuple tuple) {
        if (tuple.getSourceStreamId().equals(Streams.FileInformationUpdateStream)) {
            String fileName = tuple.getString(0);
            Pair keyRange = (Pair) tuple.getValue(1);
            Pair timeStampRange = (Pair) tuple.getValue(2);

            filePartitionSchemaManager.add(new FileMetaData(fileName, (Double) keyRange.getKey(),
                    (Double)keyRange.getValue(), (Long) timeStampRange.getKey(), (Long) timeStampRange.getValue()));
        } else if (tuple.getSourceStreamId().equals(Streams.NewQueryStream)) {
//            Long queryId = tuple.getLong(0);
//            Long timeCostInMillis = System.currentTimeMillis() - queryIdToTimeCostInMillis.get(queryId);

//            FileScanMetrics metrics = (FileScanMetrics) tuple.getValue(2);

//            int numberOfFilesToScan = tuple.getInteger(3);

//            try {
//                writeToFile(metrics, numberOfFilesToScan);
//            } catch (IOException e) {
//                e.printStackTrace();
//            }

            newQueryRequest.release();
        } else if (tuple.getSourceStreamId().equals(Streams.FileSubQueryFinishStream)) {

            int taskId = tuple.getSourceTask();

            /*task queue model
            sendSubqueryToTask(taskId);
            */

//            /*our method
            sendSubquery(taskId);
//            */

        } else if (tuple.getSourceStreamId().equals(Streams.QueryGenerateStream)) {

            try {
                newQueryRequest.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            Long queryId = tuple.getLong(0);
            Double leftKey = tuple.getDouble(1);
            Double rightKey = tuple.getDouble(2);
            Long startTimeStamp = tuple.getLong(3);
            Long endTimeStamp = tuple.getLong(4);

            List<String> fileNames = filePartitionSchemaManager.search(leftKey, rightKey, startTimeStamp, endTimeStamp);

            collector.emit(Streams.BPlusTreeQueryStream,
                    new Values(queryId, leftKey, rightKey, startTimeStamp, endTimeStamp));

            int numberOfFilesToScan = fileNames.size();
            collector.emit(Streams.FileSystemQueryInformationStream, new Values(queryId, numberOfFilesToScan));


            for (String fileName : fileNames) {
                RangeQuerySubQuery subQuery = new RangeQuerySubQuery(queryId, leftKey, rightKey, fileName, startTimeStamp, endTimeStamp);
                try {
                    taskQueue.put(subQuery);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            for (Integer taskId : queryServers) {
                RangeQuerySubQuery subQuery = taskQueue.poll();
                if (subQuery != null) {
                    collector.emitDirect(taskId, Streams.FileSystemQueryStream
                            , new Values(subQuery));
                }
            }

        } else if (tuple.getSourceStreamId().equals(NormalDistributionIndexingAndRangeQueryTopology.TimeStampUpdateStream)) {
            int taskId = tuple.getIntegerByField("taskId");
            Long timestamp = tuple.getLongByField("timestamp");

            indexTaskToTimestampMapping.put(taskId, timestamp);
        } else if (tuple.getSourceStreamId().equals(NormalDistributionIndexingAndRangeQueryTopology.IntervalPartitionUpdateStream)) {
            Map<Integer, Integer> intervalToPartitionMapping = (Map) tuple.getValueByField("newIntervalPartition");
            balancedPartition.setIntervalToPartitionMapping(intervalToPartitionMapping);
        }
    }

    private void writeToFile(FileScanMetrics metrics, int numberOfFilesToScan) throws IOException {
        Long totalTime = metrics.getTotalTime();
        Long searchTime = metrics.getSearchTime();
        Long fileReadTime = metrics.getFileReadingTime();
        Long leafDeserializationTime = metrics.getLeafDeserializationTime();
        Long treeDeserializationTime = metrics.getTreeDeserializationTime();

        String text = searchTime + " " + fileReadTime + " " + leafDeserializationTime + " "
                + treeDeserializationTime + " " + numberOfFilesToScan + " " + totalTime;

        System.out.println(text);

        bufferedWriter.write(text);

        bufferedWriter.newLine();

        bufferedWriter.flush();
    }

    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
//        outputFieldsDeclarer.declare(new Fields("key"));
//        outputFieldsDeclarer.declareStream(NormalDistributionIndexingAndRangeQueryTopology.FileSystemQueryStream,
//                new Fields("queryId", "leftKey", "rightKey", "fileName", "startTimeStamp", "endTimeStamp"));

        outputFieldsDeclarer.declareStream(Streams.FileSystemQueryStream,
                new Fields("subQuery"));

        outputFieldsDeclarer.declareStream(Streams.BPlusTreeQueryStream,
                new Fields("queryId", "leftKey", "rightKey"));

        outputFieldsDeclarer.declareStream(
                Streams.FileSystemQueryInformationStream,
                new Fields("queryId", "numberOfFilesToScan"));

        outputFieldsDeclarer.declareStream(
                Streams.BPlusTreeQueryInformationStream,
                new Fields("queryId", "numberOfTasksToScan"));
    }



    class QueryRunnable implements Runnable {

        public void run() {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                try {
                    newQueryRequest.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

//                String text = null;
//                try {
//                    text = bufferedReader.readLine();
//                    if (text == null) {
//                        bufferedReader.close();
//                        bufferedReader = new BufferedReader(new FileReader(file));
//                        text = bufferedReader.readLine();
//                    }
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }


//                String [] tuple = text.split(" ");
//
                Double leftKey = 994.0;
                Double rightKey = 994.0;
                Double min = minIndexValue.get();
                Double max = maxIndexValue.get();

                System.out.println("new query");

//                while (min > max) {
//                    min = minIndexValue.get();
//                    max = maxIndexValue.get();
//                }
//                Double leftKey = min + ((max - min) * (1 - TopologyConfig.KER_RANGE_COVERAGE)) / 2;
//                Double rightKey = max - ((max - min) * (1 - TopologyConfig.KER_RANGE_COVERAGE)) / 2;



//                Long startTimeStamp = System.currentTimeMillis() - 10000;
//                Long endTimeStamp = System.currentTimeMillis();

                Long startTimeStamp = (long) 0;
//                Long endTimeStamp = System.currentTimeMillis();
                Long endTimeStamp = Long.MAX_VALUE;

                List<String> fileNames = filePartitionSchemaManager.search(leftKey, rightKey,
                        startTimeStamp, endTimeStamp);

                generateTreeSubQuery(leftKey, rightKey, endTimeStamp);

//                int numberOfSubqueries = 10;

//                if (fileNames.size() < numberOfSubqueries) {
//                    newQueryRequest.release();
//                    continue;
//                }
                


                int numberOfFilesToScan = fileNames.size();

                int numberOfSubqueries = numberOfFilesToScan;

                /* taskQueueModel
                   putSubqueriesToTaskQueue(numberOfSubqueries, leftKey, rightKey, fileNames, startTimeStamp, endTimeStamp);
                   sendSubqueriesFromTaskQueue();
                */

                /* shuffleGrouping
                   sendSubqueriesByshuffleGrouping(numberOfSubqueries, leftKey, rightKey, fileNames, startTimeStamp, endTimeStamp);
                 */

//                /* our method
                putSubquerisToTaskQueues(numberOfSubqueries, leftKey, rightKey, fileNames, startTimeStamp, endTimeStamp);
                sendSubqueriesFromTaskQueues();
//                 */

                System.out.println("query Id " + queryId + " " + numberOfFilesToScan + " files in decompositionbolt");

                collector.emit(NormalDistributionIndexingAndRangeQueryTopology.FileSystemQueryInformationStream,
                        new Values(queryId, numberOfSubqueries));

                ++queryId;

            }
        }
    }

    private void createTaskQueues(List<Integer> targetTasks) {
        for (Integer taskId : targetTasks) {
            ArrayBlockingQueue<RangeQuerySubQuery> taskQueue = new ArrayBlockingQueue<RangeQuerySubQuery>(TopologyConfig.TASK_QUEUE_CAPACITY);
            taskIdToTaskQueue.put(taskId, taskQueue);
        }
    }

    private void putSubqueriesToTaskQueue(int numberOfSubqueries, Double leftKey
            , Double rightKey, List<String> fileNames, Long startTimeStamp, Long endTimeStamp) {
        for (int i = 0; i < numberOfSubqueries; ++i) {
            RangeQuerySubQuery subQuery = new RangeQuerySubQuery(queryId, leftKey,  rightKey, fileNames.get(i), startTimeStamp, endTimeStamp);
            try {
                taskQueue.put(subQuery);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void generateTreeSubQuery(Double leftKey, Double rightKey, Long endTimeStamp) {

        int numberOfTasksToSearch = 0;

        Integer startPartitionId = balancedPartition.getPartitionId(leftKey);
        Integer endPartitionId = balancedPartition.getPartitionId(rightKey);


        Integer startTaskId = indexServers.get(startPartitionId);
        Integer endTaskId = indexServers.get(endPartitionId);

        for (Integer taskId = startTaskId; taskId <= endTaskId; ++taskId) {
            Long timestamp = indexTaskToTimestampMapping.get(taskId);
            if (timestamp <= endTimeStamp) {
                collector.emitDirect(taskId, NormalDistributionIndexingTopology.BPlusTreeQueryStream,
                        new Values(queryId, leftKey, rightKey));
                ++numberOfTasksToSearch;
            }
        }

        collector.emit(NormalDistributionIndexingTopology.BPlusTreeQueryInformationStream,
                new Values(queryId, numberOfTasksToSearch));
    }

    private void putSubquerisToTaskQueues(int numberOfSubqueries, Double leftKey
            , Double rightKey, List<String> fileNames, Long startTimeStamp, Long endTimeStamp) {
        for (int i = 0; i < numberOfSubqueries; ++i) {
            String fileName = fileNames.get(i);
            int index = Math.abs(fileName.hashCode()) % queryServers.size();
            Integer taskId = queryServers.get(index);
            ArrayBlockingQueue<RangeQuerySubQuery> taskQueue = taskIdToTaskQueue.get(taskId);
            RangeQuerySubQuery subQuery = new RangeQuerySubQuery(queryId, leftKey,  rightKey, fileName, startTimeStamp, endTimeStamp);
            if (taskQueue == null) {
                taskQueue = new ArrayBlockingQueue<RangeQuerySubQuery>(TopologyConfig.TASK_QUEUE_CAPACITY);
            }
            try {
                taskQueue.put(subQuery);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            taskIdToTaskQueue.put(taskId, taskQueue);
        }
    }


    private void sendSubqueriesFromTaskQueue() {
        for (Integer taskId : queryServers) {
            RangeQuerySubQuery subQuery = taskQueue.poll();
            if (subQuery != null) {
                collector.emitDirect(taskId, NormalDistributionIndexingAndRangeQueryTopology.FileSystemQueryStream
                        , new Values(subQuery));
            }
        }
    }


    private void sendSubqueriesFromTaskQueues() {
        for (Integer taskId : queryServers) {
            ArrayBlockingQueue<RangeQuerySubQuery> taskQueue = taskIdToTaskQueue.get(taskId);
            RangeQuerySubQuery subQuery = taskQueue.poll();
            if (subQuery != null) {
                collector.emitDirect(taskId, NormalDistributionIndexingAndRangeQueryTopology.FileSystemQueryStream
                        , new Values(subQuery));
            }
        }
    }

    private void sendSubqueriesByshuffleGrouping(int numberOfSubqueries, Double leftKey
            , Double rightKey, List<String> fileNames, Long startTimeStamp, Long endTimeStamp) {
        for (int i = 0; i < numberOfSubqueries; ++i) {
            RangeQuerySubQuery subQuery = new RangeQuerySubQuery(queryId, leftKey, rightKey, fileNames.get(i), startTimeStamp, endTimeStamp);
            collector.emit(NormalDistributionIndexingAndRangeQueryTopology.FileSystemQueryStream
                    , new Values(subQuery));
        }
    }


    private void sendSubqueryToTask(int taskId) {
        RangeQuerySubQuery subQuery = taskQueue.poll();

        if (subQuery != null) {
            collector.emitDirect(taskId, NormalDistributionIndexingTopology.FileSystemQueryStream
                    , new Values(subQuery));
        }

    }



    private void sendSubquery(int taskId) {

        ArrayBlockingQueue<RangeQuerySubQuery> taskQueue = taskIdToTaskQueue.get(taskId);

        RangeQuerySubQuery subQuery = taskQueue.poll();

        if (subQuery == null) {

            taskQueue = getLongestQueue();

            subQuery = taskQueue.poll();

        }
        if (subQuery != null) {
            collector.emitDirect(taskId, NormalDistributionIndexingTopology.FileSystemQueryStream
                    , new Values(subQuery));
        }
    }

    private ArrayBlockingQueue<RangeQuerySubQuery> getLongestQueue() {
        List<ArrayBlockingQueue<RangeQuerySubQuery>> taskQueues
                = new ArrayList<ArrayBlockingQueue<RangeQuerySubQuery>>(taskIdToTaskQueue.values());

        Collections.sort(taskQueues, (taskQueue1, taskQueue2) -> Integer.compare(taskQueue2.size(), taskQueue1.size()));

        ArrayBlockingQueue<RangeQuerySubQuery> taskQueue = taskQueues.get(0);

        return taskQueue;
    }

    private List<RangeQuerySubQuery> generateSubqueries(Long queryId, Double leftKey
            , Double rightKey, List<String> fileNames, Long startTimeStamp, Long endTimeStamp) {
        List<RangeQuerySubQuery> subQueries = new ArrayList<>();

        RangeQuerySubQuery subQuery = new RangeQuerySubQuery(queryId, leftKey,
                rightKey, null, startTimeStamp, endTimeStamp);
        subQueries.add(subQuery);

        for (String fileName : fileNames) {
            subQuery = new RangeQuerySubQuery(queryId, leftKey, rightKey, fileName, startTimeStamp, endTimeStamp);
            subQueries.add(subQuery);
        }

        return subQueries;
    }

    private void setTimestamps() {

        indexTaskToTimestampMapping = new HashMap<>();

        for (Integer taskId : indexServers) {
            indexTaskToTimestampMapping.put(taskId, System.currentTimeMillis());
        }
    }

}