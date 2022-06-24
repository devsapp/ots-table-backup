package com.example.otstablebackup;

import com.alicloud.openservices.tablestore.SyncClient;
import com.alicloud.openservices.tablestore.TableStoreException;
import com.alicloud.openservices.tablestore.TunnelClient;
import com.alicloud.openservices.tablestore.model.*;
import com.alicloud.openservices.tablestore.model.search.*;
import com.alicloud.openservices.tablestore.model.tunnel.*;
import com.alicloud.openservices.tablestore.tunnel.worker.TunnelWorker;
import com.alicloud.openservices.tablestore.tunnel.worker.TunnelWorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.system.SystemProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@RestController
@Configuration
public class OtsTableBackupApplication {
    private static final Logger logger = LoggerFactory.getLogger(OtsTableBackupApplication.class);

    private static final String TUNNEL_NAME = "FCBackupTunnel";

    private static final String FC_STATUS_HEADER = "x-fc-status";
    @Value("${TUNNEL_TYPE: BaseAndStream}")
    private TunnelType tunnelType;

    @Value("${BACKUP_END_TIME: 1970-01-01 00:00:00}")
    private String backupEndTime;

    @Value("${DROP_IF_EXIST: false}")
    private boolean dropIfExist;

    @Value("${SOURCE_ENDPOINT}")
    private String sourceEndpoint;

    @Value("${TARGET_ENDPOINT}")
    private String targetEndpoint;

    @Value("${SOURCE_TABLE}")
    private String sourceTable;

    @Value("${TARGET_TABLE}")
    private String targetTable;

    private TableEntity source;

    private TableEntity target;

    private SyncClient sourceClient;
    private SyncClient targetClient;

    private TunnelWorker tunnelWorker;

    private TunnelWorkerConfig tunnelWorkerConfig;

    private TunnelClient tunnelClient;

    private void copyFromSourceTable(DescribeTableResponse response) {
        TableMeta sourceMeta = response.getTableMeta();
        response.getTableOptions().setMaxTimeDeviation(Long.MAX_VALUE / 1000000);

        TableMeta tableMeta = new TableMeta(target.getTableName());
        // add primary key column, from the source table
        for (PrimaryKeySchema primaryKeySchema : sourceMeta.getPrimaryKeyList()) {
            tableMeta.addPrimaryKeyColumn(new PrimaryKeySchema(primaryKeySchema.getName(), primaryKeySchema.getType()));
        }
        for (DefinedColumnSchema definedColumnSchema : sourceMeta.getDefinedColumnsList()) {
            tableMeta.addDefinedColumn(new DefinedColumnSchema(definedColumnSchema.getName(), definedColumnSchema.getType()));
        }

        CreateTableRequest request = new CreateTableRequest(tableMeta,
                response.getTableOptions(),
                new ReservedThroughput(response.getReservedThroughputDetails().getCapacityUnit()),
                response.getIndexMeta());

        if (response.getSseDetails().isEnable()) {
            request.setSseSpecification(new SSESpecification(
                    response.getSseDetails().isEnable(),
                    response.getSseDetails().getKeyType()));
        }

        targetClient.createTable(request);

        // create search index
        ListSearchIndexRequest listSearchIndexRequest = new ListSearchIndexRequest();
        listSearchIndexRequest.setTableName(source.getTableName());
        ListSearchIndexResponse listSearchIndexResponse = sourceClient.listSearchIndex(listSearchIndexRequest);
        if (!listSearchIndexResponse.getIndexInfos().isEmpty()) {
            listSearchIndexResponse
                    .getIndexInfos()
                    .forEach(indexInfo -> {
                        DescribeSearchIndexRequest describeSearchIndexRequest = new DescribeSearchIndexRequest();
                        describeSearchIndexRequest.setIndexName(indexInfo.getIndexName());
                        describeSearchIndexRequest.setTableName(indexInfo.getTableName());
                        DescribeSearchIndexResponse describeSearchIndexResponse =
                                sourceClient.describeSearchIndex(describeSearchIndexRequest);

                        CreateSearchIndexRequest createSearchIndexRequest = new CreateSearchIndexRequest(
                                target.getTableName(), indexInfo.getIndexName());
                        createSearchIndexRequest.setIndexSchema(describeSearchIndexResponse.getSchema());
                        targetClient.createSearchIndex(createSearchIndexRequest);
                    });
        }
    }

    private String createTunnel() {
        CreateTunnelRequest request = new CreateTunnelRequest(source.getTableName(), TUNNEL_NAME, tunnelType);
        CreateTunnelResponse resp = tunnelClient.createTunnel(request);
        String tunnelId = resp.getTunnelId();
        logger.info("Create Tunnel success, tunnel id: {}", tunnelId);
        return tunnelId;
    }

