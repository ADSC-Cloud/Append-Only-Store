package indexingTopology.bolt;

import indexingTopology.api.client.QueryRequest;
import indexingTopology.api.client.QueryResponse;
import indexingTopology.api.client.SchemaQueryRequest;
import indexingTopology.api.server.QueryHandle;
import indexingTopology.api.server.SchemaQueryHandle;
import indexingTopology.api.server.Server;
import indexingTopology.api.server.ServerHandle;
import indexingTopology.config.TopologyConfig;
import indexingTopology.common.data.DataSchema;
import indexingTopology.common.data.PartialQueryResult;
import indexingTopology.common.Query;
import indexingTopology.metadata.SchemaManager;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by acelzj on 11/15/16.
 */
public class QueryCoordinatorWithQueryReceiverServerBolt<T extends Number & Comparable<T>> extends QueryCoordinatorBolt<T> {

    private final int port;

    AtomicLong queryId;

    Server server;

    Map<Long, LinkedBlockingQueue<PartialQueryResult>> queryIdToPartialQueryResults;

//    Map<Long, Semaphore> queryIdToPartialQueryResultSemphore;

    private static final Logger LOG = LoggerFactory.getLogger(QueryCoordinatorWithQueryReceiverServerBolt.class);

    public QueryCoordinatorWithQueryReceiverServerBolt(T lowerBound, T upperBound, int port, TopologyConfig config, DataSchema schema) {
        super(lowerBound, upperBound, config, schema);
        this.port = port;
    }

    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        super.prepare(map, topologyContext, outputCollector);
        queryId = new AtomicLong(0);
        queryIdToPartialQueryResults = new HashMap<>();


        server = new Server(port, QueryServerHandle.class, new Class[]{LinkedBlockingQueue.class, AtomicLong.class, Map.class, SchemaManager.class}, pendingQueue, queryId, queryIdToPartialQueryResults, schemaManager);
        server.startDaemon();
//        queryIdToPartialQueryResultSemphore = new HashMap<>();
    }

    @Override
    public void cleanup() {
        server.endDaemon();
        super.cleanup();
    }

    @Override
    public void handlePartialQueryResult(Long queryId, PartialQueryResult partialQueryResult) {

        LinkedBlockingQueue<PartialQueryResult> results = queryIdToPartialQueryResults.get(queryId);
        if (results == null)
            throw new RuntimeException("received query results for an unregistered id.");

        try {
            results.put(partialQueryResult);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

//        Semaphore semaphore = queryIdToPartialQueryResultSemphore.computeIfAbsent(queryId, k -> new Semaphore(0));
//        semaphore.release();
    }

    static public class QueryServerHandle<T extends Number & Comparable<T>> extends ServerHandle implements QueryHandle, SchemaQueryHandle {

        LinkedBlockingQueue<List<Query<T>>> pendingQueryQueue;
        AtomicLong queryIdGenerator;
        AtomicLong superQueryIdGenerator;
        Map<Long, LinkedBlockingQueue<PartialQueryResult>> queryresults;
        SchemaManager schemaManager;
        public QueryServerHandle(LinkedBlockingQueue<List<Query<T>>> pendingQueryQueue, AtomicLong queryIdGenerator,
                                 Map<Long, LinkedBlockingQueue<PartialQueryResult>> queryresults,
                                 SchemaManager schemaManager) {
            this.pendingQueryQueue = pendingQueryQueue;
            this.queryresults = queryresults;
            this.queryIdGenerator = queryIdGenerator;
            this.schemaManager = schemaManager;
        }

        @Override
        public void handle(QueryRequest request) throws IOException {
            try {
                final long queryid = queryIdGenerator.getAndIncrement();

                DataSchema outputSchema;
                if (request.aggregator != null) {
                    outputSchema = request.aggregator.getOutputDataSchema();
                } else {
                    outputSchema = schemaManager.getDefaultSchema();
                }

                LinkedBlockingQueue<PartialQueryResult> results =
                        queryresults.computeIfAbsent(queryid, k -> new LinkedBlockingQueue<>());

                LOG.info("A new Query{} ({}, {}, {}, {}) is added to the pending queue.", queryid,
                        request.low, request.high, request.startTime, request.endTime);
                final List<Query<T>> queryList = new ArrayList<>();
                queryList.add(new Query(queryid, request.low, request.high, request.startTime,
                        request.endTime, request.predicate, request.aggregator, request.sorter, request.equivalentPredicate));
                pendingQueryQueue.put(queryList);

                boolean eof = false;
                while(!eof) {
                    PartialQueryResult partialQueryResult = results.take();
                    System.out.println("!!!!!!!!!!!"+results.size());
                    eof = partialQueryResult.getEOFFlag();
                    objectOutputStream.writeUnshared(new QueryResponse(partialQueryResult, outputSchema, queryid));
                    objectOutputStream.reset();
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void handle(SchemaQueryRequest request) throws IOException {
            DataSchema schema = schemaManager.getDefaultSchema();
            objectOutputStream.writeUnshared(schema);
            objectOutputStream.reset();
        }
    }
}