    private long getEndTime() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            Date date = dateFormat.parse(backupEndTime);
            return date.getTime();
        } catch (Exception e) {
            logger.error("Parse end time with error:{}, use default value 0", e.toString());
        }
        return 0;
    }

    /**
     * initialize: <a href="https://help.aliyun.com/document_detail/425056.html">...</a>
     * Before backup, check whether the backup table exists.
     * If it does not exist, create a table with the same schema as the source table
     */
    @PostMapping("/initialize")
    public ResponseEntity<String> initTable(@RequestHeader Map<String, String> headers) {
        logger.info("System {}", SystemProperties.get("SOURCE_TABLE"));
        String accessKeyId = headers.get("x-fc-access-key-id");
        String accessKeySecret = headers.get("x-fc-access-key-secret");
        String stsToken = headers.get("x-fc-security-token");
        String requestId = headers.get("x-fc-request-id");
        HttpHeaders fcHeaders = new HttpHeaders();

        try {
            logger.info("FC Initialize Start RequestId: {}", requestId);
            source = new TableEntity(sourceEndpoint, sourceTable);
            target = new TableEntity(targetEndpoint, targetTable);
            sourceClient = new SyncClient(source.getEndpoint(), accessKeyId, accessKeySecret,
                    source.getInstanceName(), stsToken);
            targetClient = new SyncClient(target.getEndpoint(), accessKeyId, accessKeySecret,
                    target.getInstanceName(), stsToken);

            DescribeTableRequest sourceRequest = new DescribeTableRequest(source.getTableName());
            DescribeTableResponse sourceResponse = sourceClient.describeTable(sourceRequest);

            try {
                DescribeTableRequest targetRequest = new DescribeTableRequest(target.getTableName());
                DescribeTableResponse targetResponse = targetClient.describeTable(targetRequest);
                logger.info("Target table {} exist, table meta is {}",
                        target.getTableName(), targetResponse.getTableMeta());

                if (tunnelType == TunnelType.BaseData && dropIfExist) {
                    logger.info("Target table {} exist, and use DropIfExist strategy to delete the table",
                            target.getTableName());
                    // delete table index
                    targetResponse
                            .getIndexMeta()
                            .forEach(indexMeta -> {
                                logger.info("Delete the table index, table:{}, index:{}",
                                        target.getTableName(), indexMeta.getIndexName());
                                targetClient.deleteIndex(new DeleteIndexRequest(target.getTableName(), indexMeta.getIndexName()));
                            });

                    // delete search index
                    ListSearchIndexRequest listSearchIndexRequest = new ListSearchIndexRequest();
                    listSearchIndexRequest.setTableName(target.getTableName());
                    targetClient.listSearchIndex(listSearchIndexRequest)
                            .getIndexInfos()
                            .forEach(searchIndexInfo -> {
                                logger.info("Delete the search index, table:{}, index:{}",
                                        searchIndexInfo.getTableName(), searchIndexInfo.getIndexName());
                                DeleteSearchIndexRequest deleteSearchIndexRequest = new DeleteSearchIndexRequest();
                                deleteSearchIndexRequest.setIndexName(searchIndexInfo.getIndexName());
                                deleteSearchIndexRequest.setTableName(searchIndexInfo.getTableName());
                                targetClient.deleteSearchIndex(deleteSearchIndexRequest);
                            });

                    // delete table
                    DeleteTableRequest deleteTableRequest = new DeleteTableRequest(target.getTableName());
                    logger.info("Delete the target table:{}", target.getTableName());
                    targetClient.deleteTable(deleteTableRequest);

                    logger.info("Target table {} drop success, try to recreate", target.getTableName());
                    copyFromSourceTable(sourceResponse);
                }
            } catch (TableStoreException e) {
                if (Objects.equals(e.getErrorCode(), "OTSObjectNotExist")) {
                    logger.warn("Target table {} not exist, try to create", target.getTableName());
                    copyFromSourceTable(sourceResponse);
                } else {
                    throw e;
                }
            }

            fcHeaders.add(FC_STATUS_HEADER, String.valueOf(HttpStatus.OK.value()));
            return new ResponseEntity<>("Initialize Success", fcHeaders, HttpStatus.OK);
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Initialize Failed, err:{}", e.toString());
            fcHeaders.add(FC_STATUS_HEADER, String.valueOf(HttpStatus.NOT_FOUND.value()));
            return new ResponseEntity<>(e.toString(), fcHeaders, HttpStatus.NOT_FOUND);
        } finally {
            logger.info("FC Initialize End RequestId: {}", requestId);
        }
    }

    /**
     * invoke: <a href="https://help.aliyun.com/document_detail/191342.html">...</a>
     * Before backup, check whether the tunnel exists. If not, create a tunnel
     */
    @PostMapping("/invoke")
    public ResponseEntity<String> backupTable(@RequestHeader Map<String, String> headers) {
        String accessKeyId = headers.get("x-fc-access-key-id");
        String accessKeySecret = headers.get("x-fc-access-key-secret");
        String stsToken = headers.get("x-fc-security-token");
        HttpHeaders fcHeaders = new HttpHeaders();

        try {
            tunnelClient = new TunnelClient(source.getEndpoint(),
                    accessKeyId, accessKeySecret, source.getInstanceName(), stsToken);

            // Create or reuse tunnel
            String tunnelId;
            try {
                DescribeTunnelRequest request = new DescribeTunnelRequest(source.getTableName(), TUNNEL_NAME);
                DescribeTunnelResponse resp = tunnelClient.describeTunnel(request);
                tunnelId = resp.getTunnelInfo().getTunnelId();
                if (resp.getTunnelInfo().isExpired()) {
                    logger.warn("Tunnel {} is expired, will remove the tunnel before", tunnelId);
                    tunnelClient.deleteTunnel(new DeleteTunnelRequest(source.getTableName(), TUNNEL_NAME));
                    tunnelId = createTunnel();
                } else if (resp.getTunnelInfo().getTunnelType() != tunnelType) {
                    logger.warn("Tunnel {} is not match the type of {}, will remove the tunnel before", tunnelId, tunnelType);
                    tunnelClient.deleteTunnel(new DeleteTunnelRequest(source.getTableName(), TUNNEL_NAME));
                    tunnelId = createTunnel();
                }
            } catch (TableStoreException e) {
                if (e.getMessage().contains("tunnel not exist")) {
                    logger.warn("Tunnel backupTunnel not exist, try to create");
                    tunnelId = createTunnel();
                } else {
                    throw e;
                }
            }

            BackupProcessor processor = new BackupProcessor(target, targetClient, logger);
            tunnelWorkerConfig = new TunnelWorkerConfig(processor);
            tunnelWorker = new TunnelWorker(tunnelId, tunnelClient, tunnelWorkerConfig);
            tunnelWorker.connectAndWorking();

            final long endTime = getEndTime();
            final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleAtFixedRate(() -> {
                DescribeTunnelResponse resp = tunnelClient.describeTunnel(new DescribeTunnelRequest(
                        source.getTableName(), TUNNEL_NAME
                ));
                // Successfully backup
                if (processor.getDone().get()) {
                    if (tunnelType.equals(TunnelType.BaseData) && resp.getChannelInfos().isEmpty()) {
                        logger.info("Table copy with tunnel type {} finished", tunnelType);
                        executor.shutdown();
                    } else if (resp.getTunnelConsumePoint().getTime() > endTime) {
                        logger.info("Table copy with tunnel type {} is finished", tunnelType);
                        executor.shutdown();
                    }
                }
            }, 0, 5, TimeUnit.SECONDS);

            boolean finished = executor.awaitTermination(600, TimeUnit.SECONDS);
            logger.info("Executor shutdown success");
            if (!finished) {
                fcHeaders.add(FC_STATUS_HEADER, String.valueOf(HttpStatus.NOT_FOUND.value()));
                return new ResponseEntity<>("Waiting Timeout", fcHeaders, HttpStatus.NOT_FOUND);
            }

            fcHeaders.add(FC_STATUS_HEADER, String.valueOf(HttpStatus.OK.value()));
            return new ResponseEntity<>("Invoke Success", fcHeaders, HttpStatus.OK);
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Invoke Failed, err {}", e.toString());
            fcHeaders.add(FC_STATUS_HEADER, String.valueOf(HttpStatus.NOT_FOUND.value()));
            return new ResponseEntity<>(e.toString(), fcHeaders, HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping("/pre-stop")
    private ResponseEntity<String> shutdown(@RequestHeader Map<String, String> headers) {
        HttpHeaders fcHeaders = new HttpHeaders();

        if (tunnelWorker != null) {
            tunnelWorker.shutdown();
        }
        if (tunnelWorkerConfig != null) {
            tunnelWorkerConfig.shutdown();
        }

        if (tunnelType.equals(TunnelType.BaseData)) {
            tunnelClient.deleteTunnel(new DeleteTunnelRequest(source.getTableName(), TUNNEL_NAME));
            logger.info("Tunnel {} with the BaseData type, delete the tunnel success", TUNNEL_NAME);
        } else if (tunnelClient != null) {
            tunnelClient.shutdown();
        }

        if (sourceClient != null) {
            sourceClient.shutdown();
        }
        if (targetClient != null) {
            targetClient.shutdown();
        }
        logger.info("Table store worker shutdown success");

        fcHeaders.add(FC_STATUS_HEADER, String.valueOf(HttpStatus.OK.value()));
        return new ResponseEntity<>("PreStop Success", fcHeaders, HttpStatus.OK);
    }

    public static void main(String[] args) {
        SpringApplication.run(OtsTableBackupApplication.class, args);
    }
}
